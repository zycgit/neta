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
public class BufferWrap implements Buffer {
    private final ByteBuffer buffer;
    public        boolean    available;

    public BufferWrap(ByteBuffer buffer) {
        this.buffer = buffer;
        this.available = true;
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Tells whether or not this byte buffer is direct.
     * @return  <tt>true</tt> if, and only if, this buffer is direct
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
    public byte get(int index) {
        return this.buffer.get(index);
    }

    @Override
    public void put(int index, byte b) {
        this.buffer.put(index, b);
    }

    @Override
    public void get(int index, byte[] dst, int dstOffset, int dstLen) {
        this.buffer.clear().position(index);
        this.buffer.get(dst, dstOffset, dstLen);
    }

    @Override
    public void put(int index, byte[] src, int srcOffset, int srcLen) {
        this.buffer.clear().position(index);
        this.buffer.put(src, srcOffset, srcLen);
    }

    @Override
    public void get(int index, ByteBuffer dst, int dstOffset, int dstLen) {
        this.buffer.clear().limit(index + dstLen).position(index);
        ByteBuffer dup = (ByteBuffer) dst.duplicate().position(dstOffset);
        dup.put(this.buffer);
    }

    @Override
    public void put(int index, ByteBuffer src, int srcOffset, int srcLen) {
        this.buffer.clear().position(index);
        this.buffer.put((ByteBuffer) src.duplicate().limit(srcOffset + srcLen).position(srcOffset));
    }

    @Override
    public void free() {
        this.available = false;
        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.buffer);
        }
    }
}