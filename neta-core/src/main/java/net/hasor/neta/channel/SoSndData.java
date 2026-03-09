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
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * A single logical write request carrying one or more {@link ByteBuf} fragments and a
 * completion {@link Future} to notify callers when the send finishes.
 * <p>Neta supports <em>gather I/O</em>: one {@code SoSndData} can reference multiple
 * {@link ByteBuf} segments consumed sequentially via {@link #transferTo}.  This avoids
 * an extra copy when the application assembles a multi-part message (e.g. a fixed-size
 * protocol header followed by a variable-length payload buffer).
 * <p>The {@link Future} is completed with the associated {@link NetChannel} on success,
 * or with a {@link SoSndException} subclass on failure, allowing send-completion callbacks:
 * <pre>
 * channel.write(buf).onComplete(result -&gt; {
 *     if (result.isSuccess()) { log.debug("sent"); }
 *     else { log.error("send failed", result.cause()); }
 * });
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoSndContext
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
            Object raw = this.data[this.readIdx];
            if (!(raw instanceof ByteBuf)) {
                throw new ClassCastException("SoSndData element at index " + this.readIdx + " is " + (raw == null ? "null" : raw.getClass().getName()) + ", expected ByteBuf. Ensure the protocol pipeline encodes all data to ByteBuf.");
            }
            ByteBuf srcBuf = (ByteBuf) raw;
            len += srcBuf.readBuffer(dst);
            srcBuf.markReader();
            if (srcBuf.readableBytes() == 0) {
                this.readIdx++;
            }
        } while (this.hasReadable() && dst.hasRemaining());
        return len;
    }

    /**
     * Returns the next chunk as a raw byte array and advances the read cursor.
     * Returns {@code null} when all fragments have been consumed.
     */
    public byte[] transferPull() {
        if (!this.hasReadable()) {
            return null;
        }

        try {
            Object raw = this.data[this.readIdx];
            if (!(raw instanceof ByteBuf)) {
                throw new ClassCastException("SoSndData element at index " + this.readIdx + " is " + (raw == null ? "null" : raw.getClass().getName()) + ", expected ByteBuf. Ensure the protocol pipeline encodes all data to ByteBuf.");
            }
            ByteBuf srcBuf = (ByteBuf) raw;
            byte[] bytes = srcBuf.asByteArray();

            srcBuf.skipReadableBytes(bytes.length);
            srcBuf.markReader();
            return bytes;
        } finally {
            this.readIdx++;
        }
    }

    /**
     * Returns the next raw data element (typically a {@link ByteBuf}) without copying
     * and advances the read cursor. Returns {@code null} when exhausted.
     */
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
     * Marks the send as successful: fulfills the completion future and releases all {@link ByteBuf} fragments.
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
     * Marks the send as failed: propagates {@code e} to the completion future and releases all {@link ByteBuf} fragments.
     * @param e the cause of the failure
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
        return "ChannelID " + this.result.getChannelId() + ", " + Arrays.toString(this.data);
    }
}