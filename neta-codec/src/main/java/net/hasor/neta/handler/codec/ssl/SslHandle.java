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
package net.hasor.neta.handler.codec.ssl;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Handling the SSL handshake
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 */
class SslHandle {
    private static final Logger             logger = Logger.getLogger(SslHandle.class);
    private static final ByteBuffer         EMPTY  = ByteBuffer.allocate(0);
    //
    private final        long               channelID;
    private final        ProtoContext       protoCtx;
    private final        SoContextService   soContext;
    private final        SslContext         sslContext;
    private final        SslEngineWrap      engine;
    private final        ByteBufAllocator   bufAllocator;
    private final        boolean            sslLog;
    private final        Runnable           closeCallBack;
    //
    private volatile     SslHandshakeStatus handshake;
    private              ByteBuffer         inNetData;
    private              ByteBuffer         inAppData;
    private              ByteBuffer         outNetData;
    private              ByteBuffer         outAppData;

    public SslHandle(long channelID, ProtoContext protoCtx, SslContext sslContext, SslEngineWrap engine, Runnable closeCallBack) {
        this.channelID = channelID;
        this.protoCtx = protoCtx;
        this.soContext = (SoContextService) protoCtx.getSoContext();
        this.sslContext = sslContext;
        this.engine = engine;
        this.bufAllocator = protoCtx.getSoContext().getByteBufAllocator();
        this.sslLog = protoCtx.getSoContext().getConfig().isPrintLog();
        this.handshake = SslHandshakeStatus.NotHandshaking;
        this.closeCallBack = closeCallBack;
    }

    /** channelID */
    public long getChannelID() {
        return this.channelID;
    }

    public SslHandshakeStatus getHandshake() {
        return this.handshake;
    }

    private int queueToBuffer(ProtoRcvQueue<ByteBuf> src, ByteBuffer dst) {
        int total = 0;
        while (src.hasMore()) {
            ByteBuf data = src.peekMessage();
            total += data.readBuffer(dst);
            data.markReader();
            if (data.readableBytes() > 0) {
                break;
            } else {
                src.skipMessage(1);
            }
        }
        return total;
    }

    private int bufferToQueue(ByteBuffer src, ProtoSndQueue<ByteBuf> dst) {
        int length = src.limit();
        if (length > 0) {
            ByteBuf byteBuf = this.bufAllocator.buffer(length);
            byteBuf.write(src);
            byteBuf.markWriter();
            dst.offerMessage(byteBuf);
        }
        return length;
    }

    // --------------------------------------------------------------------------------------------
    //
    // handshake
    //
    // --------------------------------------------------------------------------------------------

    public boolean tryHandshake(boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        if (this.handshake == SslHandshakeStatus.Finish) {
            return true; // The handshake has been successful, and the SSL data decryption/encryption is processed
        }

        // start handshake
        if (this.handshake == SslHandshakeStatus.NotHandshaking) {
            logger.info("sslHandshake(" + this.channelID + ") begin.");
            this.engine.beginHandshake();
            this.inAppData = this.resizingBuffer(null, this.engine.getApplicationBufferSize());
            this.inNetData = this.resizingBuffer(null, this.engine.getPacketBufferSize());
            this.outAppData = this.resizingBuffer(null, this.engine.getApplicationBufferSize());
            this.outNetData = this.resizingBuffer(null, this.engine.getPacketBufferSize());
            this.handshake = SslHandshakeStatus.Handshaking;
        }

        // handshake requests
        if (this.handshake == SslHandshakeStatus.Handshaking) {
            try {
                this.doHandshake(rcvUp, rcvDown, sndUp, sndDown);
            } catch (IOException e) {
                String type = isRcv ? "rcv" : "snd";
                if (this.sslLog) {
                    logger.error("sslHandshake(" + this.channelID + ") " + type + "Failed: " + e.getMessage(), e);
                } else {
                    logger.error("sslHandshake(" + this.channelID + ") " + type + "Failed: " + e.getMessage());
                }
                this.handleClose(sndDown);
                throw e;
            }
        }

        // Handshake successful
        return this.handshake == SslHandshakeStatus.Finish;
    }

