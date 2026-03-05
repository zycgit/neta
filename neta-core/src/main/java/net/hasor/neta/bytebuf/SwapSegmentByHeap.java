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
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * {@link SwapSegment} implementation backed by a {@link FileChannel}.
 * <p>All reads and writes flow through the OS page-cache via standard
 * {@link FileChannel#read}/{@link FileChannel#write} calls.  Data is copied
 * between the kernel buffer and Java heap {@code byte[]} / {@code ByteBuffer}
 * on every I/O operation (one extra copy vs. mmap), but this mode works on
 * <em>all</em> JVM platforms, including Android.
 * @author 赵永春 (zyc@hasor.net)
 */
final class SwapSegmentByHeap implements SwapSegment {
    private final RandomAccessFile raf;
    private final FileChannel      channel;

    SwapSegmentByHeap(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "rw");
        this.channel = this.raf.getChannel();
    }

    @Override
    public void writeFully(long physPos, byte[] src, int srcOff, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(src, srcOff, len);
        long pos = physPos;
        while (bb.hasRemaining()) {
            pos += this.channel.write(bb, pos);
        }
    }

    @Override
    public void writeFully(long physPos, ByteBuffer src, int len) throws IOException {
        int origLim = src.limit();
        src.limit(src.position() + len);
        long pos = physPos;
        while (src.hasRemaining()) {
            pos += this.channel.write(src, pos);
        }
        src.limit(origLim);
    }

    @Override
    public int readFully(long physPos, byte[] dst, int dstOff, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(dst, dstOff, len);
        long pos = physPos;
        while (bb.hasRemaining()) {
            int n = this.channel.read(bb, pos);
            if (n < 0) {
                break;
            }
            pos += n;
        }
        return len - bb.remaining();
    }

    @Override
    public int readFully(long physPos, ByteBuffer dst, int len) throws IOException {
        int origLim = dst.limit();
        dst.limit(dst.position() + len);
        long pos = physPos;
        int done = 0;
        while (dst.hasRemaining()) {
            int n = this.channel.read(dst, pos);
            if (n < 0) {
                break;
            }
            pos += n;
            done += n;
        }
        dst.limit(origLim);
        return done;
    }

    @Override
    public void close() throws IOException {
        try {
            this.channel.close();
        } finally {
            this.raf.close();
        }
    }
}
