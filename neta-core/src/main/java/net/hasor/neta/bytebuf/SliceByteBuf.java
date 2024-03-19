///*
// * Copyright 2008-2009 the original author or authors.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package net.hasor.neta.bytebuf;
//import java.nio.ByteBuffer;
//
///**
// * 基于 NioChunk 的 ByteBuf 接口实现。
// * @author 赵永春 (zyc@hasor.net)
// * @version : 2022-11-01
// */
//public class SliceByteBuf extends AbstractUnPooledByteBuf {
//    protected final Buffer buffer;
//
//    protected SliceByteBuf(ByteBufAllocator alloc, Buffer initData) {
//        super(alloc, initData.capacity());
//
//        int capacity = initData.capacity();
//        this.buffer = new BufferWrap(initData.duplicate());
//    }
//
//    private static int offsetSize(int offset, int capacity) {
//        return offset % capacity;
//    }
//
//    @Override
//    protected void _putByte(int offset, byte b) {
//        checkFree();
//
//        int capacity = this.getMaxCapacity();
//        int offsetSize = offsetSize(offset, this.getMaxCapacity());
//
//        if (offsetSize < capacity) {
//            this.buffer.put(offsetSize, b);
//        } else {
//            int off = offsetSize - capacity;
//            this.buffer.put(off, b);
//        }
//    }
//
//    @Override
//    protected void _putBytes(int offset, byte[] b, int off, int len) {
//        checkFree();
//
//        int capacity = this.getMaxCapacity();
//        int offsetSize = offsetSize(offset, this.getMaxCapacity());
//
//        if (offsetSize > capacity) {
//            int cutOffset = offsetSize - capacity;
//            if (cutOffset > capacity) {
//                throw new IndexOutOfBoundsException();
//            }
//            offsetSize = cutOffset;
//        }
//
//        if ((offsetSize + len) < capacity) {
//            this.buffer.put(offsetSize, b, off, len);
//        } else {
//            int partA = capacity - offsetSize;
//            int partB = len - partA;
//
//            this.buffer.put(offsetSize, b, off, partA);
//            this.buffer.put(offsetSize, b, off + partA, partB);
//        }
//    }
//
//    @Override
//    protected byte _getByte(int offset) {
//        checkFree();
//
//        int capacity = this.getMaxCapacity();
//        int offsetSize = offsetSize(offset, this.getMaxCapacity());
//
//        if (offsetSize < capacity) {
//            return this.buffer.get(offsetSize);
//        } else {
//            int off = offsetSize - capacity;
//            return this.buffer.get(off);
//        }
//    }
//
//    @Override
//    protected int _getBytes(int offset, byte[] b, int off, int len) {
//        checkFree();
//
//        int capacity = this.getMaxCapacity();
//        int offsetSize = offsetSize(offset, this.getMaxCapacity());
//
//        if ((offsetSize + len) < capacity) {
//            this.buffer.get(offsetSize, b, off, len);
//            return len;
//        } else {
//            int partA = capacity - offsetSize;
//            int partB = len - partA;
//
//            this.buffer.get(offsetSize, b, off, partA);
//            this.buffer.get(offsetSize, b, off + partA, partB);
//            return partA + partB;
//        }
//    }
//
//    @Override
//    protected void _free() {
//        this.buffer.free();
//    }
//
//    @Override
//    public int capacity() {
//        return this.getMaxCapacity();
//    }
//
//    @Override
//    public boolean isDirect() {
//        return this.buffer.isDirect();
//    }
//
//    @Override
//    public byte[] asByteArray() {
//        checkFree();
//
//        byte[] copyArray = new byte[this.markedWriterIndex - this.markedReaderIndex];
//        this._getBytes(0, copyArray, 0, copyArray.length);
//        return copyArray;
//    }
//
//    @Override
//    public SliceByteBuf copy() {
//        checkFree();
//
//        SliceByteBuf copy = new SliceByteBuf(this.alloc, this.capacity(), this.getMaxCapacity(), this.chunkAllocator);
//        copy.markedReaderIndex = this.markedReaderIndex;
//        copy.markedWriterIndex = this.markedWriterIndex;
//        copy.readerIndex = this.readerIndex;
//        copy.writerIndex = this.writerIndex;
//
//        this.data.deepCopy(copy.data);
//
//        return copy;
//
//        //        byte[] copyArray = new byte[this.getMaxCapacity()];
//        //        this._getBytes(0, copyArray, 0, copyArray.length);
//        //        ArrayByteBuf byteBuf = new ArrayByteBuf(this.alloc, copyArray);
//        //
//        //        int wi = this.markedWriterIndex - this.markedReaderIndex;
//        //        byteBuf.markedWriterIndex = wi;
//        //        byteBuf.writerIndex = wi;
//        //        return byteBuf;
//    }
//
//    @Override
//    public ByteBuffer asByteBuffer() {
//        return this.data.byteBuffer();
//    }
//
//    @Override
//    protected String getSimpleName() {
//        return "SliceNioByteBuf";
//    }
//}