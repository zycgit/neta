/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import net.hasor.cobble.ref.RecycleObjectPool;

/**
 * Unpooled {@link Buffer} implementation that wraps an existing heap array or
 * {@link ByteBuffer}.
 * <p>Unlike {@link BufferTarget}, this type does not own pooled pages. It is
 * used by heap and direct buffers created outside the page allocator, including
 * small cached temporary buffers.
 * <p>Calling {@link #free()} invalidates the wrapper. For regular heap buffers
 * this only drops references; for small cached buffers it returns the storage to
 * the lightweight cache so it can be reused by later allocations.
 * <p>When the wrapped storage is heap-backed, {@link Buffer#heapArray()} exposes
 * the raw byte array as a fast path for bulk access.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see BufferTarget
 * @see BufferCleaner
 */
class BufferWrap implements Buffer {
    static final RecycleObjectPool<BufferWrap> RECYCLER = new RecycleObjectPool<>(//
            BufferWrap::new, BufferWrap::resetState, BufferWrap::onRecycle);
    private      ByteBuffer                    buffer;
    private      byte[]                        heapArray;   // direct heap array (avoids ByteBuffer.wrap)
    private      boolean                       available;
    private      boolean                       fromSmallCache;

    // ------------------------------------------------------------------------

    private BufferWrap() {
    }

    private void resetState() {
        this.buffer = null;
        this.heapArray = null;
        this.available = false;
        this.fromSmallCache = false;
    }

    private void onRecycle() {
        ByteBuffer buf = this.buffer;
        byte[] ha = this.heapArray;
        boolean wasSmallCache = this.fromSmallCache;
        this.resetState();

        if (ha != null && wasSmallCache) {
            SmallBufferCache.freeHeap(ha);
        } else if (buf != null) {
            if (buf.isDirect()) {
                if (!SmallBufferCache.freeDirect(buf) && ByteBufUtils.CLEANER != null) {
                    ByteBufUtils.CLEANER.freeDirectBuffer(buf);
                }
            } else if (wasSmallCache && buf.hasArray()) {
                SmallBufferCache.freeHeap(buf.array());
            }
        }
    }

    void initBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
        this.heapArray = null;
        this.available = true;
        this.fromSmallCache = false;
    }

    void initSmallBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
        this.heapArray = null;
        this.available = true;
        this.fromSmallCache = true;
    }

    /** Initialize with a direct heap byte array, avoiding ByteBuffer.wrap() allocation. */
    void initSmallHeapBuffer(byte[] array) {
        this.buffer = null;
        this.heapArray = array;
        this.available = true;
        this.fromSmallCache = true;
    }

    private ByteBuffer ensureBuffer() {
        ByteBuffer b = this.buffer;
        if (b == null && this.heapArray != null) {
            b = ByteBuffer.wrap(this.heapArray);
            this.buffer = b;
        }
        return b;
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    @Override
    public boolean isDirect() {
        if (this.heapArray != null) {
            return false;
        }
        return this.buffer.isDirect();
    }

    @Override
    public int capacity() {
        byte[] ha = this.heapArray;
        if (ha != null) {
            return ha.length;
        }
        return this.buffer.capacity();
    }

    @Override
    public ByteBuffer getTarget() {
        return ensureBuffer();
    }

    @Override
    public int getOffset() {
        return 0;
    }

    @Override
    public byte get(int index) {
        byte[] ha = this.heapArray;
        if (ha != null) {
            return ha[index];
        }
        return this.buffer.get(index);
    }

    @Override
    public void put(int index, byte b) {
        byte[] ha = this.heapArray;
        if (ha != null) {
            ha[index] = b;
            return;
        }
        this.buffer.put(index, b);
    }

    @Override
    public void get(int index, byte[] dst, int dstOffset, int dstLen) {
        byte[] ha = this.heapArray;
        if (ha != null) {
            System.arraycopy(ha, index, dst, dstOffset, dstLen);
            return;
        }
        this.buffer.get(index, dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, byte[] src, int srcOffset, int srcLen) {
        byte[] ha = this.heapArray;
        if (ha != null) {
            System.arraycopy(src, srcOffset, ha, index, srcLen);
            return;
        }
        this.buffer.put(index, src, srcOffset, srcLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstLen) {
        int dstPosition = dst.position();
        dst.put(dstPosition, ensureBuffer(), index, dstLen);
        ((java.nio.Buffer) dst).position(dstPosition + dstLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstOffset, int dstLen) {
        dst.put(dstOffset, ensureBuffer(), index, dstLen);

        int newPos = dstOffset + dstLen;
        if (newPos > dst.position()) {
            ((java.nio.Buffer) dst).position(newPos);
        }
    }

    @Override
    public void put(int index, ByteBuffer src, int srcLen) {
        int newPos = src.position() + srcLen;
        if (newPos > src.limit()) {
            throw new IllegalArgumentException("(src.position + srcLen) > limit: (" + newPos + " > " + src.limit() + ")");
        }

        ensureBuffer().put(index, src, src.position(), srcLen);
        ((java.nio.Buffer) src).position(newPos);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcOffset, int srcLen) {
        int newPos = srcOffset + srcLen;
        if (newPos > src.limit()) {
            throw new IllegalArgumentException("(srcOffset + srcLen) > limit: (" + newPos + " > " + src.limit() + ")");
        }

        ensureBuffer().put(index, src, srcOffset, srcLen);

        if (newPos > src.position()) {
            ((java.nio.Buffer) src).position(newPos);
        }
    }

    @Override
    public byte[] heapArray() {
        byte[] ha = this.heapArray;
        if (ha != null) {
            return ha;
        }
        ByteBuffer bb = this.buffer;
        return (bb != null && !bb.isDirect() && bb.hasArray()) ? bb.array() : null;
    }

    @Override
    public int heapArrayOffset() {
        if (this.heapArray != null) {
            return 0;
        }
        ByteBuffer bb = this.buffer;
        return (bb != null && bb.hasArray()) ? bb.arrayOffset() : 0;
    }

    @Override
    public void free() {
        RECYCLER.recycle(this);
    }
}
