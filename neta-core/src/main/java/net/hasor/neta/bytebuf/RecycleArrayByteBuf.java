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
import net.hasor.cobble.ObjectUtils;

import java.nio.ByteBuffer;

/**
 * 基于字节数组的窗口 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class RecycleArrayByteBuf extends AbstractByteBuf {
    protected final byte[] target;

    RecycleArrayByteBuf(ByteBufAllocator alloc, byte[] initData) {
        super(alloc, initData.length);
        this.target = initData;
        this.writerIndex = initData.length;
        this.markedWriterIndex = initData.length;
    }

    RecycleArrayByteBuf(ByteBufAllocator alloc, int capacity) {
        super(alloc, ObjectUtils.checkPositiveOrZero(capacity, "capacity"));
        this.target = new byte[capacity];
        this.writerIndex = 0;
        this.markedWriterIndex = 0;
    }

    @Override
    public ByteBuf markReader() {
        synchronized (this.synchronizedLock) {
            if (this.markedReaderIndex != this.readerIndex) {
                this.markedReaderIndex = this.readerIndex;
                this.updateIndex();
            }

            // notify all writer threads, to write it
            this.synchronizedLock.notifyAll();
        }
        return this;
    }

    private void updateIndex() {
        int capacity = this.target.length;
        if (this.markedReaderIndex >= capacity) {
            this.markedReaderIndex = this.markedReaderIndex - capacity;
            this.markedWriterIndex = this.markedWriterIndex - capacity;
            this.readerIndex = this.readerIndex - capacity;
            this.writerIndex = this.writerIndex - capacity;
        }
    }

    private static int offsetSize(int offset, int capacity) {
        return offset % capacity;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        this.target[offsetSize] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + srcLen) < capacity) {
            System.arraycopy(src, srcOffset, this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            System.arraycopy(src, srcOffset, this.target, offsetSize, partA);
            System.arraycopy(src, srcOffset + partA, this.target, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        srcLen = Math.min(src.remaining(), srcLen);

        if ((offsetSize + srcLen) <= capacity) {
            src.get(this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            src.get(this.target, offsetSize, partA);
            src.get(this.target, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= capacity) {
            src.readBytes(this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            src.readBytes(this.target, offsetSize, partA);
            src.readBytes(this.target, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        return this.target[offsetSize];
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            System.arraycopy(this.target, offsetSize, dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            System.arraycopy(this.target, offsetSize, dst, dstOffset, partA);
            System.arraycopy(this.target, 0, dst, dstOffset + partA, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            dst.put(this.target, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            dst.put(this.target, offsetSize, partA);
            dst.put(this.target, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            dst.writeBytes(this.target, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            dst.writeBytes(this.target, offsetSize, partA);
            dst.writeBytes(this.target, 0, partB);
            return partA + partB;
        }
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
    public RecycleArrayByteBuf copy() {
        checkFree();

        byte[] copyArray = new byte[this.getMaxCapacity()];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        RecycleArrayByteBuf byteBuf = new RecycleArrayByteBuf(this.alloc, copyArray);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "RecycleArrayByteBuf";
    }
}