package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.net.SoContext;
import net.hasor.cobble.net.SoDelayTask;
import net.hasor.cobble.net.SoOverflowException;
import net.hasor.cobble.net.SoResManager;

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
 * 处理 SSL
 */
public class SslHandle {
    private static final Logger        logger = Logger.getLogger(SslHandle.class);
    private final        long          channelID;
    private final        SoContext     context;
    private final        SSLEngine     engine;
    private final        SoResManager  rm;
    //
    private final        AtomicBoolean hsStatus;        // 握手处理中
    private volatile     boolean       hsAppendData;    // 在 handshake 期间如果收到数据会被设置为 true
    private volatile     boolean       handshake;
    private final        AtomicBoolean rcvStatus;       // 接收数据处理中
    private volatile     boolean       rcvAppendData;   // 在 rcv 期间如果收到数据会被设置为 true
    private final        AtomicBoolean sndStatus;       // 发送数据处理中
    private volatile     boolean       sndAppendData;   // 在 snd 期间如果收到数据会被设置为 true
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

    /** 管道ID */
    public long getChannelID() {
        return this.channelID;
    }

    /** 关闭 SSL 会话 */
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

    /** 无法恢复的失败，会关闭 Socket */
    private void handleFailed(SSLEngine sslEngine, Throwable e) {
        this.context.closeChannel(this.channelID, e.getMessage());
        this.hsStatus.set(false);
        this.rcvStatus.set(false);
        this.sndStatus.set(false);
    }

    // --------------------------------------------------------------------------------------------
    //
    // 握手相关
    //
    // --------------------------------------------------------------------------------------------

    /** 是否完成握手 */
    public boolean isHandshake() {
        return this.handshake;
    }

    /** 开始握手 */
    public void beginHandshake() throws SSLException {
        logger.info("sslHandshake(" + this.channelID + ") begin.");
        this.engine.beginHandshake();

        SSLSession session = this.engine.getSession();
        this.inAppData = this.rm.newByteBuffer(session.getApplicationBufferSize());
        this.inNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
        this.outAppData = this.rm.newByteBuffer(session.getApplicationBufferSize());
        this.outNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
    }

    /** 握手阶段 rcv/snd 统一处理 */
    public void handshake(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        if (this.hsStatus.compareAndSet(false, true)) {
            this.doHandshake(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.hsAppendData = true;
        }
    }

    private void doHandshake(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        // 已经握手，无需处理
        if (this.handshake) {
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        // 处理 SSL 握手
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

    /** Unwrap 操作，负责处理接收的网络数据 */
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

        // 需要处理 BUFFER_OVERFLOW 的情况，有些 SSL 实现并非完全遵循照标准的固定 Buffer 大小进行封包拆分
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

    /** 握手中，Wrap 操作，负责处理发送网络数据 */
    private void handleWrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        // 当没下游有足够空间写数据时，异步重试
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
        // 需要处理 BUFFER_OVERFLOW 的情况，有些 SSL 实现并非完全遵循照标准的固定 Buffer 大小进行封包拆分
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

        // 一次写不完，需要异步任务继续写
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

    /** 握手中，Unwrap/Wrap 的后续处理 */
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

    /** 结束本轮 handshake 调用  */
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
    // 握手后的数据接收
    //
    // --------------------------------------------------------------------------------------------

    /** 握手之后，处理接收的 SSL 数据 */
    public void handlerRcv(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.rcvStatus.compareAndSet(false, true)) {
            this.doHandlerRcv(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.rcvAppendData = true;
        }
    }

    public void doHandlerRcv(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
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
    // 握手后的数据发送
    //
    // --------------------------------------------------------------------------------------------

    /** 握手之后，处理发送的数据 */
    public void handlerSnd(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.sndStatus.compareAndSet(false, true)) {
            this.doHandlerSnd(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.sndAppendData = true;
        }
    }

    /** 握手之后，处理发送的数据 */
    public void doHandlerSnd(SSLEngine engine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
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