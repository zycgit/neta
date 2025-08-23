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
 * 基于 {@link ByteBuffer} 的环形 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version :  2022-11-01
 */
final class RingByteBuffer extends AbstractByteBuf {
    static RecycleHandler<RingByteBuffer> RECYCLE_HANDLER = new RecycleHandler<RingByteBuffer>() {
        public RingByteBuffer create() {
            return new RingByteBuffer();
        }

        @Override
        public void free(RingByteBuffer tar) {
            RecycleObjectPool.free(RingByteBuffer.class, tar);
        }
    };
    protected ByteBuffer target;

    private RingByteBuffer() {
    }

    void initBuffer(ByteBufAllocator alloc, int capacity) {
        super.initByteBuf(alloc, capacity);
        this.target = alloc.jvmBuffer(capacity);
    }

    // ------------------------------------------------------------------------

    void initBuffer(ByteBufAllocator alloc, ByteBuffer initData) {
        super.initByteBuf(alloc, initData.capacity());
        this.target = initData;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.updateIndex();
        }
        return this;
    }

    private void updateIndex() {
        int capacity = this.target.capacity();
        if (this.markedReaderIndex >= capacity) {
            this.markedReaderIndex = this.markedReaderIndex - capacity;
            this.markedWriterIndex = this.markedWriterIndex - capacity;
            this.readerIndex = this.readerIndex - capacity;
            this.writerIndex = this.writerIndex - capacity;
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int offsetSize = offset % this.getMaxCapacity();
        this.target.put(offsetSize, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;

        if ((offsetSize + srcLen) < maxCap) {
            this.target.clear().position(offsetSize);
            this.target.put(src, srcOffset, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            this.target.clear().position(offsetSize);
            this.target.put(src, srcOffset, partA);

            if (partB > 0) {
                this.target.clear();
                this.target.put(src, srcOffset + partA, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;
        srcLen = Math.min(src.remaining(), srcLen);

        if ((offsetSize + srcLen) <= maxCap) {
            this.target.clear().position(offsetSize);
            this.target.put((ByteBuffer) src.duplicate().limit(src.position() + srcLen));
            src.position(src.position() + srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;
            int step = partA + partB;

            this.target.clear().position(offsetSize);
            ByteBuffer dup4Part = (ByteBuffer) src.duplicate().limit(partA);
            this.target.put(dup4Part);
            src.position(src.position() + partA);

            if (partB > 0) {
                this.target.clear();
                dup4Part.limit(step);
                this.target.put(dup4Part);
                src.position(src.position() + partB);
            }

            return step;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= maxCap) {
            this.target.clear();
            src.readBuffer(this.target, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            this.target.clear().position(offsetSize);
            src.readBuffer(this.target, partA);

            if (partB > 0) {
                this.target.clear();
                src.readBuffer(this.target, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int offsetSize = offset % this.getMaxCapacity();
        this.target.clear();
        return this.target.get(offsetSize);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;

        if ((offsetSize + dstLen) < maxCap) {
            this.target.clear().position(offsetSize);
            this.target.get(dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            this.target.clear().position(offsetSize).limit(offsetSize + partA);
            this.target.get(dst, dstOffset, partA);

            if (partB > 0) {
                this.target.clear().limit(partB);
                this.target.get(dst, dstOffset + partA, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;

        if ((offsetSize + dstLen) < maxCap) {
            this.target.clear().position(offsetSize).limit(dstLen);
            dst.put(this.target);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            this.target.clear().position(offsetSize).limit(offsetSize + partA);
            dst.put(this.target);

            if (partB > 0) {
                this.target.clear().limit(partB);
                dst.put(this.target);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset % maxCap;

        if ((offsetSize + dstLen) < maxCap) {
            this.target.clear();
            dst.writeBuffer(this.target, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            this.target.clear().position(offsetSize);
            dst.writeBuffer(this.target, partA);

            if (partB > 0) {
                this.target.clear();
                dst.writeBuffer(this.target, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected void _free() {
        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.target);
        }
        this.target = null;
        RECYCLE_HANDLER.free(this);
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
    public RingByteBuffer copy() {
        checkFree();

        int capacity = this.getMaxCapacity();
        ByteBuffer copyBuffer = this.alloc.jvmBuffer(capacity);
        this._getBytes(this.markedReaderIndex, copyBuffer, copyBuffer.capacity());
        RingByteBuffer byteBuf = RecycleObjectPool.get(RingByteBuffer.class, RingByteBuffer.RECYCLE_HANDLER);
        byteBuf.initBuffer(this.alloc, copyBuffer);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "RingByteBuffer";
    }
}