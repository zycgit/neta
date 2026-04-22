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
import net.hasor.neta.channel.data.ProtoRcvQueue;
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
public final class QueueByteBuf extends AbstractByteBuf {
    private final ProtoRcvQueue<ByteBuf> queue;
    private final ArrayList<Component>   components = new ArrayList<>();
    private int                          totalCapacity;
    private int                          lastAccessedComponentIndex;
    private int                          queuePeekCount;

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
        this.refresh();
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
            this.components.ensureCapacity(this.components.size() + (currentQueueSize - this.queuePeekCount));
            this.queue.peekEachMessage(this.queuePeekCount, this::appendReadableComponent);
            this.queuePeekCount = currentQueueSize;

            // Make new data immediately readable
            this.writerIndex = this.totalCapacity;
            this.markedWriterIndex = this.totalCapacity;
        }
        return this;
    }

    private void appendReadableComponent(ByteBuf buf) {
        if (buf == null) {
            return;
        }
        int readable = buf.readableBytes();
        if (readable == 0) {
            return;
        }

        Component c = new Component();
        c.buf = buf;
        c.compositeOffset = this.totalCapacity;
        c.length = readable;
        if (buf instanceof WrapArrayBuffer) {
            c.cachedArray = ((WrapArrayBuffer) buf).target;
            c.cachedArrayBase = buf.readerIndex();
        } else if (buf instanceof AutoArrayByteBuf) {
            c.cachedArray = ((AutoArrayByteBuf) buf).target;
            c.cachedArrayBase = buf.readerIndex();
        }

        this.components.add(c);
        this.totalCapacity += readable;
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
        if (c.cachedArray != null) {
            return c.cachedArray[c.cachedArrayBase + localOffset];
        }
        return c.buf.getByte(localOffset);
    }

    @Override
    public int expect(byte expected, int maxScanBytes) {
        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }
        if (this.components.size() == 1) {
            Component c = this.components.get(0);
            int start = this.readerIndex;
            byte[] directArray = directArray(c.buf);
            if (directArray != null) {
                int arrayOffset = c.buf.readerIndex() + start;
                for (int i = 0; i < scanLength; i++) {
                    if (directArray[arrayOffset + i] == expected) {
                        return i;
                    }
                }
                return -1;
            }
            int end = start + scanLength;
            for (int i = start; i < end; i++) {
                if (c.buf.getUInt8(i) == expected) {
                    return i - start;
                }
            }
            return -1;
        }

        byte[] scratch = ByteBuf.expectScratch(ByteBuf.DEFAULT_EXPECT_SCAN_SIZE);
        int scanned = 0;
        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length);
            this.getBytes(scanned, scratch, 0, copyLength);
            for (int i = 0; i < copyLength; i++) {
                if (scratch[i] == expected) {
                    return scanned + i;
                }
            }
            scanned += copyLength;
        }
        return -1;
    }

    @Override
    public int expectLast(byte expected, int maxScanBytes) {
        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }
        if (this.components.size() == 1) {
            Component c = this.components.get(0);
            int start = this.readerIndex;
            byte[] directArray = directArray(c.buf);
            if (directArray != null) {
                int arrayOffset = c.buf.readerIndex() + start;
                for (int i = scanLength - 1; i >= 0; i--) {
                    if (directArray[arrayOffset + i] == expected) {
                        return i;
                    }
                }
                return -1;
            }
            for (int i = start + scanLength - 1; i >= start; i--) {
                if (c.buf.getUInt8(i) == expected) {
                    return i - start;
                }
            }
            return -1;
        }

        byte[] scratch = ByteBuf.expectScratch(ByteBuf.DEFAULT_EXPECT_SCAN_SIZE);
        int scanned = 0;
        int lastMatch = -1;
        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length);
            this.getBytes(scanned, scratch, 0, copyLength);
            for (int i = 0; i < copyLength; i++) {
                if (scratch[i] == expected) {
                    lastMatch = scanned + i;
                }
            }
            scanned += copyLength;
        }
        return lastMatch;
    }

    @Override
    public ByteBuf readLineBuffer(int maxScanBytes) {
        int lineFeedIndex = this.expect((byte) '\n', maxScanBytes);
        if (lineFeedIndex < 0) {
            return null;
        }

        if (this.components.size() == 1) {
            Component c = this.components.get(0);
            boolean hasCarriageReturn = lineFeedIndex > 0 && c.buf.getUInt8(this.readerIndex + lineFeedIndex - 1) == '\r';
            int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
            ByteBuf line = lineLength <= 0 ? ByteBuf.EMPTY : ByteBufUtils.lineSlice(c.buf, this.readerIndex, lineLength);
            this.skipReadableBytes(lineLength + (hasCarriageReturn ? 2 : 1));
            return line;
        }

        boolean hasCarriageReturn = lineFeedIndex > 0 && this.getUInt8(lineFeedIndex - 1) == '\r';
        int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
        ByteBuf line = this.sliceLineView(lineLength);
        this.skipReadableBytes(lineLength + (hasCarriageReturn ? 2 : 1));
        return line;
    }

    private ByteBuf sliceLineView(int length) {
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }

        int startOffset = this.readerIndex;
        int endOffset = startOffset + length;
        CompositeByteBuf result = null;

        for (int i = 0; i < this.components.size(); i++) {
            Component c = this.components.get(i);
            int cStart = c.compositeOffset;
            int cEnd = c.compositeOffset + c.length;

            if (cEnd <= startOffset) {
                continue;
            }
            if (cStart >= endOffset) {
                break;
            }

            int overlapStart = Math.max(cStart, startOffset) - cStart;
            int overlapEnd = Math.min(cEnd, endOffset) - cStart;
            int overlapLen = overlapEnd - overlapStart;

            if (result == null && overlapLen == length) {
                return ByteBufUtils.lineSlice(c.buf, overlapStart, overlapLen);
            }
            if (result == null) {
                result = ByteBufUtils.compositeBuffer(alloc());
            }
            if (overlapStart == 0 && overlapLen == c.length) {
                result.addComponent(c.buf.retain());
            } else {
                result.addComponent(ByteBufUtils.lineSlice(c.buf, overlapStart, overlapLen));
            }
        }
        return result == null ? ByteBuf.EMPTY : result;
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
        int absoluteIdx = idx;

        // Inline findComponent cache hit path
        int ci = this.lastAccessedComponentIndex;
        Component c;
        if (ci < this.components.size()) {
            c = this.components.get(ci);
            if (absoluteIdx < c.compositeOffset || absoluteIdx >= c.compositeOffset + c.length) {
                // Try next component (common sequential read crossing boundary)
                int ni = ci + 1;
                if (ni < this.components.size()) {
                    c = this.components.get(ni);
                    if (absoluteIdx >= c.compositeOffset && absoluteIdx < c.compositeOffset + c.length) {
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

        int localOffset = absoluteIdx - c.compositeOffset;
        if (c.cachedArray != null) {
            return c.cachedArray[c.cachedArrayBase + localOffset];
        }
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

            if (c.cachedArray != null) {
                System.arraycopy(c.cachedArray, c.cachedArrayBase + localOffset, dst, currentDstOffset, toRead);
            } else {
                c.buf.getBytes(localOffset, dst, currentDstOffset, toRead);
            }

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

    /**
     * Marks the current reader position but defers queue consumption until {@link #markReader()}.
     * This keeps {@code markedReaderIndex} in sync for follow-up {@link #sliceOff(int)} calls
     * without repeatedly compacting the underlying queue during line-by-line parsing.
     */
    public QueueByteBuf markReaderDeferred() {
        super.markReader();
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

        if (this.components.size() == 1) {
            Component c = this.components.get(0);
            int discardOffset = this.readerIndex;
            if (discardOffset >= c.length) {
                this.queue.skipMessage(1);
                this.queuePeekCount--;
                this.components.clear();
                this.totalCapacity = 0;
            } else {
                c.buf.skipReadableBytes(discardOffset);
                c.cachedArrayBase += discardOffset;
                c.length -= discardOffset;
                c.compositeOffset = 0;
                this.totalCapacity = c.length;
            }

            this.lastAccessedComponentIndex = 0;
            this.markedReaderIndex = 0;
            this.readerIndex = 0;
            this.markedWriterIndex = this.totalCapacity;
            this.writerIndex = this.totalCapacity;
            return;
        }

        int discardOffset = this.readerIndex;
        int skipCount = 0;

        Iterator<Component> it = this.components.iterator();
        while (it.hasNext()) {
            Component c = it.next();
            if (c.length <= discardOffset) {
                discardOffset -= c.length;
                skipCount++;
                it.remove();
            } else {
                if (discardOffset > 0) {
                    c.buf.skipReadableBytes(discardOffset);
                    c.cachedArrayBase += discardOffset;
                    c.length -= discardOffset;
                }
                break;
            }
        }

        if (skipCount > 0) {
            this.queue.skipMessage(skipCount);
            this.queuePeekCount -= skipCount;
        }

        int currentOffset = 0;
        for (Component c : this.components) {
            c.compositeOffset = currentOffset;
            currentOffset += c.length;
        }

        this.totalCapacity = currentOffset;
        this.lastAccessedComponentIndex = 0;
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
    * the front portion are added via {@link CompositeByteBuf#addComponent(ByteBuf)} after an
    * explicit retain for zero-copy sharing. Boundary components that are only partially in the
    * front portion are copied.
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

        int splitReaderIndex = this.markedReaderIndex + splitOffset;
        if (splitReaderIndex > this.markedWriterIndex) {
            splitReaderIndex = this.markedWriterIndex;
        }

        int frontLen = splitReaderIndex - this.markedReaderIndex;
        if (frontLen <= 0) {
            return ByteBuf.EMPTY;
        }

        if (this.components.size() == 1) {
            Component c = this.components.get(0);
            int localStart = this.markedReaderIndex - c.compositeOffset;
            ByteBuf result = localStart == 0 && frontLen == c.length ? c.buf.retain() : ByteBufUtils.lineSlice(c.buf, localStart, frontLen);

            this.readerIndex = splitReaderIndex;
            this.markedReaderIndex = splitReaderIndex;
            discardReadBytes();
            return result;
        }

        ByteBuf result = null;
        CompositeByteBuf composite = null;
        int startOffset = this.markedReaderIndex;
        int endOffset = splitReaderIndex;

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

            ByteBuf overlap = overlapStart == 0 && overlapLen == c.length ? c.buf.retain() : ByteBufUtils.lineSlice(c.buf, overlapStart, overlapLen);

            if (result == null && overlapLen == frontLen) {
                result = overlap;
                break;
            }
            if (composite == null) {
                composite = ByteBufUtils.compositeBuffer(alloc());
                result = composite;
            }
            composite.addComponent(overlap);
        }

        // Advance readerIndex past the sliced-off portion and discard
        this.readerIndex = splitReaderIndex;
        this.markedReaderIndex = splitReaderIndex;
        discardReadBytes();

        return result == null ? ByteBuf.EMPTY : result;
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

    private static byte[] directArray(ByteBuf buf) {
        if (buf instanceof WrapArrayBuffer) {
            return ((WrapArrayBuffer) buf).target;
        }
        if (buf instanceof AutoArrayByteBuf) {
            return ((AutoArrayByteBuf) buf).target;
        }
        return null;
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
        byte[]  cachedArray;
        int     cachedArrayBase;
    }
}
