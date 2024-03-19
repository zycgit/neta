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
public class RecycleArrayByteBuf extends AbstractByteBuf {
    protected final byte[] data;

    RecycleArrayByteBuf(ByteBufAllocator alloc, byte[] initData) {
        super(alloc, initData.length);
        this.data = initData;
        this.writerIndex = initData.length;
        this.markedWriterIndex = initData.length;
    }

    RecycleArrayByteBuf(ByteBufAllocator alloc, int capacity) {
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

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        this.data[offsetSize] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + srcLen) < capacity) {
            System.arraycopy(src, srcOffset, this.data, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            System.arraycopy(src, srcOffset, this.data, offsetSize, partA);
            System.arraycopy(src, srcOffset + partA, this.data, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        src.position(src.position() + srcOffset);
        srcLen = Math.min(src.remaining(), srcLen);

        if ((offsetSize + srcLen) <= capacity) {
            src.get(this.data, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            src.get(this.data, offsetSize, partA);
            src.get(this.data, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        src.skipReadableBytes(srcOffset);
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= capacity) {
            src.readBytes(this.data, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            src.readBytes(this.data, offsetSize, partA);
            src.readBytes(this.data, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        return this.data[offsetSize];
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
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        dst.position(dst.position() + dstOffset);

        if ((offsetSize + dstLen) < capacity) {
            dst.put(this.data, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            dst.put(this.data, offsetSize, partA);
            dst.put(this.data, 0, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstOffset, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        dst.skipWritableBytes(dstOffset);

        if ((offsetSize + dstLen) < capacity) {
            dst.writeBytes(this.data, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            dst.writeBytes(this.data, offsetSize, partA);
            dst.writeBytes(this.data, 0, partB);
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
    public byte[] asByteArray() {
        checkFree();

        byte[] copyArray = new byte[this.markedWriterIndex - this.markedReaderIndex];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        return copyArray;
    }

    @Override
    public RecycleArrayByteBuf copy() {
        checkFree();

        byte[] copyArray = new byte[this.getMaxCapacity()];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        RecycleArrayByteBuf byteBuf = new RecycleArrayByteBuf(this.alloc, copyArray);

        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        byteBuf.readerIndex = this.readerIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "RecycleArrayByteBuf";
    }
}