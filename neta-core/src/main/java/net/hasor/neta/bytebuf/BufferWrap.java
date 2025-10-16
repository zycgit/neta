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
    static  RecycleHandler<BufferWrap> RECYCLE_HANDLER = new RecycleHandler<BufferWrap>() {
        public BufferWrap create() {
            return new BufferWrap();
        }

        @Override
        public void free(BufferWrap tar) {
            RecycleObjectPool.free(BufferWrap.class, tar);
        }
    };
    private ByteBuffer                 buffer;
    private boolean                    available;

    // ------------------------------------------------------------------------

    private BufferWrap() {
    }

    private static ByteBuffer clearAndPosition(ByteBuffer buffer, int index) {
        if (index == 0) {
            buffer.clear();
        } else {
            buffer.clear().position(index);
        }
        return buffer;
    }

    void initBuffer(ByteBuffer buffer) {
        this.buffer = buffer;
        this.available = true;
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Tells whether or not this byte buffer is direct.
     * @return <tt>true</tt> if, and only if, this buffer is direct
     */
    @Override
    public boolean isDirect() {
        return this.buffer.isDirect();
    }

    @Override
    public int capacity() {
        return this.buffer.capacity();
    }

    @Override
    public ByteBuffer getTarget() {
        return this.buffer;
    }

    @Override
    public int getOffset() {
        return 0;
    }

    @Override
    public byte get(int index) {
        return this.buffer.get(index);
    }

    @Override
    public void put(int index, byte b) {
        this.buffer.put(index, b);
    }

    @Override
    public void get(int index, byte[] dst, int dstOffset, int dstLen) {
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.get(dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, byte[] src, int srcOffset, int srcLen) {
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.put(src, srcOffset, srcLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstLen) {
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.limit(index + dstLen);
        dst.put(dupBuf);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstOffset, int dstLen) {
        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);
        dupBuf.limit(index + dstLen);
        ByteBuffer dup = (ByteBuffer) dst.duplicate().position(dstOffset);
        dup.put(dupBuf);

        int newPos = dstOffset + dstLen;
        if (newPos > dst.position()) {
            dst.position(newPos);
        }
    }

    @Override
    public void put(int index, ByteBuffer src, int srcLen) {
        int newPos = src.position() + srcLen;
        if (newPos > src.limit()) {
            throw new IllegalArgumentException("(src.position + srcLen) > limit: (" + newPos + " > " + src.limit() + ")");
        }

        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);

        dupBuf.put((ByteBuffer) src.duplicate().limit(newPos));
        src.position(newPos);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcOffset, int srcLen) {
        int newPos = srcOffset + srcLen;
        if (newPos > src.limit()) {
            throw new IllegalArgumentException("(srcOffset + srcLen) > limit: (" + newPos + " > " + src.limit() + ")");
        }

        ByteBuffer dupBuf = this.buffer.duplicate();
        clearAndPosition(dupBuf, index);

        int limit = srcOffset + srcLen;
        ByteBuffer dupSrc = clearAndPosition(src.duplicate(), srcOffset);
        dupSrc.limit(limit);
        dupBuf.put(dupSrc);

        if (limit > src.position()) {
            src.position(limit);
        }
    }

    @Override
    public void free() {
        this.available = false;
        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.buffer);
        }
        this.buffer = null;
        RECYCLE_HANDLER.free(this);
    }
}