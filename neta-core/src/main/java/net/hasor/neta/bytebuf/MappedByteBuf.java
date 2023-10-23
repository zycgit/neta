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
//package net.hasor.cobble.bytebuf;
//import java.io.RandomAccessFile;
//import java.nio.MappedByteBuffer;
//import java.util.LinkedList;
//import java.util.List;
//
///**
// * 基于 NioChunk 的 ByteBuf 接口实现。
// * @version : 2022-11-01
// * @author 赵永春 (zyc@hasor.net)
// */
//public class MappedByteBuf extends AbstractByteBuf {
//    private RandomAccessFile mappedFile     = null;//= new RandomAccessFile("largeFile.txt", "rw");
//    private MappedByteBuffer mappedBuffer;
//    private NioChunk         mappedNioChunk = null;
//    private int              mapSize;
//    private long             formPosition;
//    private long             toPosition;
//
//    private class MappedByteBufferWrap {
//        private MappedByteBuffer mappedByteBuffer;
//        private int              formPosition;
//        private int              toPosition;
//
//        public void put(int offset, byte b) {
//        }
//    }
//
//    private List<MappedByteBufferWrap> resetOrGetMapper(int offset, int length) {
//        List<MappedByteBuffer> arrayList = new LinkedList<>();
//
//
//        if (offset <= this.formPosition) {
//            if ((offset + length) <= this.toPosition) {
//                // ones
//            } else {
//                // need split
//            }
//        }
//
//
//        if (this.formPosition <= offset && offset <= this.toPosition) {
//            if ((offset + length) <= this.toPosition) {
//                // ones
//            } else {
//                // need split
//            }
//        } else {
//
//        }
//
//
//
//        if (offset + length > (position + mapSize))
//
//            //MapMode mode, long position, long size
//
//            mappedFile.getChannel().map();
//    }
//
//    @Override
//    protected void _putByte(int offset, byte b) {
//        this.resetOrGetMapper(offset, 1).get(0).put(offset, b);
//    }
//
//    @Override
//    protected void _putBytes(int offset, byte[] b, int off, int len) {
//
//    }
//
//    @Override
//    protected byte _getByte(int offset) {
//        return 0;
//    }
//
//    @Override
//    protected int _getBytes(int offset, byte[] b, int off, int len) {
//        return 0;
//    }
//
//    @Override
//    protected void extendByteBuf(int targetCapacity) {
//
//    }
//
//    @Override
//    public int capacity() {
//        return 0;
//    }
//
//    @Override
//    public byte[] array() {
//        throw new UnsupportedOperationException();
//    }
//
//    @Override
//    public boolean isDirect() {
//        return false;
//    }
//
//    @Override
//    public ByteBuf copy() {
//        return null;
//    }
//
//    @Override
//    public void free() {
//
//    }
//}