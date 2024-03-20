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
 * 基于 {@link ByteBuffer} 的窗口 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version :  2022-11-01
 */
public class RecycleSliceByteBuf extends AbstractByteBuf {
    protected final ByteBuffer target;

    RecycleSliceByteBuf(ByteBufAllocator alloc, ByteBuffer initData) {
        super(alloc, initData.capacity());
        this.target = initData;
    }

    private static int offsetSize(int offset, int capacity) {
        return offset % capacity;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        this.target.put(offsetSize, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + srcLen) < capacity) {
            this.target.clear().position(offsetSize);
            this.target.put(src, srcOffset, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            this.target.clear().position(offsetSize);
            this.target.put(src, srcOffset, partA);
            this.target.clear();
            this.target.put(src, srcOffset + partA, partB);
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
            this.target.clear().position(offsetSize);
            this.target.put((ByteBuffer) src.duplicate().limit(src.position() + srcLen));
            src.position(src.position() + srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;
            int step = partA + partB;

            this.target.clear().position(offsetSize);
            ByteBuffer dup4Part = (ByteBuffer) src.duplicate().limit(partA);
            this.target.put(dup4Part);
            src.position(src.position() + partA);
            this.target.clear();
            dup4Part.limit(step);
            this.target.put(dup4Part);

            src.position(src.position() + partB);
            return step;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= capacity) {
            this.target.clear();
            src.readBuffer(this.target, srcLen);
            return srcLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = srcLen - partA;

            this.target.clear().position(offsetSize);
            src.readBuffer(this.target, partA);
            this.target.clear();
            src.readBuffer(this.target, partB);
            return partA + partB;
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int offsetSize = offsetSize(offset, this.getMaxCapacity());
        this.target.clear();
        return this.target.get(offsetSize);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            this.target.clear().position(offsetSize);
            this.target.get(dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            this.target.clear().position(offsetSize).limit(offsetSize + partA);
            this.target.get(dst, dstOffset, partA);
            this.target.clear().limit(partB);
            this.target.get(dst, dstOffset + partA, partB);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            this.target.clear().position(offsetSize).limit(dstLen);
            dst.put(this.target);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            this.target.clear().position(offsetSize).limit(offsetSize + partA);
            dst.put(this.target);
            this.target.clear().limit(partB);
            dst.put(this.target);
            return partA + partB;
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        int capacity = this.getMaxCapacity();
        int offsetSize = offsetSize(offset, this.getMaxCapacity());

        if ((offsetSize + dstLen) < capacity) {
            this.target.clear();
            dst.writeBuffer(this.target, dstLen);
            return dstLen;
        } else {
            int partA = capacity - offsetSize;
            int partB = dstLen - partA;
            this.target.clear().position(offsetSize);
            dst.writeBuffer(this.target, partA);
            this.target.clear();
            dst.writeBuffer(this.target, partB);
            return partA + partB;
        }
    }

    @Override
    protected void _free() {
        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.target);
        }
    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public byte[] asByteArray() {
        checkFree();

        byte[] copyArray = new byte[this.markedWriterIndex - this.markedReaderIndex];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        return copyArray;
    }

    @Override
    public RecycleSliceByteBuf copy() {
        checkFree();

        ByteBuffer copyBuffer;
        if (this.isDirect()) {
            copyBuffer = ByteBuffer.allocateDirect(this.getMaxCapacity());
        } else {
            copyBuffer = ByteBuffer.allocate(this.getMaxCapacity());
        }

        this._getBytes(this.markedReaderIndex, copyBuffer, copyBuffer.capacity());
        RecycleSliceByteBuf byteBuf = new RecycleSliceByteBuf(this.alloc, copyBuffer);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "RecycleSliceByteBuf";
    }
}