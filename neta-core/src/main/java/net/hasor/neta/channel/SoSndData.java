/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * One logical write request carrying one or more {@link ByteBuf} fragments plus a completion
 * {@link Future} used to notify the caller when sending finishes.
 * <p>Neta supports <em>gather I/O</em>: a single {@code SoSndData} can reference multiple
 * {@link ByteBuf} fragments and consume them sequentially through {@link #transferTo}. This avoids
 * extra copies when an application assembles multipart messages such as a fixed-length header plus
 * a variable-length payload.</p>
 * <p>On success, the {@link Future} completes with the associated {@link NetChannel}. On failure,
 * it completes with a subtype of {@link SoSndException}, making it convenient to register send
 * completion callbacks:</p>
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
    private int                      readIdx;

    SoSndData(long sendSize, Object[] data, Future<NetChannel> future, NetChannel result) {
        this.dataSize = sendSize;
        this.data = data;
        this.future = future;
        this.result = result;
        this.readIdx = 0;
    }

    /**
     * Return the total size of the current data packet.
     */
    public long getDataSize() {
        return this.dataSize;
    }

    /**
     * Return whether the current data packet still contains readable data.
     */
    public boolean hasReadable() {
        return this.readIdx < this.data.length;
    }

    /**
     * Copy packet content into the destination buffer.
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
     * Return the next data fragment as a raw byte array and advance the read cursor.
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
     * Return the next raw data element, usually a {@link ByteBuf}, without copying and advance the read cursor.
     * Returns {@code null} when all elements have been consumed.
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
     * Mark sending as successful, complete the callback Future, and release all {@link ByteBuf} fragments.
     */
    public void completed() {
        try {
            this.future.completed(this.result);
        } finally {
            for (Object buf : this.data) {
                SoUtils.release(buf);
            }
        }
    }

    /**
     * Mark sending as failed, propagate {@code e} to the completion Future, and release all {@link ByteBuf} fragments.
     * @param e failure cause
     */
    public void failed(Throwable e) {
        try {
            this.future.failed(e);
        } finally {
            for (Object buf : this.data) {
                SoUtils.release(buf);
            }
        }
    }

    @Override
    public String toString() {
        return "ChannelID " + this.result.getChannelId() + ", " + Arrays.toString(this.data);
    }
}