    /** The handshake phase is handled by rcv/snd in a unified manner */
    private void doHandshake(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        HandshakeStatus hs = this.engine.getHandshakeStatus();
        while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
            SSLEngineResult result;
            switch (hs.name()) {
                case "NEED_UNWRAP":
                case "NEED_UNWRAP_AGAIN":
                    result = this.handshakeUnwrap(rcvUp, rcvDown);
                    hs = result.getHandshakeStatus();
                    break;
                case "NEED_WRAP":
                    result = this.handshakeWrap(sndUp, sndDown);
                    hs = result.getHandshakeStatus();
                    break;
                case "NEED_TASK":
                    this.engine.getTask().run();
                    hs = this.engine.getHandshakeStatus();
                    continue;
                default:
                    throw new SSLHandshakeException("inner error ,can't happen.");
                    // Handle other status:  // FINISHED or NOT_HANDSHAKING
            }

            // process Status
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW: {
                    if (rcvUp.hasMore()) {
                        continue;
                    } else {
                        return;
                    }
                }
                case OK: {
                    if (hs == HandshakeStatus.FINISHED) {
                        this.handshake = SslHandshakeStatus.Finish;
                        logger.info("sslHandshake(" + this.channelID + ") finish.");
                        ((NetChannel) this.protoCtx.getChannel()).fireUserEvent(SslEvent.class, new SslEvent(true, this.sslContext));

                        if (this.outAppData.hasRemaining()) {
                            this.handshakeWrap(sndUp, sndDown);
                        }
                        if (this.inNetData.hasRemaining()) {
                            this.handshakeUnwrap(rcvUp, rcvDown);
                        }
                        return;
                    } else {
                        break;
                    }
                }
                case CLOSED: {
                    this.handleClose(sndDown);
                    return;
                }
            }
        }
    }

    /** Failure from which there is no recovery will close the Socket */
    private void handleClose(ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        // Release all resources such as internal buffers that SSLEngine is managing.
        this.engine.closeOutbound();
        this.engine.closeInbound();

        this.outNetData.clear();
        while (!this.engine.isOutboundDone()) {
            SSLEngineResult res = this.engine.wrap(EMPTY, this.outNetData);
            if (res.bytesProduced() > 0) {
                this.outNetData.flip();
                this.bufferToQueue(this.outNetData, sndDown);
            }
            this.outNetData.compact();
        }

        this.afterClose();
    }

    /** The Unwrap operation is responsible for processing the received network data */
    private SSLEngineResult handshakeUnwrap(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) throws IOException {
        // rcvUp to inNetData.
        int rcvTotal = this.queueToBuffer(rcvUp, this.inNetData);
        this.inNetData.flip();

        // handshake
        SSLEngineResult result;
        HandshakeStatus hsStatus;
        int consumedBytes = 0;
        int producedBytes = 0;
        do {
            result = this.engine.unwrap(this.inNetData, this.inAppData);
            // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                this.resizingBufOverflowForUnwrap("sslHandshake");
                result = this.engine.unwrap(this.inNetData, this.inAppData);
            }

            hsStatus = result.getHandshakeStatus();
            consumedBytes += result.bytesConsumed();
            producedBytes += result.bytesProduced();

            // During an handshake renegotiation we might need to perform several unwraps to consume the handshake data.
        } while (result.getStatus() == Status.OK            // process
                && hsStatus == HandshakeStatus.NEED_UNWRAP  // need more UNWRAP
                && producedBytes == 0);                     // no produced any data, continue SslHandshake.

        // has AppData
        if (this.sslLog) {
            logger.info("sslHandshake(" + this.channelID + ") Unwrap, " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");
        }

        this.inAppData.flip();
        if (producedBytes > 0) {
            this.bufferToQueue(this.inAppData, rcvDown);
        }

        this.inAppData.compact();
        this.inNetData.compact();
        return result;
    }

    /** n the handshake, the Wrap operation is responsible for sending network data */
    private SSLEngineResult handshakeWrap(ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        // read data to outAppData
        int dataTotal = this.queueToBuffer(sndUp, this.outAppData);
        this.outAppData.flip();

        // wrap Data to SSL Data.
        SSLEngineResult result = this.engine.wrap(this.outAppData, this.outNetData);
        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForWrap("sslHandshake");
            result = this.engine.wrap(this.outAppData, this.outNetData);
        }

        // has NetData
        int bytesConsumed = result.bytesConsumed();
        int bytesProduced = result.bytesProduced();
        if (this.sslLog) {
            logger.info("sslHandshake(" + this.channelID + ") WRAP, " + dataTotal + "/" + bytesConsumed + "/" + bytesProduced + " (data > encode > snd)");
        }

        this.outNetData.flip();
        if (bytesProduced > 0) {
            this.bufferToQueue(this.outNetData, sndDown);
        }

        // finish
        this.outAppData.compact();
        this.outNetData.compact();
        return result;
    }

    private void resizingBufOverflowForUnwrap(String type) {
        int oldAppSize = this.inAppData.capacity();
        int newAppSize = this.engine.getApplicationBufferSize();

        String resizing = oldAppSize + " -> " + newAppSize;
        logger.warn(type + " (" + this.channelID + ") Unwrap BUFFER_OVERFLOW, resizing " + resizing + " (inputAppBuf)");

        this.inAppData = resizingBuffer(this.inAppData, newAppSize);
    }

    private void resizingBufOverflowForWrap(String type) {
        int oldNetSize = this.outNetData.capacity();
        int newNetSize = this.engine.getPacketBufferSize();

        String resizing = oldNetSize + " -> " + newNetSize;
        logger.warn(type + "(" + this.channelID + ") Wrap BUFFER_OVERFLOW, resizing " + resizing + " (outputNetBuf)");

        this.outNetData = resizingBuffer(this.outNetData, newNetSize);
    }

    private ByteBuffer resizingBuffer(ByteBuffer oldBuf, int newSize) {
        ByteBuffer newBuf;
        if (this.bufAllocator.isDirect()) {
            newBuf = ByteBuffer.allocateDirect(newSize);
        } else {
            newBuf = ByteBuffer.allocate(newSize);
        }

        if (oldBuf != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(oldBuf);
        }

        return newBuf;
    }

    public void clearBuffers() {
        this.inNetData.clear();
        this.inAppData.clear();
        this.outNetData.clear();
        this.outAppData.clear();
    }

    public void afterClose() throws IOException {
        this.clearBuffers();
        this.handshake = SslHandshakeStatus.NotHandshaking;
        this.closeCallBack.run();
        ((NetChannel) this.protoCtx.getChannel()).fireUserEvent(SslEvent.class, new SslEvent(false, this.sslContext));
    }
    // --------------------------------------------------------------------------------------------
    //
    // After the handshake, receive data
    //
    // --------------------------------------------------------------------------------------------

    /** After the handshake, receive data */
    public void handlerRcv(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        // rcvUp to inNetData.
        int rcvTotal = this.queueToBuffer(rcvUp, this.inNetData);

        // Process incoming data
        this.inNetData.flip();
        SSLEngineResult result = this.engine.unwrap(this.inNetData, this.inAppData);
        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            // try resizing Buf size.
            this.resizingBufOverflowForUnwrap("sslRcv");
            this.handlerRcv(rcvUp, rcvDown, sndUp, sndDown);
            return;
        }

        // has AppData
        int consumedBytes = result.bytesConsumed();
        int producedBytes = result.bytesProduced();
        if (this.sslLog) {
            logger.info("sslRcv(" + this.channelID + ") " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");
        }

        this.inAppData.flip();
        if (producedBytes > 0) {
            this.bufferToQueue(this.inAppData, rcvDown);
        }

        this.inAppData.compact();
        this.inNetData.compact();

        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW: // need more data
            case OK:
                break;
            case CLOSED:
                this.afterClose();
                break;
            default:
                break;
        }
    }

    /** After the handshake, the data sent is processed */
    public void handlerSnd(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        // read data to outAppData
        int sndTotal = this.queueToBuffer(sndUp, this.outAppData);

        // Process out data
        this.outAppData.flip();
        SSLEngineResult result = this.engine.wrap(this.outAppData, this.outNetData);
        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForWrap("sslSnd");
            this.handlerSnd(rcvUp, rcvDown, sndUp, sndDown);
            return;
        }

        // has AppData
        int consumedBytes = result.bytesConsumed();
        int producedBytes = result.bytesProduced();
        if (this.sslLog) {
            logger.info("sslSnd(" + this.channelID + ") " + sndTotal + "/" + consumedBytes + "/" + producedBytes + " (data > decode > snd)");
        }

        this.outNetData.flip();
        if (producedBytes > 0) {
            this.bufferToQueue(this.outNetData, sndDown);
        }

        this.outNetData.compact();
        this.outAppData.compact();

        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW: // need more data
            case OK:
                break;
            case CLOSED:
                this.afterClose();
                break;
        }
    }
}