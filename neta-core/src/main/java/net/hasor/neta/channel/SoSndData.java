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

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * data packet
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoSndData {
    private final long               dataSize;
    private final Object[]           data;
    private final Future<NetChannel> future;
    private final NetChannel         result;
    private       int                readIdx;

    SoSndData(long sendSize, Object[] data, Future<NetChannel> future, NetChannel result) {
        this.dataSize = sendSize;
        this.data = data;
        this.future = future;
        this.result = result;
        this.readIdx = 0;
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

    /**
     * copy packet data to {@link ByteBuf}
     */
    public int transferTo(ByteBuffer dst) {
        if (!this.hasReadable()) {
            return 0;
        }

        int len = 0;
        do {
            ByteBuf srcBuf = (ByteBuf) this.data[this.readIdx];
            len += srcBuf.readBuffer(dst);
            srcBuf.markReader();
            if (srcBuf.readableBytes() == 0) {
                this.readIdx++;
            }
        } while (this.hasReadable() && dst.hasRemaining());
        return len;
    }

    public byte[] transferPull() {
        if (!this.hasReadable()) {
            return null;
        }

        try {
            ByteBuf srcBuf = (ByteBuf) this.data[this.readIdx];
            byte[] bytes = srcBuf.asByteArray();

            srcBuf.skipReadableBytes(bytes.length);
            srcBuf.markReader();
            return bytes;
        } finally {
            this.readIdx++;
        }
    }

    public Object transferTake() {
        if (!this.hasReadable()) {
            return null;
        }

        try {
            return this.data[this.readIdx];
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
            for (Object buf : this.data) {
                if (buf instanceof Closeable) {
                    IOUtils.closeQuietly((Closeable) buf);
                }
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
            for (Object buf : this.data) {
                if (buf instanceof Closeable) {
                    IOUtils.closeQuietly((Closeable) buf);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ChannelID " + this.result.getChannelId() + ", " + this.data.toString();
    }
}