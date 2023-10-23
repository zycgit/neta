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
import net.hasor.neta.channel.SoDelayTask;
import net.hasor.neta.channel.SoOverflowException;
import net.hasor.neta.channel.SoResManager;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handling the SSL handshake
 * @version : 2023-10-18
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslHandle {
    private static final Logger        logger = Logger.getLogger(SslHandle.class);
    private final        long          channelID;
    private final        SoContext     context;
    private final        SSLEngine     engine;
    private final        SoResManager  rm;
    //
    private final        AtomicBoolean hsStatus;        // Handshake in progress
    private volatile     boolean       hsAppendData;    // Set to true if data is received during the handshake
    private volatile     boolean       handshake;
    private final        AtomicBoolean rcvStatus;       // Receiving data processing
    private volatile     boolean       rcvAppendData;   // Set to true if data is received during rcv
    private final        AtomicBoolean sndStatus;       // Sending data processing
    private volatile     boolean       sndAppendData;   // Set to true if data is received during snd
    //
    public               ByteBuffer    inNetData;
    public               ByteBuffer    inAppData;
    public               ByteBuffer    outNetData;
    public               ByteBuffer    outAppData;

    public SslHandle(long channelID, SoContext context, SSLEngine engine, SoResManager rm) {
        this.channelID = channelID;
        this.context = context;
        this.engine = engine;
        this.rm = rm;
        this.hsStatus = new AtomicBoolean(false);
        this.rcvStatus = new AtomicBoolean(false);
        this.sndStatus = new AtomicBoolean(false);
        this.handshake = false;
    }

    /** channelID */
    public long getChannelID() {
        return this.channelID;
    }

    /** Closing SSL sessions */
    private void handleClose(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
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
        this.hsStatus.set(false);
        this.rcvStatus.set(false);
        this.sndStatus.set(false);
    }

    /** Failure from which there is no recovery will close the Socket */
    private void handleFailed(SSLEngine sslEngine, Throwable e) {
        this.context.closeChannel(this.channelID, e.getMessage());
        this.hsStatus.set(false);
        this.rcvStatus.set(false);
        this.sndStatus.set(false);
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
    public void handshake(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        if (this.hsStatus.compareAndSet(false, true)) {
            this.doHandshake(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.hsAppendData = true;
        }
    }

    private void doHandshake(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        // Handshake already done, no need to process
        if (this.handshake) {
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        // Handling the SSL handshake
        try {
            switch (sslEngine.getHandshakeStatus()) {
                case NEED_TASK: {
                    Runnable runnable;
                    while ((runnable = sslEngine.getDelegatedTask()) != null) {
                        runnable.run();
                    }
                    this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                    break;
                }
                case NEED_UNWRAP: {
                    this.handleUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                    break;
                }
                case NEED_WRAP: {
                    this.handleWrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                    break;
                }
            }
        } catch (IOException e) {
            this.handleFailed(sslEngine, e);
        }
    }

    /** The Unwrap operation is responsible for processing the received network data */
    private void handleUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        // no more data to read.
        if (!rcvUpstream.hasReadable()) {
            logger.info("sslHandshake(" + this.channelID + ") Unwrap, rcv is empty.");
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        this.inNetData.clear();
        this.inAppData.clear();
        int rcvTotal = rcvUpstream.readableBytes();
        rcvUpstream.read(this.inNetData);
        this.inNetData.flip();

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

            // During an handshake renegotiation we might need to perform several unwraps to consume the handshake data.
        } while (result.getStatus() == Status.OK            // process
                && hsStatus == HandshakeStatus.NEED_UNWRAP  // need more UNWRAP
                && producedBytes == 0);                     // no produced any data, continue SslHandshake.

        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForUnwrap("sslHandshake", this.engine);
            rcvUpstream.resetReader();
            this.handleUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        // markReader rcvUpstream readIndex
        rcvUpstream.resetReader();
        rcvUpstream.skipReadableBytes(consumedBytes);
        rcvUpstream.markReader();
        logger.info("sslHandshake(" + this.channelID + ") Unwrap, " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");

        // has AppData,  AppData -> rcvDownstream
        if (producedBytes > 0) {
            this.inAppData.flip();// and the app buffer to be read.
            rcvDownstream.write(this.inAppData);
            rcvDownstream.markWriter();

            if (this.inAppData.hasRemaining()) {
                SSLEngineResult copyResult = result;
                this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.inAppData, rcvDownstream), this).onCompleted(f -> {
                    this.afterWrapUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, copyResult);
                }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            } else {
                this.afterWrapUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
            }
        } else {
            this.afterWrapUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
        }
    }

    private void resizingBufOverflowForUnwrap(String type, SSLEngine engine) {
        SSLSession session = engine.getSession();
        int oldNetSize = this.inNetData.capacity();
        int oldAppSize = this.inAppData.capacity();
        int newNetSize = session.getPacketBufferSize();
        int newAppSize = session.getApplicationBufferSize();

        SslConfig sslConfig = this.context.getConfig().getSslConfig();
        if (newNetSize > sslConfig.getMaxResizingNetBufSize() || newAppSize > sslConfig.getMaxResizingAppBufSize()) {
            String part1 = newNetSize + "/" + newAppSize;
            String part2 = sslConfig.getMaxResizingNetBufSize() + "/" + sslConfig.getMaxResizingAppBufSize();
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
    private void handleWrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        // Retry asynchronously when no downstream has enough space to write data
        if (!sndDownstream.hasWritable()) {
            logger.info("sslHandshake(" + this.channelID + ") Wrap, sndBuf is full.");
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            return;
        }

        // per send Data
        this.outAppData.clear();
        this.outNetData.clear();
        int dataTotal = sndUpstream.readableBytes();
        sndUpstream.read(this.outAppData);

        // wrap Data to SSL Data.
        this.outAppData.flip();
        SSLEngineResult result = sslEngine.wrap(this.outAppData, this.outNetData);
        // To handle the BUFFER_OVERFLOW case, some SSL implementations do not fully follow the standard fixed Buffer size for splitting packets
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
            this.resizingBufOverflowForWrap("sslHandshake", this.engine);
            rcvUpstream.resetReader();
            this.handleWrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        int bytesConsumed = result.bytesConsumed();
        int bytesProduced = result.bytesProduced();

        // mark sndUpstream pos.
        sndUpstream.resetReader();
        if (bytesConsumed > 0) {
            sndUpstream.skipReadableBytes(bytesConsumed);
            sndUpstream.markReader();
        }

        this.outNetData.flip();
        sndDownstream.write(this.outNetData);
        sndDownstream.markWriter();
        logger.info("sslHandshake(" + this.channelID + ") WRAP, " + dataTotal + "/" + bytesConsumed + "/" + bytesProduced + " (data > encode > snd)");

        // Asynchronous tasks are required to continue writing
        if (this.outNetData.hasRemaining()) {
            this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.outNetData, sndDownstream), this).onCompleted(f -> {
                this.afterWrapUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
        } else {
            this.afterWrapUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
        }
    }

    private void resizingBufOverflowForWrap(String type, SSLEngine engine) {
        SSLSession session = engine.getSession();
        int oldNetSize = this.outNetData.capacity();
        int oldAppSize = this.outAppData.capacity();
        int newNetSize = session.getPacketBufferSize();
        int newAppSize = session.getApplicationBufferSize();

        SslConfig sslConfig = this.context.getConfig().getSslConfig();
        if (newAppSize > sslConfig.getMaxResizingAppBufSize() || newNetSize > sslConfig.getMaxResizingNetBufSize()) {
            String part1 = newAppSize + "/" + newNetSize;
            String part2 = sslConfig.getMaxResizingAppBufSize() + "/" + sslConfig.getMaxResizingNetBufSize();
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
    private void afterWrapUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream, SSLEngineResult result) {
        this.inNetData.compact();
        this.inAppData.compact();
        this.outNetData.compact();
        this.outAppData.compact();

        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW:
                this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);//need more data
                break;
            case OK: {
                if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
                    this.handshake = true;
                    this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                } else {
                    this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                }
                break;
            }
            case CLOSED:
            default: {
                this.handleClose(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                break;
            }
        }
    }

    /** Ends the handshake call  */
    private void handleFinish(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        if (this.hsAppendData) {
            this.hsAppendData = false;
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
        } else {
            this.hsStatus.set(false);
        }
    }

    // --------------------------------------------------------------------------------------------
    //
    // After the handshake, receive data
    //
    // --------------------------------------------------------------------------------------------

    /** After the handshake, receive data */
    public void handlerRcv(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.rcvStatus.compareAndSet(false, true)) {
            this.doHandlerRcv(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.rcvAppendData = true;
        }
    }

    private void doHandlerRcv(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (!rcvUpstream.hasReadable()) {
            this.afterHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        // Process incoming data
        this.inNetData.clear();
        this.inAppData.clear();
        int rcvTotal = rcvUpstream.readableBytes();
        rcvUpstream.read(this.inNetData);
        this.inNetData.flip();
        SSLEngineResult res = engine.unwrap(this.inNetData, this.inAppData);
        int consumedBytes = res.bytesConsumed();
        int producedBytes = res.bytesProduced();

        switch (res.getStatus()) {
            case BUFFER_OVERFLOW: {
                // try resizing Buf size.
                this.resizingBufOverflowForUnwrap("sslRcv", engine);
                rcvUpstream.resetReader();
                this.doHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                return;
            }
            case BUFFER_UNDERFLOW: {
                // need more data
                rcvUpstream.resetReader();
                this.afterHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                return;
            }
            case OK: {
                // copy data to rcvDownstream
                rcvUpstream.resetReader();
                rcvUpstream.skipReadableBytes(consumedBytes);
                rcvUpstream.markReader();
                logger.info("sslRcv(" + this.channelID + ") " + rcvTotal + "/" + consumedBytes + "/" + producedBytes + " (rcv > decode > data)");

                // has AppData,  AppData -> rcvDownstream
                if (producedBytes > 0) {
                    this.inAppData.flip();// and the app buffer to be read.
                    int write = rcvDownstream.write(this.inAppData);
                    rcvDownstream.markWriter();

                    if (this.inAppData.hasRemaining()) {
                        this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.inAppData, rcvDownstream), this).onCompleted(f -> {
                            this.afterHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                        }).onFailed(f -> this.handleFailed(engine, f.getCause()));
                    } else {
                        this.afterHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                    }
                } else {
                    this.afterHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                }
                return;
            }
            case CLOSED:
            default: {
                this.handleClose(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                break;
            }
        }
    }

    private void afterHandlerRcv(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        this.inNetData.compact();
        this.inAppData.compact();

        if (this.rcvAppendData) {
            this.rcvAppendData = false;
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                try {
                    this.doHandlerRcv(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                } catch (IOException e) {
                    this.handleFailed(engine, e);
                }
            }).onFailed(f -> this.handleFailed(engine, f.getCause()));
        } else {
            this.rcvStatus.set(false);
        }
    }

    // --------------------------------------------------------------------------------------------
    //
    // After the handshake, send data
    //
    // --------------------------------------------------------------------------------------------

    /** After the handshake, the data sent is processed */
    public void handlerSnd(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.sndStatus.compareAndSet(false, true)) {
            this.doHandlerSnd(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.sndAppendData = true;
        }
    }

    private void doHandlerSnd(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (!sndUpstream.hasReadable()) {
            this.afterHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        // Process out data
        this.outAppData.clear();
        this.outNetData.clear();
        int sndTotal = sndUpstream.readableBytes();
        sndUpstream.read(this.outAppData);
        this.outAppData.flip();
        SSLEngineResult res = engine.wrap(this.outAppData, this.outNetData);
        int consumedBytes = res.bytesConsumed();
        int producedBytes = res.bytesProduced();

        switch (res.getStatus()) {
            case BUFFER_OVERFLOW: {
                // try resizing Buf size.
                this.resizingBufOverflowForWrap("sslSnd", engine);
                sndUpstream.resetReader();
                this.doHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                return;
            }
            case BUFFER_UNDERFLOW: {
                // need more data
                sndUpstream.resetReader();
                this.afterHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                return;
            }
            case OK: {
                // copy data to rcvDownstream
                sndUpstream.resetReader();
                sndUpstream.skipReadableBytes(consumedBytes);
                sndUpstream.markReader();
                logger.info("sslSnd(" + this.channelID + ") " + sndTotal + "/" + consumedBytes + "/" + producedBytes + " (data > decode > snd)");

                // has AppData,  AppData -> rcvDownstream
                if (producedBytes > 0) {
                    this.outNetData.flip();
                    int write = sndDownstream.write(this.outNetData);
                    sndDownstream.markWriter();

                    if (this.outNetData.hasRemaining()) {
                        this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.outNetData, sndDownstream), this).onCompleted(f -> {
                            this.afterHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                        }).onFailed(f -> this.handleFailed(engine, f.getCause()));
                    } else {
                        this.afterHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                    }
                } else {
                    this.afterHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                }
                return;
            }
            case CLOSED:
            default: {
                this.handleClose(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                break;
            }
        }
    }

    private void afterHandlerSnd(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        this.outNetData.compact();
        this.outAppData.compact();

        if (this.sndAppendData) {
            this.sndAppendData = false;
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                try {
                    this.doHandlerSnd(engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
                } catch (IOException e) {
                    this.handleFailed(engine, e);
                }
            }).onFailed(f -> this.handleFailed(engine, f.getCause()));
        } else {
            this.sndStatus.set(false);
        }
    }
}