/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.hasor.neta.channel.ProtoRcvQueue;

/**
 * A read-only {@link ByteBuf} view over a {@link ProtoRcvQueue ProtoRcvQueue&lt;ByteBuf&gt;},
 * presenting all queued ByteBuf messages as a single contiguous readable buffer.
 * <p>
 * This class provides zero-copy composite reading across multiple queued ByteBuf messages,
 * allowing protocol decoders to work with fragmented data as if it were a single buffer.
 * <pre>
 * receive queue snapshot
 *   ProtoRcvQueue<ByteBuf>
 *     +--------+--------+--------+--------+
 *     | msg[0] | msg[1] | msg[2] |  ...   |
 *     +--------+--------+--------+--------+
 *         |        |        |
 *         v        v        v
 *     +-------+ +-------+ +-------+
 *     | buf0  | | buf1  | | buf2  |
 *     +-------+ +-------+ +-------+
 * QueueByteBuf component table
 *   +--------------+----------------+--------+
 *   | component[0] | offset = 0      | len=l0 |
 *   | component[1] | offset = l0     | len=l1 |
 *   | component[2] | offset = l0+l1  | len=l2 |
 *   +--------------+----------------+--------+
 * logical read path
 *   readerIndex ---> [ buf0 remainder ][ buf1 ][ buf2 ] ... ---> writerIndex
 * </pre>
 * <p>
 * <b>Write operations are not supported</b> — all write/put methods throw
 * {@link UnsupportedOperationException}.
 * <p>
 * Component ByteBufs are peeked (not taken) from the queue.
 * Call {@link #markReader()} to consume fully-read messages from the queue.
 * Call {@link #refresh()} to incorporate newly arrived messages.
 * <p>
 * Typical usage in a protocol handler:
 * <pre>
 * QueueByteBuf buf = ByteBufUtils.queueBuffer(src);
 * try {
 *     if (buf.readableBytes() &lt; 4) return ProtoStatus.Stop;
 *     int len = buf.readInt32();
 *     if (buf.readableBytes() &lt; len) return ProtoStatus.Stop;
 *     String payload = buf.readString(len, charset);
 *     buf.markReader();
 *     dst.offer(payload);
 *     return ProtoStatus.Next;
 * } finally {
 *     buf.free();
 * }
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-07-08
 */
final class QueueByteBuf extends AbstractByteBuf {
    private final ProtoRcvQueue<ByteBuf> queue;
    private final List<Component>        components = new ArrayList<>();
    private       int                    totalCapacity;
    private       int                    lastAccessedComponentIndex;
    private       int                    queuePeekCount;

    /**
     * Creates a QueueByteBuf wrapping the given receive queue.
     * All currently available messages in the queue are immediately incorporated.
     * <p>
     * Use {@link ByteBufUtils#queueBuffer(ProtoRcvQueue)} to create instances.
     * @param queue the receive queue to wrap (must not be null)
     * @throws NullPointerException if queue is null
     */
    QueueByteBuf(ProtoRcvQueue<ByteBuf> queue) {
        super();
        this.alloc = ByteBufAllocator.DEFAULT;
        if (queue == null) {
            throw new NullPointerException("queue must not be null");
        }
        this.queue = queue;
        refresh();
    }

    /**
     * Refreshes this buffer by peeking at new messages in the queue.
     * Messages that were already incorporated are not re-processed.
     * New messages become immediately readable.
     * @return this QueueByteBuf for chaining
     */
    public QueueByteBuf refresh() {
        checkFree();
        int currentQueueSize = this.queue.queueSize();
        if (currentQueueSize > this.queuePeekCount) {
            List<ByteBuf> allMessages = this.queue.peekMessage(currentQueueSize);
            for (int i = this.queuePeekCount; i < allMessages.size(); i++) {
                ByteBuf buf = allMessages.get(i);
                int readable = buf.readableBytes();
                if (readable == 0) {
                    continue;
                }

                Component c = new Component();
                c.buf = buf;
                c.compositeOffset = this.totalCapacity;
                c.length = readable;

                this.components.add(c);
                this.totalCapacity += readable;
            }
            this.queuePeekCount = currentQueueSize;

            // Make new data immediately readable
            this.writerIndex = this.totalCapacity;
            this.markedWriterIndex = this.totalCapacity;
        }
        return this;
    }

