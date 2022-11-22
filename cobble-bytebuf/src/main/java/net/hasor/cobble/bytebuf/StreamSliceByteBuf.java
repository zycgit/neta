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
package net.hasor.cobble.bytebuf;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 将 InputStream 和 OutputStream 的链接在 ByteBuf 的两端。
 * 使其可以不断的从 InputStream 中读取数据，并且基于 ByteBuf 操作这些数据。最后将结果写回 OutputStream。
 *
 * <pre>Input Stream Data -> StreamByteBuf -> Output Stream Data </pre>
 *
 * @version : 2022-11-07
 * @author 赵永春 (zyc@hasor.net)
 */
public class StreamSliceByteBuf extends SliceNioByteBuf implements StreamByteBuf {
    private final InputStream  inStream;
    private final OutputStream outStream;

    protected StreamSliceByteBuf(int capacity, InputStream inStream, OutputStream outStream, NioChunkAllocator chunkAllocator) {
        super(capacity, capacity, chunkAllocator);
        this.inStream = inStream;
        this.outStream = outStream;
    }

    public boolean loadData(int loadMaxSize) throws IOException {
        byte[] buf = new byte[loadMaxSize];
        int len = Math.min(buf.length, this.writableBytes());
        if (len <= 0) {
            return false;
        }

        int n = 0;
        int readBytes = 0;
        while (this.writableBytes() > 0 && -1 < (n = this.inStream.read(buf, 0, len))) {
            this.writeBytes(buf, 0, n);
            readBytes += n;
        }

        if (this.markedWriterIndex != this.writerIndex) {
            this.markedWriterIndex = this.writerIndex;
        }

        return readBytes > 0;
    }

    @Override
    protected void receivedBytes(int lastMarkedWriter, int currentMarkedWriter) throws IOException {
        int len = currentMarkedWriter - lastMarkedWriter;
        int capacity = this.data.capacity();
        int baseOffset = lastMarkedWriter % capacity;

        byte[] buf = new byte[4096];
        while (len > 0) {
            int readBytes = -1;

            if ((baseOffset + len) <= capacity) {
                this.data.clearMaxLimit();
                this.data.position(baseOffset);
                this.data.get(buf, 0, len);
                readBytes = len;
            } else {
                int partA = this.data.capacity() - baseOffset;
                int partB = len - partA;

                this.data.clearMaxLimit();
                this.data.position(baseOffset);
                this.data.get(buf, 0, partA);

                this.data.position(0);
                this.data.get(buf, partA, partB);
                readBytes = partA + partB;
            }
            if (readBytes <= 0) {
                break;
            }

            this.outStream.write(buf, 0, readBytes);
            this.outStream.flush();
            baseOffset += readBytes;
            len -= readBytes;
        }

        this.writerIndex = lastMarkedWriter;
        this.markedWriterIndex = lastMarkedWriter;
        super.receivedBytes(lastMarkedWriter, currentMarkedWriter);
    }

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException();
    }

    @Override
    public StreamSliceByteBuf copy() {
        throw new UnsupportedOperationException();
    }
}
