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
package net.hasor.neta.channel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;

import java.nio.ByteBuffer;

/**
 * data packet
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoSndData {
    private final long               dataSize;
    private final ByteBuf[]          data;
    private final Future<NetChannel> future;
    private final NetChannel         result;
    private       int                readIdx;
    private       int                readIndexBytes;

    public SoSndData(ByteBuf[] data, Future<NetChannel> future, NetChannel result) {
        this(dataSize(data), data, future, result);
    }

    public SoSndData(long sendSize, ByteBuf[] data, Future<NetChannel> future, NetChannel result) {
        this.dataSize = sendSize;
        this.data = data;
        this.future = future;
        this.result = result;
        this.readIdx = 0;
    }

    private static long dataSize(ByteBuf[] bufArray) {
        long size = 0;
        for (ByteBuf buf : bufArray) {
            size += buf.readableBytes();
        }
        return size;
    }

    /**
     * packet size.
     */
    public long getDataSize() {
        return this.dataSize;
    }

    /**
     * packet has any data.
     */
    public boolean hasReadable() {
        return this.readIdx < this.data.length;
    }

    public long readableBytes() {
        return this.dataSize - this.readIndexBytes;
    }

    /**
     * copy packet data to {@link ByteBuf}
     */
    public int transferTo(ByteBuffer dst) {
        if (!this.hasReadable()) {
            return 0;
        }

        int len = 0;
        do {
            ByteBuf srcBuf = this.data[this.readIdx];
            len += srcBuf.readBuffer(dst);
            srcBuf.markReader();
            if (srcBuf.readableBytes() == 0) {
                this.readIdx++;
            }
        } while (this.hasReadable() && dst.hasRemaining());

        this.readIndexBytes += len;
        return len;
    }

    public ByteBuffer transferPull() {
        if (!this.hasReadable()) {
            return null;
        }

        try {
            ByteBuf srcBuf = this.data[this.readIdx];
            byte[] bytes = srcBuf.asByteArray();

            srcBuf.skipReadableBytes(bytes.length);
            srcBuf.markReader();
            this.readIndexBytes += bytes.length;
            return ByteBuffer.wrap(bytes);
        } finally {
            this.readIdx++;
        }
    }

    /**
     * completed callback.
     */
    public void completed() {
        try {
            this.future.completed(this.result);
        } finally {
            for (ByteBuf buf : this.data) {
                IOUtils.closeQuietly(buf);
            }
        }
    }

    /**
     * failed callback.
     */
    public void failed(Throwable e) {
        try {
            this.future.failed(e);
        } finally {
            for (ByteBuf buf : this.data) {
                IOUtils.closeQuietly(buf);
            }
        }
    }

    @Override
    public String toString() {
        return "ChannelID " + this.result.getChannelID() + ", " + this.data.toString();
    }
}