    /** Returns the underlying receive queue. */
    public ProtoRcvQueue<ByteBuf> queue() {
        return this.queue;
    }

    /** Returns the number of component ByteBufs in this buffer. */
    public int numComponents() {
        return this.components.size();
    }

    @Override
    public int capacity() {
        return this.totalCapacity;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    // ---- Component Lookup ----

    /**
     * Find the component containing the given absolute offset.
     * Uses a cached last-accessed index to optimize sequential reads (O(1) in the common case).
     * Falls back to binary search (O(log n)) if the cache misses.
     */
    private Component findComponent(int offset) {
        // Fast path: check cached component first (optimizes sequential reads)
        int cachedIdx = this.lastAccessedComponentIndex;
        if (cachedIdx < this.components.size()) {
            Component cached = this.components.get(cachedIdx);
            if (offset >= cached.compositeOffset && offset < cached.compositeOffset + cached.length) {
                return cached;
            }
            // Check the next component (common sequential read pattern)
            int nextIdx = cachedIdx + 1;
            if (nextIdx < this.components.size()) {
                Component next = this.components.get(nextIdx);
                if (offset >= next.compositeOffset && offset < next.compositeOffset + next.length) {
                    this.lastAccessedComponentIndex = nextIdx;
                    return next;
                }
            }
        }

        // Slow path: binary search
        int idx = binarySearchComponentIndex(offset);
        this.lastAccessedComponentIndex = idx;
        return this.components.get(idx);
    }

    /**
     * Binary search to find the component index containing the given absolute offset.
     */
    private int binarySearchComponentIndex(int offset) {
        int lo = 0;
        int hi = this.components.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Component c = this.components.get(mid);
            if (offset < c.compositeOffset) {
                hi = mid - 1;
            } else if (offset >= c.compositeOffset + c.length) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        throw new IndexOutOfBoundsException("composite offset " + offset + " is out of range [0, " + this.totalCapacity + ")");
    }

    // ---- Read (Get) Operations ----

    @Override
    protected byte _getByte(int offset) {
        checkFree();
        Component c = findComponent(offset);
        int localOffset = offset - c.compositeOffset;
        return c.buf.getByte(localOffset);
    }

    /**
     * Optimized readByte for sequential reads.
     * Bypasses AbstractByteBuf.readByte() → nextReadable() → _getByte() chain.
     * Uses inlined component lookup for O(1) access in the common sequential case.
     */
    @Override
    public byte readByte() {
        if (this.freed) {
            throw new IllegalStateException("has been released.");
        }
        int idx = this.readerIndex;
        if (idx >= this.markedWriterIndex) {
            throw new IndexOutOfBoundsException("read out of range. length: 1 (expected: 0 ~ 0)");
        }
        this.readerIndex = idx + 1;

        // Inline findComponent cache hit path
        int ci = this.lastAccessedComponentIndex;
        Component c;
        if (ci < this.components.size()) {
            c = this.components.get(ci);
            if (idx < c.compositeOffset || idx >= c.compositeOffset + c.length) {
                // Try next component (common sequential read crossing boundary)
                int ni = ci + 1;
                if (ni < this.components.size()) {
                    c = this.components.get(ni);
                    if (idx >= c.compositeOffset && idx < c.compositeOffset + c.length) {
                        this.lastAccessedComponentIndex = ni;
                    } else {
                        c = findComponent(idx);
                    }
                } else {
                    c = findComponent(idx);
                }
            }
        } else {
            c = findComponent(idx);
        }

        int localOffset = idx - c.compositeOffset;
        return c.buf.getByte(localOffset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();
        int remaining = dstLen;
        int currentDstOffset = dstOffset;
        int currentOffset = offset;

        while (remaining > 0) {
            Component c = findComponent(currentOffset);
            int localOffset = currentOffset - c.compositeOffset;
            int available = c.length - localOffset;
            int toRead = Math.min(remaining, available);

            c.buf.getBytes(localOffset, dst, currentDstOffset, toRead);

            remaining -= toRead;
            currentDstOffset += toRead;
            currentOffset += toRead;
        }

        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();
        int remaining = dstLen;
        int currentOffset = offset;

        while (remaining > 0) {
            Component c = findComponent(currentOffset);
            int localOffset = currentOffset - c.compositeOffset;
            int available = c.length - localOffset;
            int toRead = Math.min(remaining, available);

            c.buf.getBuffer(localOffset, dst, toRead);

            remaining -= toRead;
            currentOffset += toRead;
        }

        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();
        int remaining = dstLen;
        int currentOffset = offset;

        while (remaining > 0) {
            Component c = findComponent(currentOffset);
            int localOffset = currentOffset - c.compositeOffset;
            int available = c.length - localOffset;
            int toRead = Math.min(remaining, available);

            c.buf.getBuffer(localOffset, dst, toRead);

            remaining -= toRead;
            currentOffset += toRead;
        }

        return dstLen;
    }

    // ---- Write (Put) Operations - Not Supported ----

    @Override
    protected void _putByte(int offset, byte b) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    /** Override to report 0 writable bytes — this buffer is read-only. */
    @Override
    public int writableBytes() {
        return 0;
    }

    /** Override to report 0 written bytes — data is provided through the queue. */
    @Override
    public int writtenBytes() {
        return 0;
    }

    /** This buffer is read-only — writes are not supported. */
    @Override
    public void writeByte(byte n) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    /** This buffer is read-only — writes are not supported. */
    @Override
    public int writeBytes(byte[] src, int off, int len) {
        throw new UnsupportedOperationException("QueueByteBuf does not support direct writes.");
    }

    // ---- Mark & Consume ----

    /**
     * Marks the current reader position and consumes all fully-read ByteBuf messages
     * from the underlying queue.
     * <p>
     * After this call, data before the current {@code readerIndex} is permanently consumed
     * from the queue via {@link ProtoRcvQueue#skipMessage(int)}, and the internal indices
     * are reset — {@code readerIndex} and {@code markedReaderIndex} both become 0.
     * <p>
     * Partially consumed ByteBuf messages have their {@code readerIndex} advanced to reflect
     * the consumed portion.
     * <p>
     * <b>Warning:</b> After calling this method, {@link #resetReader()} will only revert to
     * the position of this mark (i.e. 0), not to any earlier position — data before the mark
     * has been released from the queue.
     * @return this buffer
     */
    @Override
    public ByteBuf markReader() {
        super.markReader();
        discardReadBytes();
        return this;
    }

    // ---- Discard & Consume ----

    /**
     * Discards read bytes and consumes fully-read ByteBuf messages from the underlying queue.
     * <p>
     * Fully consumed ByteBuf messages are removed via {@link ProtoRcvQueue#skipMessage(int)}.
     * A partially consumed ByteBuf message has its {@code readerIndex} advanced.
     * <p>
     * The underlying queue is consumed destructively via {@link ProtoRcvQueue#skipMessage(int)}.
     * Consumed messages are removed immediately and are not restored by the queue.
     */
    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }

        int discardOffset = this.readerIndex;
        int skipCount = 0;

        // Remove fully consumed components and handle partial consumption
        Iterator<Component> it = this.components.iterator();
        while (it.hasNext()) {
            Component c = it.next();
            int endOffset = c.compositeOffset + c.length;
            if (endOffset <= discardOffset) {
                // Fully consumed — remove from component list
                skipCount++;
                it.remove();
            } else if (c.compositeOffset < discardOffset) {
                // Partially consumed — advance component's reader position
                int consumed = discardOffset - c.compositeOffset;
                c.buf.skipReadableBytes(consumed);
                c.length -= consumed;
                break;
            } else {
                break;
            }
        }

        // Skip consumed messages from queue
        if (skipCount > 0) {
            this.queue.skipMessage(skipCount);
            this.queuePeekCount -= skipCount;
        }

        // Recompute composite offsets
        int offset = 0;
        for (Component c : this.components) {
            c.compositeOffset = offset;
            offset += c.length;
        }
        this.totalCapacity = offset;

        // Reset component cache
        this.lastAccessedComponentIndex = 0;

        // Adjust indices
        this.markedReaderIndex = 0;
        this.readerIndex = 0;
        this.markedWriterIndex = this.totalCapacity;
        this.writerIndex = this.totalCapacity;
    }

