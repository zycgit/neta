/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;

/**
 * The {@link ByteBuffer} is convert to a {@link Buffer} interface
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferWrap implements Buffer {
    static final int                        RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleHandler<BufferWrap> RECYCLE_HANDLER = new RecycleHandler<BufferWrap>() {
        public BufferWrap create() {
            return new BufferWrap();
        }

        @Override
        public void free(BufferWrap tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };
    private      ByteBuffer                 buffer;
    private      byte[]                     heapArray;   // direct heap array (avoids ByteBuffer.wrap)
    private      boolean                    available;
    private      boolean                    fromSmallCache;

    // ------------------------------------------------------------------------

    private BufferWrap() {
    }

    private static ByteBuffer clearAndPosition(ByteBuffer buffer, int index) {
        if (index == 0) {
            ((java.nio.Buffer) buffer).clear();
        } else {
            ((java.nio.Buffer) buffer).clear();
            ((java.nio.Buffer) buffer).position(index);
        }
        return buffer;
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
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.get(dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, byte[] src, int srcOffset, int srcLen) {
        byte[] ha = this.heapArray;
        if (ha != null) {
            System.arraycopy(src, srcOffset, ha, index, srcLen);
            return;
        }
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.put(src, srcOffset, srcLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstLen) {
        ByteBuffer dupBuf = ensureBuffer().duplicate();
        clearAndPosition(dupBuf, index);
        ((java.nio.Buffer) dupBuf).limit(index + dstLen);
        dst.put(dupBuf);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstOffset, int dstLen) {
        ByteBuffer dupBuf = ensureBuffer().duplicate();
        clearAndPosition(dupBuf, index);
        ((java.nio.Buffer) dupBuf).limit(index + dstLen);
        ByteBuffer dup = dst.duplicate();
        ((java.nio.Buffer) dup).position(dstOffset);
        dup.put(dupBuf);

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

        ByteBuffer dupBuf = ensureBuffer().duplicate();
        clearAndPosition(dupBuf, index);

        ByteBuffer srcDup = src.duplicate();
        ((java.nio.Buffer) srcDup).limit(newPos);
        dupBuf.put(srcDup);
        ((java.nio.Buffer) src).position(newPos);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcOffset, int srcLen) {
        int newPos = srcOffset + srcLen;
        if (newPos > src.limit()) {
            throw new IllegalArgumentException("(srcOffset + srcLen) > limit: (" + newPos + " > " + src.limit() + ")");
        }

        ByteBuffer dupBuf = ensureBuffer().duplicate();
        clearAndPosition(dupBuf, index);

        int limit = srcOffset + srcLen;
        ByteBuffer dupSrc = clearAndPosition(src.duplicate(), srcOffset);
        ((java.nio.Buffer) dupSrc).limit(limit);
        dupBuf.put(dupSrc);

        if (limit > src.position()) {
            ((java.nio.Buffer) src).position(limit);
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
        this.available = false;
        ByteBuffer buf = this.buffer;
        byte[] ha = this.heapArray;
        boolean wasSmallCache = this.fromSmallCache;
        this.buffer = null;
        this.heapArray = null;
        this.fromSmallCache = false;

        if (ha != null && wasSmallCache) {
            // Small heap buffer stored directly as byte[] — return to cache
            SmallBufferCache.freeHeap(ha);
        } else if (buf != null) {
            if (buf.isDirect()) {
                // always attempt small cache return first; freeDirect validates size class internally
                if (!SmallBufferCache.freeDirect(buf) && ByteBufUtils.CLEANER != null) {
                    ByteBufUtils.CLEANER.freeDirectBuffer(buf);
                }
            } else if (wasSmallCache && buf.hasArray()) {
                SmallBufferCache.freeHeap(buf.array());
            }
        }

        RECYCLE_HANDLER.free(this);
    }
}