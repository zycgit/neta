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
package net.hasor.neta.handler.ssl;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.channel.SoOverflowException;
import net.hasor.neta.channel.SoResManager;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Handling the SSL handshake
 * @version : 2023-10-18
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslHandle {
    private static final Logger       logger = Logger.getLogger(SslHandle.class);
    private final        long         channelID;
    private final        SslConfig    config;
    private final        SoContext    context;
    private final        SSLEngine    engine;
    private final        SoResManager rm;
    //
    private volatile     boolean      handshake;
    public               ByteBuffer   inNetData;
    public               ByteBuffer   inAppData;
    public               ByteBuffer   outNetData;
    public               ByteBuffer   outAppData;

    public SslHandle(long channelID, SslConfig config, SoContext context, SSLEngine engine, SoResManager rm) {
        this.channelID = channelID;
        this.config = config;
        this.context = context;
        this.engine = engine;
        this.rm = rm;
        this.handshake = false;
    }

    /** channelID */
    public long getChannelID() {
        return this.channelID;
    }

    /** Closing SSL sessions */
    private void handleClose(SSLEngine sslEngine, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) {
        if (!sslEngine.isOutboundDone()) {
            sslEngine.closeOutbound();
        }

        //        // Indicate that application is done with engine
        //        engine.closeOutbound();
        //        while (!engine.isOutboundDone()) {
        //            // Get close message
        //            SSLEngineResult res = engine.wrap(empty, myNetData);
        //            // Check res statuses
        //            // Send close message to peer
        //            while(myNetData.hasRemaining()) {
        //                int num = socketChannel.write(myNetData);
        //                if (num == 0) {
        //                    // no bytes written; try again later
        //                }
        //                myNetData().compact();
        //            }
        //        }
        //        // Close transport
        //        socketChannel.close();
    }

    /** Failure from which there is no recovery will close the Socket */
    private PipeStatus handleFailed(SSLEngine sslEngine, Throwable e) {
        this.context.closeChannel(this.channelID, e.getMessage());
        return PipeStatus.Interrupt;
    }

    private int readData(PipeRcvQueue<ByteBuf> src, ByteBuffer dst) {
        int total = 0;
        while (src.hasMore()) {
            ByteBuf data = src.peekMessage();
            total += data.read(dst);
            data.markReader();
            if (data.hasReadable()) {
                break;
            } else {
                src.skipMessage(1);
            }
        }
        return total;
    }

    private int writeData(ByteBuffer src, PipeSndQueue<ByteBuf> dst) {
        int length = src.limit();
        ByteBuf byteBuf = this.rm.newByteBuf(src.limit());
        byteBuf.write(src);
        byteBuf.markWriter();
        dst.offerMessage(byteBuf);
        return length;
    }

    // --------------------------------------------------------------------------------------------
    //
    // handshake
    //
    // --------------------------------------------------------------------------------------------

    /** test handshake */
    public boolean isHandshake() {
        return this.handshake;
    }

    /** begin handshake */
    public void beginHandshake() throws SSLException {
        logger.info("sslHandshake(" + this.channelID + ") begin.");
        this.engine.beginHandshake();

        SSLSession session = this.engine.getSession();
        this.inAppData = this.rm.newByteBuffer(session.getApplicationBufferSize());
        this.inNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
        this.outAppData = this.rm.newByteBuffer(session.getApplicationBufferSize());
        this.outNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
    }

    /** The handshake phase is handled by rcv/snd in a unified manner */
    public void handshake(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) {
        // Handshake already done, no need to process
        if (this.handshake) {
            return;
        }

        // Handling the SSL handshake
        try {
            switch (this.engine.getHandshakeStatus()) {
                case NEED_UNWRAP: {
                    this.handleUnwrap(this.engine, rcvUp, rcvDown, sndUp, sndDown);
                    break;
                }
                case NEED_WRAP: {
                    this.handleWrap(this.engine, rcvUp, rcvDown, sndUp, sndDown);
                    break;
                }
            }
        } catch (IOException e) {
            this.handleFailed(this.engine, e);
        }
    }

    /** The Unwrap operation is responsible for processing the received network data */
    private void handleUnwrap(SSLEngine sslEngine, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        // rcvUp to inNetData.
        int rcvTotal = this.readData(rcvUp, this.inNetData);
        this.inNetData.flip();
        if (!this.inNetData.hasRemaining()) {
            this.inNetData.compact();
            return;
        }

        // handshake
        SSLEngineResult result;
        HandshakeStatus hsStatus;
        int consumedBytes = 0;
        int producedBytes = 0;
        do {
            result = sslEngine.unwrap(this.inNetData, this.inAppData);
            hsStatus = result.getHandshakeStatus();
            consumedBytes += result.bytesConsumed();
            producedBytes += result.bytesProduced();

            if (hsStatus == HandshakeStatus.NEED_TASK) {
                Runnable runnable;
                while ((runnable = this.engine.getDelegatedTask()) != null) {
                    runnable.run();
                }
                hsStatus = this.engine.getHandshakeStatus();
            }

            // During an handshake renegotiation we might need to perform several unwraps to consume the handshake data.
        } while (result.getStatus() == Status.OK            // process
                && hsStatus == HandshakeStatus.NEED_UNWRAP  // need more UNWRAP
                && producedBytes == 0);                     // no produced any data, continue SslHandshake.

        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForUnwrap("sslHandshake", this.engine);
            // TODO rcvUpstream.resetReader();
            this.handleUnwrap(sslEngine, rcvUp, rcvDown, sndUp, sndDown);
            return;
        }

        // has AppData
        logger.info("sslHandshake(" + this.channelID + ") Unwrap, " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");

        if (producedBytes > 0) {
            this.inAppData.flip();
            this.writeData(this.inAppData, rcvDown);
            this.inAppData.compact();
        }

        this.inNetData.compact();
        this.afterWrapUnwrap(sslEngine, rcvUp, rcvDown, sndUp, sndDown, result);
    }

    private void resizingBufOverflowForUnwrap(String type, SSLEngine engine) {
        SSLSession session = engine.getSession();
        int oldNetSize = this.inNetData.capacity();
        int oldAppSize = this.inAppData.capacity();
        int newNetSize = session.getPacketBufferSize();
        int newAppSize = session.getApplicationBufferSize();

        if (newNetSize > this.config.getMaxResizingNetBufSize() || newAppSize > this.config.getMaxResizingAppBufSize()) {
            String part1 = newNetSize + "/" + newAppSize;
            String part2 = this.config.getMaxResizingNetBufSize() + "/" + this.config.getMaxResizingAppBufSize();
            String errorMsg = "Unwrap BUFFER_OVERFLOW, " + part1 + " exceed the allowed resizing size " + part2 + " (decodeBuf/dataBuf)";

            logger.error(type + "(" + this.channelID + ") " + errorMsg);
            throw new SoOverflowException(errorMsg);
        }

        String part1 = oldNetSize + "/" + oldAppSize;
        String part2 = newNetSize + "/" + newAppSize;
        logger.warn(type + " (" + this.channelID + ") Unwrap BUFFER_OVERFLOW, resizing " + part1 + " -> " + part2 + " (decodeBuf/dataBuf)");

        this.inNetData = this.rm.freeObject(this.inNetData);
        this.inNetData = this.rm.newByteBuffer(newNetSize);
        this.inAppData = this.rm.freeObject(this.inAppData);
        this.inAppData = this.rm.newByteBuffer(newAppSize);
    }

    /** n the handshake, the Wrap operation is responsible for sending network data */
    private void handleWrap(SSLEngine sslEngine, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        // read data to outAppData
        int dataTotal = this.readData(sndUp, this.outAppData);
        this.outAppData.flip();
        if (!this.outAppData.hasRemaining()) {
            this.outAppData.compact();
            return;
        }

        // wrap Data to SSL Data.
        SSLEngineResult result = sslEngine.wrap(this.outAppData, this.outNetData);
        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForWrap("sslHandshake", this.engine);
            // TODO rcvUpstream.resetReader();
            this.handleWrap(sslEngine, rcvUp, rcvDown, sndUp, sndDown);
            return;
        }

        // has NetData
        int bytesConsumed = result.bytesConsumed();
        int bytesProduced = result.bytesProduced();
        logger.info("sslHandshake(" + this.channelID + ") WRAP, " + dataTotal + "/" + bytesConsumed + "/" + bytesProduced + " (data > encode > snd)");
        if (bytesProduced > 0) {
            this.outNetData.flip();
            this.writeData(this.outNetData, sndDown);
            this.outNetData.compact();
        }

        // finish
        this.outAppData.compact();
        this.afterWrapUnwrap(sslEngine, rcvUp, rcvDown, sndUp, sndDown, result);
    }

    private void resizingBufOverflowForWrap(String type, SSLEngine engine) {
        SSLSession session = engine.getSession();
        int oldNetSize = this.outNetData.capacity();
        int oldAppSize = this.outAppData.capacity();
        int newNetSize = session.getPacketBufferSize();
        int newAppSize = session.getApplicationBufferSize();

        if (newAppSize > this.config.getMaxResizingAppBufSize() || newNetSize > this.config.getMaxResizingNetBufSize()) {
            String part1 = newAppSize + "/" + newNetSize;
            String part2 = this.config.getMaxResizingAppBufSize() + "/" + this.config.getMaxResizingNetBufSize();
            String errorMsg = "Wrap BUFFER_OVERFLOW, " + part1 + " exceed the allowed resizing size " + part2 + " (dataBuf/encodeBuf)";

            logger.error(type + "(" + this.channelID + ") " + errorMsg);
            throw new SoOverflowException(errorMsg);
        }

        String part1 = oldAppSize + "/" + oldNetSize;
        String part2 = newAppSize + "/" + newNetSize;
        logger.warn(type + "(" + this.channelID + ") Wrap BUFFER_OVERFLOW, resizing " + part1 + " -> " + part2 + " (dataBuf/encodeBuf)");

        this.outNetData = this.rm.freeObject(this.outNetData);
        this.outNetData = this.rm.newByteBuffer(newNetSize);
        this.outAppData = this.rm.freeObject(this.outAppData);
        this.outAppData = this.rm.newByteBuffer(newAppSize);
    }

    /** in the handshake, after Unwrap/Wrap */
    private void afterWrapUnwrap(SSLEngine sslEngine, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown, SSLEngineResult result) throws IOException {
        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW:
                return;//need more data
            case OK: {
                if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
                    this.handshake = true;
                    if (this.outAppData.hasRemaining()) {
                        this.handlerSnd(rcvUp, rcvDown, sndUp, sndDown);
                    }
                    return;
                } else {
                    this.handshake(rcvUp, rcvDown, sndUp, sndDown);
                    return;
                }
            }
            case CLOSED:
            default: {
                this.handleClose(sslEngine, rcvUp, rcvDown, sndUp, sndDown);
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    //
    // After the handshake, receive data
    //
    // --------------------------------------------------------------------------------------------

    /** After the handshake, receive data */
    public void handlerRcv(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        // rcvUp to inNetData.
        int rcvTotal = this.readData(rcvUp, this.inNetData);
        this.inNetData.flip();
        if (!this.inNetData.hasRemaining()) {
            this.inNetData.compact();
            logger.info("sslRcv(" + this.channelID + ") data is empty.");
            return;
        }

        // Process incoming data
        SSLEngineResult res = this.engine.unwrap(this.inNetData, this.inAppData);

        // has AppData
        int consumedBytes = res.bytesConsumed();
        int producedBytes = res.bytesProduced();
        logger.info("sslRcv(" + this.channelID + ") " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");
        if (producedBytes > 0) {
            this.inAppData.flip();
            this.writeData(this.inAppData, rcvDown);
            this.inAppData.compact();
        }

        switch (res.getStatus()) {
            case BUFFER_OVERFLOW: {
                // try resizing Buf size.
                this.resizingBufOverflowForUnwrap("sslRcv", this.engine);
                // TODO rcvUpstream.resetReader();
                this.handlerRcv(rcvUp, rcvDown, sndUp, sndDown);
                return;
            }
            case BUFFER_UNDERFLOW: // need more data
            case OK:
                this.inNetData.compact();
                break;
            case CLOSED:
            default: {
                this.handleClose(this.engine, rcvUp, rcvDown, sndUp, sndDown);
                break;
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    //
    // After the handshake, send data
    //
    // --------------------------------------------------------------------------------------------

    /** After the handshake, the data sent is processed */
    public void handlerSnd(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        // read data to outAppData
        int sndTotal = this.readData(sndUp, this.outAppData);
        this.outAppData.flip();
        if (!this.outAppData.hasRemaining()) {
            this.outAppData.compact();
            logger.info("sslSnd(" + this.channelID + ") no data to send.");
            return;
        }

        // Process out data
        SSLEngineResult res = this.engine.wrap(this.outAppData, this.outNetData);

        // has AppData
        int consumedBytes = res.bytesConsumed();
        int producedBytes = res.bytesProduced();
        logger.info("sslSnd(" + this.channelID + ") " + sndTotal + "/" + consumedBytes + "/" + producedBytes + " (data > decode > snd)");
        if (producedBytes > 0) {
            this.outNetData.flip();
            this.writeData(this.outNetData, sndDown);
            this.outNetData.compact();
        }

        switch (res.getStatus()) {
            case BUFFER_OVERFLOW: {
                // try resizing Buf size.
                this.resizingBufOverflowForWrap("sslSnd", this.engine);
                //sndUpstream.resetReader();
                this.handlerSnd(rcvUp, rcvDown, sndUp, sndDown);
                return;
            }
            case BUFFER_UNDERFLOW: // need more data
            case OK: {
                this.outAppData.compact();
                return;
            }
            case CLOSED:
            default: {
                this.handleClose(this.engine, rcvUp, rcvDown, sndUp, sndDown);
                break;
            }
        }
    }
}