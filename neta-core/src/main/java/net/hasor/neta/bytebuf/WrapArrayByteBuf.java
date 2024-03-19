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
 * 基于 字节数组的 ByteBuf 接口实现。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class WrapArrayByteBuf extends AbstractByteBuf {
    protected final byte[] data;

    WrapArrayByteBuf(ByteBufAllocator alloc, byte[] initData) {
        super(alloc, initData.length);
        this.data = initData;
        this.writerIndex = initData.length;
        this.markedWriterIndex = initData.length;
    }

    WrapArrayByteBuf(ByteBufAllocator alloc, int capacity) {
        super(alloc, ObjectUtils.checkPositiveOrZero(capacity, "capacity"));
        this.data = new byte[capacity];
        this.writerIndex = 0;
        this.markedWriterIndex = 0;
    }

    private static int offsetSize(int offset, int capacity) {
        return offset % capacity;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if (offsetSize < capacity) {
            this.data[offsetSize] = b;
        } else {
            int off = offsetSize - capacity;
            this.data[off] = b;
        }
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if (offsetSize > capacity) {
            int cutOffset = offsetSize - capacity;
            if (cutOffset > capacity) {
                throw new IndexOutOfBoundsException();
            }
            offsetSize = cutOffset;
        }

        if ((offsetSize + srcLen) < capacity) {
            System.arraycopy(src, srcOffset, this.data, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            System.arraycopy(src, srcOffset, this.data, offsetSize, partA);
            System.arraycopy(src, partA, this.data, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcOffset, int srcLen) {
        return 0;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcOffset, int srcLen) {
        return 0;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if (offsetSize < capacity) {
            return this.data[offsetSize];
        } else {
            int off = offsetSize - capacity;
            return this.data[off];
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            System.arraycopy(this.data, offsetSize, dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            System.arraycopy(this.data, offsetSize, dst, dstOffset, partA);
            System.arraycopy(this.data, 0, dst, dstOffset + partA, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstOffset, int dstLen) {
        return 0;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstOffset, int dstLen) {
        return 0;
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
        checkFree();

        byte[] copyArray = new byte[this.markedWriterIndex - this.markedReaderIndex];
        this._getBytes(0, copyArray, 0, copyArray.length);
        return copyArray;
    }

    @Override
    public WrapArrayByteBuf copy() {
        checkFree();

        byte[] copyArray = new byte[this.getMaxCapacity()];
        this._getBytes(0, copyArray, 0, copyArray.length);
        WrapArrayByteBuf byteBuf = new WrapArrayByteBuf(this.alloc, copyArray);

        int wi = this.markedWriterIndex - this.markedReaderIndex;
        byteBuf.markedWriterIndex = wi;
        byteBuf.writerIndex = wi;
        return byteBuf;
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(this.data);
    }

    @Override
    protected String getSimpleName() {
        return "ArrayByteBuf";
    }
}