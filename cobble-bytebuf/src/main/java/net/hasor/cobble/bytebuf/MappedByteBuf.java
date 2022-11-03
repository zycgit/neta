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
//import java.io.File;
//import java.io.RandomAccessFile;
//import java.nio.MappedByteBuffer;
//import java.nio.channels.FileChannel;
//
//public class MappedByteBuf extends SliceNioByteBuf {
//    private RandomAccessFile mappedFile     = null;//= new RandomAccessFile("largeFile.txt", "rw");
//    private MappedByteBuffer mappedBuffer;
//    private NioChunk         mappedNioChunk = null;
//
//    public MappedByteBuf(int memCapacity, int maxCapacity, File tempFile) {
//        super(memCapacity, maxCapacity, new MappedNioChunkAllocator(tempFile));
//    }
//
//    @Override
//    protected void extendByteBuf(int targetCapacity) {
//        throw new UnsupportedOperationException();
//    }
//
//    private class MappedNioChunkAllocator implements NioChunkAllocator {
//        private RandomAccessFile mappedFile = null;//= new RandomAccessFile("largeFile.txt", "rw");
//        private MappedByteBuffer mappedBuffer;
//
//        MappedNioChunkAllocator(File tempFile) {
//            this.mappedBuffer = mappedFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 1000);
//        }
//
//        @Override
//        public NioChunk allocateBuffer(int capacity) {
//            return null;
//        }
//    }
//}