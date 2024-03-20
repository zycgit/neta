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

/**
 * <pre>
 * +-------------+------------+-------------+----------+
 * | discardable | readable   | overlayable | writable |
 * +-------------+------------+-------------+----------+
 * |             |            |             |          |
 * 0   ≤   readerIndex  ≤  marked  ≤  writerIndex ≤ capacity
 *                      writerIndex
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class WrapArrayBuffer extends AbstractByteBuf {
    protected final byte[] data;

    WrapArrayBuffer(byte[] initData, boolean asWrite) {
        super(null, initData.length);
        this.data = initData;
        if (!asWrite) {
            this.writerIndex = initData.length;
            this.markedWriterIndex = initData.length;
        }
    }

    @Override
    public ByteBuf markReader() {
        synchronized (this.synchronizedLock) {
            // notify all writer threads, to write it
            this.synchronizedLock.notifyAll();
        }
        return this;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        this.data[offset] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        System.arraycopy(src, srcOffset, this.data, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        src.get(this.data, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        src.readBytes(this.data, offset, srcLen);
        return srcLen;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        return this.data[offset];
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        System.arraycopy(this.data, offset, dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        dst.put(this.data, offset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        dst.writeBytes(this.data, offset, dstLen);
        return dstLen;
    }

    @Override
    protected void _free() {

    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public byte[] asByteArray() {
        return this.data;
    }

    @Override
    public WrapArrayBuffer copy() {
        checkFree();

        byte[] copyArray = this.data.clone();
        WrapArrayBuffer byteBuf = new WrapArrayBuffer(copyArray, true);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "WrapArrayBuffer";
    }
}