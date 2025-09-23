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
 * 基于 {@link ByteBuffer} 的自动扩缩容 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class AutoByteBuffer extends AbstractByteBuf {
    static    RecycleHandler<AutoByteBuffer> RECYCLE_HANDLER = new RecycleHandler<AutoByteBuffer>() {
        public AutoByteBuffer create() {
            return new AutoByteBuffer();
        }

        @Override
        public void free(AutoByteBuffer tar) {
            RecycleObjectPool.free(AutoByteBuffer.class, tar);
        }
    };
    protected ByteBuffer                     target;
    private   int                            extensionSize;

    // ------------------------------------------------------------------------

    private AutoByteBuffer() {
    }

    void initBuffer(ByteBufAllocator alloc, int maxCapacity, int extensionSize, ByteBuffer initData) {
        super.initByteBuf(alloc, maxCapacity);
        this.extensionSize = extensionSize;
        this.target = initData;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.recycle();
        }
        return this;
    }

    private void recycle() {
        int requestSize = this.writerIndex - this.markedReaderIndex;
        ByteBuffer recycle = this.alloc.jvmBuffer(evalSize(requestSize));
        this.target.clear().position(this.markedReaderIndex).limit(this.markedReaderIndex + requestSize);
        recycle.put(this.target);

        int recyclePos = this.markedReaderIndex;
        this.target = recycle;
        this.writerIndex = this.writerIndex - recyclePos;
        this.markedWriterIndex = this.markedWriterIndex - recyclePos;
        this.readerIndex = this.readerIndex - recyclePos;
        this.markedReaderIndex = 0;
    }

    private int evalSize(int requestSize) {
        int maxCap = this.getMaxCapacity();
        int newSize;
        if ((requestSize % this.extensionSize) > 0) {
            int rate = (requestSize / this.extensionSize) + 1;
            newSize = Math.min(rate * this.extensionSize, maxCap);
        } else {
            newSize = Math.min(requestSize + this.extensionSize, maxCap);
        }
        return Math.min(newSize, maxCap);
    }

    private void checkExtension(int offset, int len) {
        int currentCap = this.capacity();
        int requestSize = offset + len;
        if (requestSize > currentCap) {
            ByteBuffer extension = this.alloc.jvmBuffer(evalSize(requestSize));
            extension.put((ByteBuffer) this.target.clear());
            this.target = extension;
        }
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
            RECYCLE_HANDLER.free(this);
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
    public AutoByteBuffer copy() {
        checkFree();

        ByteBuffer copyBuffer = this.alloc.jvmBuffer(this.target.capacity());
        this.target.clear();
        copyBuffer.put(this.target);

        AutoByteBuffer byteBuf = RecycleObjectPool.get(AutoByteBuffer.class, AutoByteBuffer.RECYCLE_HANDLER);
        byteBuf.initBuffer(this.alloc, this.getMaxCapacity(), this.extensionSize, copyBuffer);
        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "AutoByteBuffer";
    }
}