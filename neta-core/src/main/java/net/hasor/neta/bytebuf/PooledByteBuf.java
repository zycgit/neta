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
 * 基于 {@link Buffer} 池化的的窗口 {@link ByteBuf} 实现，同时如果容量不足它会自动扩缩容
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PooledByteBuf extends AbstractByteBuf {
    protected     Buffer     target;
    private final BufferPool pool;
    private final int        initSize;
    private final int        extensionSize;

    PooledByteBuf(ByteBufAllocator alloc, int maxCapacity, int extensionSize, Buffer target, BufferPool pool) {
        super(alloc, maxCapacity);
        this.target = target;
        this.pool = pool;
        this.initSize = target.capacity();
        this.extensionSize = extensionSize;
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

    private void recycle() {
        Buffer toFreeTarget = null;
        try {
            int requestSize = this.writerIndex - this.markedReaderIndex;
            Buffer extTarget = this.pool.requestBuffer(evalSize(requestSize), this.alloc);
            toFreeTarget = extTarget;// when try failed, free requestBuffer.

            if (requestSize > 0) {
                ByteBuffer targetBuf = extTarget.getTarget().duplicate();
                targetBuf.clear().position(extTarget.getOffset());
                this.target.get(this.markedReaderIndex, targetBuf, requestSize);
            }
            toFreeTarget = this.target;
            this.target = extTarget;

            int recyclePos = this.markedReaderIndex;
            this.writerIndex = this.writerIndex - recyclePos;
            this.markedWriterIndex = this.markedWriterIndex - recyclePos;
            this.readerIndex = this.readerIndex - recyclePos;
            this.markedReaderIndex = 0;
        } finally {
            if (toFreeTarget != null) {
                toFreeTarget.free();
            }
        }

    }

    private int evalSize(int requestSize) {
        int maxCap = this.getMaxCapacity();
        int newSize;
        if (requestSize == 0) {
            return this.initSize;
        } else if ((requestSize % this.extensionSize) > 0) {
            int rate = (requestSize / this.extensionSize) + 1;
            newSize = Math.min(rate * this.extensionSize, maxCap);
        } else {
            newSize = Math.min(requestSize + this.extensionSize, maxCap);
        }
        return Math.min(newSize, maxCap);
    }

    private void checkExtension(int offset, int len) {
        int requestSize = offset + len;
        if (requestSize > this.capacity()) {
            Buffer toFreeTarget = null;
            try {
                Buffer extTarget = this.pool.requestBuffer(evalSize(requestSize), this.alloc);
                toFreeTarget = extTarget;// when try failed, free requestBuffer.

                ByteBuffer targetBuf = extTarget.getTarget().duplicate();
                targetBuf.clear().position(extTarget.getOffset());
                this.target.get(0, targetBuf, this.target.capacity());
                toFreeTarget = this.target;
                this.target = extTarget;
            } finally {
                if (toFreeTarget != null) {
                    toFreeTarget.free();
                }
            }
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();
        checkExtension(offset, 1);

        this.target.put(offset, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();
        checkExtension(offset, srcLen);

        this.target.put(offset, src, srcOffset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        checkExtension(offset, srcLen);

        this.target.put(offset, src, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        checkExtension(offset, srcLen);

        ByteBuffer tarBuf = this.target.getTarget().duplicate();
        int tarOffset = this.target.getOffset() + offset;

        tarBuf.clear().position(tarOffset);
        return src.readBuffer(tarBuf, srcLen);
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        return this.target.get(offset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        this.target.get(offset, dst, dstOffset, dstLen);
        return dstLen;

    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        this.target.get(offset, dst, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        ByteBuffer targetBuf = this.target.getTarget().duplicate();
        int tarOffset = this.target.getOffset() + offset;

        targetBuf.clear().position(tarOffset);
        dst.writeBuffer(targetBuf, dstLen);
        return dstLen;
    }

    @Override
    protected void _free() {
        if (this.target != null) {
            this.target.free();
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
    public PooledByteBuf copy() {
        checkFree();

        Buffer target = this.pool.requestBuffer(this.target.capacity(), this.alloc);
        ByteBuffer targetBuf = target.getTarget().duplicate();
        targetBuf.clear().position(target.getOffset());
        this._getBytes(this.markedReaderIndex, targetBuf, target.capacity());
        PooledByteBuf byteBuf = new PooledByteBuf(this.alloc, this.getMaxCapacity(), this.extensionSize, target, this.pool);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "PooledByteBuf";
    }
}
