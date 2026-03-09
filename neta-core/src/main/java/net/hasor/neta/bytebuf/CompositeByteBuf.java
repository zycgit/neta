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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A virtual {@link ByteBuf} that combines multiple underlying {@link ByteBuf} instances
 * into a single contiguous view, similar to Netty's CompositeByteBuf.
 * <p>
 * This provides zero-copy aggregation of multiple buffers. The composite buffer
 * presents all component buffers as a single readable buffer. Data can be read
 * seamlessly across component boundaries.
 *
 * <pre>
 * logical view
 *
 *   readerIndex -----------------------------------------------> writerIndex
 *   |                                                           |
 *   v                                                           v
 *   +------------------- composite readable address space --------------------+
 *   | component[0] | component[1] | component[2] | ... | component[n]        |
 *   +-----------------------------------------------------------------------+
 *   0            c0.end          c1.end         c2.end                    totalCapacity
 *
 * physical ownership
 *
 *   components list
 *     -> { buf=buf0, compositeOffset=0,      length=buf0.readableBytes() }
 *     -> { buf=buf1, compositeOffset=c0.end, length=buf1.readableBytes() }
 *     -> { buf=buf2, compositeOffset=c1.end, length=buf2.readableBytes() }
 * </pre>
 * <p>
 * Components are added via {@link #addComponent(ByteBuf)}, which retains the buffer
 * and makes its readable data immediately available in the composite view.
 * <p>
 * <b>Read operations</b> (read*, get*) are fully supported across component boundaries.
 * <br>
 * <b>Write operations</b> (write*, set*) are not supported — use {@link #addComponent(ByteBuf)}
 * to append data instead.
 * <p>
 * Example:
 * <pre>{@code
 * CompositeByteBuf composite = ByteBufUtils.compositeBuffer(allocator);
 * composite.addComponent(buf1);
 * composite.addComponent(buf2);
 * // Read across buf1 and buf2 boundaries seamlessly
 * int value = composite.readInt32();
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-16
 */
public class CompositeByteBuf extends AbstractByteBuf {
    private final List<Component> components;
    private       int             totalCapacity;
    /** Cache the last accessed component index for sequential read optimization. */
    private       int             lastAccessedComponentIndex;

    /**
     * Create a new empty CompositeByteBuf.
     * @param alloc the allocator to use when creating copies
     */
    public CompositeByteBuf(ByteBufAllocator alloc) {
        this.components = new ArrayList<>();
        this.totalCapacity = 0;
        this.initByteBuf(alloc, Integer.MAX_VALUE);
        // start with writerIndex=0, markedWriterIndex=0, readerIndex=0
        this.writerIndex = 0;
        this.markedWriterIndex = 0;
    }

    /**
     * Appends a {@link ByteBuf} as a new component. The buffer's current readable data
     * becomes part of this composite's readable data. The buffer is retained.
     * <p>
     * If the buffer has no readable data, it is ignored.
     * @param buf the buffer to add (its readable bytes become composite content)
     * @return this CompositeByteBuf for chaining
     */
    public CompositeByteBuf addComponent(ByteBuf buf) {
        checkFree();
        if (buf == null || buf.readableBytes() == 0) {
            return this;
        }

        buf.retain();

        Component c = new Component();
        c.buf = buf;
        c.compositeOffset = this.totalCapacity;
        c.length = buf.readableBytes();

        // Cache underlying byte array for direct access (skip virtual dispatch per byte)
        if (buf instanceof WrapArrayBuffer) {
            c.cachedArray = ((WrapArrayBuffer) buf).target;
            c.cachedArrayBase = buf.readerIndex();
        } else if (buf instanceof AutoArrayByteBuf) {
            c.cachedArray = ((AutoArrayByteBuf) buf).target;
            c.cachedArrayBase = buf.readerIndex();
        }

        this.components.add(c);
        this.totalCapacity += c.length;

        // Make the new data immediately readable
        this.writerIndex = this.totalCapacity;
        this.markedWriterIndex = this.totalCapacity;
        return this;
    }

    /**
     * Appends multiple {@link ByteBuf} instances as new components.
     * @param buffers the buffers to add
     * @return this CompositeByteBuf for chaining
     */
    public CompositeByteBuf addComponents(ByteBuf... buffers) {
        if (buffers != null) {
            for (ByteBuf buf : buffers) {
                addComponent(buf);
            }
        }
        return this;
    }

    /** Returns the number of components in this composite buffer. */
    public int numComponents() {
        return this.components.size();
    }

    /**
     * Returns an unmodifiable view of the component buffers.
     * <p>Note: The returned list reflects the current state; components may change
     * after {@link #discardReadBytes()} or {@link #addComponent(ByteBuf)}.
     */
    public List<ByteBuf> decompose() {
        List<ByteBuf> result = new ArrayList<>(this.components.size());
        for (Component c : this.components) {
            result.add(c.buf);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public int capacity() {
        return this.totalCapacity;
    }

    // ---- Capacity ----

    @Override
    public boolean isDirect() {
        // Composite is mixed; report false (heap-like)
        return false;
    }

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

    // ---- Component Lookup ----

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

    /**
     * Returns the component index containing the given absolute offset.
     */
    private int findComponentIndex(int offset) {
        // Use cache-aware lookup
        int cachedIdx = this.lastAccessedComponentIndex;
        if (cachedIdx < this.components.size()) {
            Component cached = this.components.get(cachedIdx);
            if (offset >= cached.compositeOffset && offset < cached.compositeOffset + cached.length) {
                return cachedIdx;
            }
        }
        int idx = binarySearchComponentIndex(offset);
        this.lastAccessedComponentIndex = idx;
        return idx;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();
        Component c = findComponent(offset);
        int localOffset = offset - c.compositeOffset;
        byte[] arr = c.cachedArray;
        if (arr != null) {
            return arr[c.cachedArrayBase + localOffset];
        }
        return c.buf.getByte(localOffset);
    }

    /**
     * Optimized readByte for sequential reads.
     * Bypasses AbstractByteBuf.readByte() → nextReadable() → _getByte() chain.
     * Uses cached component array for direct O(1) access without virtual dispatch.
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
        byte[] arr = c.cachedArray;
        if (arr != null) {
            return arr[c.cachedArrayBase + localOffset];
        }
        return c.buf.getByte(localOffset);
    }

    // ---- Read (Get) Operations ----

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

    @Override
    protected void _putByte(int offset, byte b) {
        throw new UnsupportedOperationException("CompositeByteBuf does not support direct writes. Use addComponent() instead.");
    }

    // ---- Write (Put) Operations - Not Supported ----

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        throw new UnsupportedOperationException("CompositeByteBuf does not support direct writes. Use addComponent() instead.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        throw new UnsupportedOperationException("CompositeByteBuf does not support direct writes. Use addComponent() instead.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        throw new UnsupportedOperationException("CompositeByteBuf does not support direct writes. Use addComponent() instead.");
    }

    /** Override to report 0 writable bytes — use {@link #addComponent(ByteBuf)} to add data. */
    @Override
    public int writableBytes() {
        return 0;
    }

    /** Override to report 0 written bytes — data is provided through components. */
    @Override
    public int writtenBytes() {
        return 0;
    }

    @Override
    protected void _free() {
        for (Component c : this.components) {
            c.buf.release();
        }
        this.components.clear();
        this.totalCapacity = 0;
        this.lastAccessedComponentIndex = 0;
    }

    // ---- Lifecycle ----

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

    // ---- Copy ----

    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }

        int discardOffset = this.readerIndex;

        // Remove fully consumed components
        Iterator<Component> it = this.components.iterator();
        while (it.hasNext()) {
            Component c = it.next();
            int endOffset = c.compositeOffset + c.length;
            if (endOffset <= discardOffset) {
                // Fully consumed — release
                c.buf.release();
                it.remove();
            } else if (c.compositeOffset < discardOffset) {
                // Partially consumed — advance component's reader
                int consumed = discardOffset - c.compositeOffset;
                c.buf.skipReadableBytes(consumed);
                c.length -= consumed;
                // Update cached array base to reflect new reader position
                if (c.cachedArray != null) {
                    c.cachedArrayBase += consumed;
                }
                break;
            } else {
                break;
            }
        }

        // Recompute composite offsets
        int offset = 0;
        for (Component c : this.components) {
            c.compositeOffset = offset;
            offset += c.length;
        }
        this.totalCapacity = offset;

        // Reset component cache (components may have been removed)
        this.lastAccessedComponentIndex = 0;

        // Adjust indices
        this.markedReaderIndex = 0;
        this.readerIndex = 0;
        this.markedWriterIndex = this.totalCapacity;
        this.writerIndex = this.totalCapacity;
    }

    // ---- Discard & Slice ----

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

        // Copy front data into a new ByteBuf
        int frontLen = absoluteSplit - this.markedReaderIndex;
        if (frontLen <= 0) {
            return ByteBuf.EMPTY;
        }

        byte[] frontData = new byte[frontLen];
        _getBytes(this.markedReaderIndex, frontData, 0, frontLen);

        // Advance readerIndex past the sliced-off portion and discard
        this.readerIndex = absoluteSplit;
        this.markedReaderIndex = absoluteSplit;
        discardReadBytes();

        return ByteBuf.wrap(frontData);
    }

    @Override
    protected String getSimpleName() {
        return "CompositeByteBuf";
    }

    /** Component entry tracking a ByteBuf and its offset within the composite. */
    private static class Component {
        ByteBuf buf;
        int     compositeOffset; // start offset within the composite
        int     length;          // number of bytes contributed by this component
        // Cached for direct byte-level access (null for non-array-backed buffers)
        byte[]  cachedArray;
        int     cachedArrayBase; // base index into cachedArray for this component's data
    }
}