    // ---- Slice ----

    /**
     * Splits off the front portion of this buffer as a zero-copy {@link CompositeByteBuf}.
     * <p>
     * The returned ByteBuf is a read-only composite view. Components that fall entirely within
     * the front portion are added via {@link CompositeByteBuf#addComponent(ByteBuf)} (zero-copy,
     * retained). Boundary components that are only partially in the front portion are copied.
     * <p>
     * After slicing, this buffer's readerIndex advances past the split point and
     * {@link #discardReadBytes()} is called to consume the front portion from the queue.
     * @param splitOffset number of bytes (relative to markedReaderIndex) to slice off
     * @return a CompositeByteBuf containing the front portion, or {@link ByteBuf#EMPTY} if empty
     */
    @Override
    public ByteBuf sliceOff(int splitOffset) {
        checkFree();
        if (splitOffset <= 0) {
            return ByteBuf.EMPTY;
        }

        int absoluteSplit = this.markedReaderIndex + splitOffset;
        if (absoluteSplit > this.markedWriterIndex) {
            absoluteSplit = this.markedWriterIndex;
        }

        int frontLen = absoluteSplit - this.markedReaderIndex;
        if (frontLen <= 0) {
            return ByteBuf.EMPTY;
        }

        CompositeByteBuf result = new CompositeByteBuf(alloc());
        int startOffset = this.markedReaderIndex;
        int endOffset = absoluteSplit;

        for (Component c : this.components) {
            int cStart = c.compositeOffset;
            int cEnd = c.compositeOffset + c.length;

            if (cEnd <= startOffset) {
                continue; // before front portion
            }
            if (cStart >= endOffset) {
                break; // past front portion
            }

            // Component overlaps with front portion
            int overlapStart = Math.max(cStart, startOffset) - cStart; // local offset in component
            int overlapEnd = Math.min(cEnd, endOffset) - cStart;
            int overlapLen = overlapEnd - overlapStart;

            if (overlapStart == 0 && overlapLen == c.length) {
                // Entire component is in front portion — zero-copy (addComponent retains)
                result.addComponent(c.buf);
            } else {
                // Partial component — copy the overlap portion
                byte[] partial = new byte[overlapLen];
                c.buf.getBytes(overlapStart, partial, 0, overlapLen);
                result.addComponent(ByteBuf.wrap(partial));
            }
        }

        // Advance readerIndex past the sliced-off portion and discard
        this.readerIndex = absoluteSplit;
        this.markedReaderIndex = absoluteSplit;
        discardReadBytes();

        return result;
    }

    // ---- Copy ----

    @Override
    public ByteBuf copy() {
        checkFree();
        int readable = readableBytes();
        if (readable == 0) {
            return ByteBuf.EMPTY;
        }

        ByteBuf newBuf = alloc().buffer(readable, readable);
        byte[] data = new byte[readable];
        _getBytes(this.readerIndex, data, 0, readable);
        newBuf.writeBytes(data);
        newBuf.markWriter();
        newBuf.order(this.order());
        return newBuf;
    }

    // ---- Lifecycle ----

    @Override
    protected void _free() {
        // Don't release component ByteBufs — the queue owns them
        this.components.clear();
        this.totalCapacity = 0;
        this.lastAccessedComponentIndex = 0;
        this.queuePeekCount = 0;
    }

    @Override
    protected String getSimpleName() {
        return "QueueByteBuf";
    }

    /** Component entry tracking a ByteBuf and its offset within the composite view. */
    private static class Component {
        ByteBuf buf;
        int     compositeOffset; // start offset within the composite
        int     length;          // number of bytes contributed by this component
    }
}
