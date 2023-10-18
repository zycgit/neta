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
package net.hasor.cobble.net.bytebuf;
import java.nio.ByteBuffer;

/**
 * ByteBuffer 的一个包装类。
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
class NioChunk {
    private final ByteBuffer byteBuffer;

    NioChunk(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
    }

    byte get(int index) {
        return this.byteBuffer.get(index);
    }

    void put(int index, byte b) {
        this.byteBuffer.put(index, b);
    }

    void get(byte[] dst, int offset, int length) {
        this.byteBuffer.get(dst, offset, length);
    }

    void put(byte[] src, int offset, int length) {
        this.byteBuffer.put(src, offset, length);
    }

    void put(ByteBuffer buffer) {
        this.byteBuffer.put(buffer);
    }

    void clearPosition(int position) {
        this.byteBuffer.clear().position(position);
    }

    void clearMaxLimit() {
        this.byteBuffer.clear().limit(this.byteBuffer.capacity());
    }

    void clearLimit(int limit) {
        this.byteBuffer.clear().limit(limit);
    }

    void position(int position) {
        this.byteBuffer.position(position);
    }

    int capacity() {
        return this.byteBuffer.capacity();
    }

    boolean isDirect() {
        return this.byteBuffer.isDirect();
    }

    ByteBuffer byteBuffer() {
        return this.byteBuffer;
    }

    synchronized void deepCopy(NioChunk dst) {
        this.clearMaxLimit();
        dst.clearPosition(0);
        dst.put(this.byteBuffer);
    }

    void freeBuffer() {
        if (ByteBufUtil.CLEANER != null) {
            ByteBufUtil.CLEANER.freeDirectBuffer(this.byteBuffer);
        }
    }
}
