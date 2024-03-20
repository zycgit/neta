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
import net.hasor.cobble.NumberUtils;

import java.nio.ByteBuffer;

/**
 * 基于 {@link ByteBuffer} 的自动扩缩容 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ElasticByteBuffer extends AbstractByteBuf {
    protected ByteBuffer target;
    protected int        initSize;
    protected int        extensionSize;

    ElasticByteBuffer(ByteBufAllocator alloc, int initCapacity, int maxCapacity, int extensionSize) {
        this(alloc, initCapacity, maxCapacity, extensionSize, alloc.jvmBuffer(initCapacity));
    }

    ElasticByteBuffer(ByteBufAllocator alloc, int initCapacity, int maxCapacity, int extensionSize, ByteBuffer initData) {
        super(alloc, maxCapacity);
        this.initSize = initCapacity;
        this.extensionSize = extensionSize;
        this.target = initData;
    }

    @Override
    public ByteBuf markReader() {
        synchronized (this.synchronizedLock) {
            if (this.markedReaderIndex != this.readerIndex) {
                this.markedReaderIndex = this.readerIndex;
                this.recycle();
            }

            // notify all writer threads, to write it
            this.synchronizedLock.notifyAll();
        }
        return this;
    }

    private void checkExtension(int offset, int len) {
        int cap = capacity();
        if ((offset + len) > cap) {
            int rate = (len / this.extensionSize) + 1;
            int newSize = cap + (rate * this.extensionSize);
            newSize = NumberUtils.between(newSize, this.extensionSize, this.getMaxCapacity());

            ByteBuffer extension = this.alloc.jvmBuffer(newSize);
            extension.put((ByteBuffer) this.target.clear());
            this.target = extension;
        }
    }

    private void recycle() {
        int recyclePos = this.markedReaderIndex;
        int validSize = this.target.capacity() - recyclePos;
        int newSize = NumberUtils.between(validSize, this.extensionSize, this.getMaxCapacity());

        ByteBuffer recycle = this.alloc.jvmBuffer(newSize);
        this.target.clear().position(this.markedReaderIndex);
        recycle.put(this.target);

        this.target = recycle;
        this.writerIndex = this.writerIndex - recyclePos;
        this.markedWriterIndex = this.markedWriterIndex - recyclePos;
        this.readerIndex = this.readerIndex - recyclePos;
        this.markedReaderIndex = 0;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();
        checkExtension(offset, 1);

        this.target.clear();
        this.target.put(offset, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();
        checkExtension(offset, srcLen);

        this.target.clear().position(offset);
        this.target.put(src, srcOffset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        checkExtension(offset, srcLen);

        this.target.clear().position(offset);
        this.target.put((ByteBuffer) src.duplicate().limit(src.position() + srcLen));
        src.position(src.position() + srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        checkExtension(offset, srcLen);

        this.target.clear().position(offset);
        src.readBuffer(this.target, srcLen);
        return srcLen;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        this.target.clear();
        return this.target.get(offset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        this.target.clear().position(offset);
        this.target.get(dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        this.target.clear().position(offset).limit(offset + dstLen);
        dst.put(this.target);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        this.target.clear().position(offset);
        dst.writeBuffer(this.target, dstLen);
        return dstLen;
    }

    @Override
    protected void _free() {
        try {
            if (ByteBufUtils.CLEANER != null) {
                ByteBufUtils.CLEANER.freeDirectBuffer(this.target);
            }
        } finally {
            this.target = null;
        }
    }

    @Override
    public int capacity() {
        return this.target.capacity();
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public ElasticByteBuffer copy() {
        checkFree();

        ByteBuffer copyBuffer = this.alloc.jvmBuffer(this.target.capacity());
        this.target.clear();
        copyBuffer.put(this.target);
        ElasticByteBuffer byteBuf = new ElasticByteBuffer(this.alloc, this.initSize, this.getMaxCapacity(), this.extensionSize, copyBuffer);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "ElasticByteBuffer";
    }
}