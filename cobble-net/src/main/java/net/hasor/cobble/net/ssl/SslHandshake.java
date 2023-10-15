package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.net.SoContext;
import net.hasor.cobble.net.SoDelayTask;
import net.hasor.cobble.net.SoResManager;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 握手期间不处理数据发送
 */
public class SslHandshake {
    private static final Logger        logger = Logger.getLogger(SslHandshake.class);
    private static final ByteBuffer    DUMMY  = ByteBuffer.allocate(0);
    private final        long          channelID;
    private final        SoContext     context;
    private final        SSLEngine     engine;
    private final        SoResManager  rm;
    //
    private final        AtomicBoolean status;      // 最近一个请求是否还在处理中（由于 doHandshake 方法会分解为多个异步任务，因此 doHandshake 方法返回并不能表示已经处理完毕）
    private volatile     boolean       appendData;  // 在 status = true 期间如果收到数据会被设置为 true
    private volatile     boolean       handshake;
    // 握手期间使用的 Buffer，每次使用都会 clear 清空，握手完毕后会进行释放以节省内存
    public               ByteBuffer    inNetData;
    public               ByteBuffer    inAppData;
    public               ByteBuffer    outNetData;

    public SslHandshake(long channelID, SoContext context, SSLEngine engine, SoResManager rm) {
        this.channelID = channelID;
        this.context = context;
        this.engine = engine;
        this.rm = rm;
        this.status = new AtomicBoolean(false);
        this.handshake = false;
    }

    /** 管道ID */
    public long getChannelID() {
        return this.channelID;
    }

    /** 是否完成握手 */
    public boolean isHandshake() {
        return this.handshake;
    }

    public SslHandler toSslHandler() {
        return new SslHandler(this.channelID, this.context, this.engine, this.rm);
    }

    /** 开始握手 */
    public void beginHandshake() throws SSLException {
        logger.info("sslHandshake(" + this.channelID + ") begin.");
        this.engine.beginHandshake();

        SSLSession session = this.engine.getSession();
        this.inAppData = this.rm.newByteBuffer(session.getApplicationBufferSize());
        this.inNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
        this.outNetData = this.rm.newByteBuffer(session.getPacketBufferSize());
    }

    /** 握手阶段无论 rcv/snd 都统一处理 */
    public void handshake(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) {
        if (this.status.compareAndSet(false, true)) {
            this.doHandshake(this.engine, rcvUpstream, rcvDownstream, sndDownstream);
        } else {
            this.appendData = true;
        }
    }

    private void doHandshake(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) {
        // 已经握手，无需处理
        if (this.handshake) {
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
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
                    this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                    break;
                }
                case NEED_UNWRAP: {
                    this.handleUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                    break;
                }
                case NEED_WRAP: {
                    this.handleWrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                    break;
                }
            }
        } catch (IOException e) {
            this.handleFailed(sslEngine, e);
        }
    }

    /** 握手中，Unwrap 操作，负责处理接收的网络数据 */
    private void handleUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) throws IOException {
        // no more data to read.
        int rcvTotal = rcvUpstream.readableBytes();
        if (!rcvUpstream.hasReadable()) {
            logger.info("sslHandshake(" + this.channelID + ") Unwrap, rcv is empty.");
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
            return;
        }

        this.inAppData.clear();
        this.inNetData.clear();
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

        // TODO 需要处理 BUFFER_OVERFLOW 的情况，有些 SSL 实现并完全遵循照标准的固定 Buffer 大小

        // markReader rcvUpstream readIndex
        rcvUpstream.resetReader();
        rcvUpstream.skipReadableBytes(consumedBytes);
        rcvUpstream.markReader();
        logger.info("sslHandshake(" + this.channelID + ") Unwrap, " + rcvTotal + "/" + consumedBytes + " (rcv/consumed)");

        // has AppData,  AppData -> rcvDownstream
        if (producedBytes > 0) {
            this.inAppData.flip();// and the app buffer to be read.
            rcvDownstream.write(this.inAppData);
            rcvDownstream.markWriter();

            if (this.inAppData.hasRemaining()) {
                SSLEngineResult copyResult = result;
                this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.inAppData, rcvDownstream), this).onCompleted(f -> {
                    this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream, copyResult);
                }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            } else {
                this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream, result);
            }
        } else {
            this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream, result);
        }
    }

    /** 握手中，Wrap 操作，负责处理发送网络数据 */
    private void handleWrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) throws IOException {
        // 当没有足够空间写数据时，异步重试
        if (!sndDownstream.hasWritable()) {
            logger.info("sslHandshake(" + this.channelID + ") Wrap, sndBuf is full.");
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            return;
        }

        this.outNetData.clear();
        SSLEngineResult result = sslEngine.wrap(DUMMY, this.outNetData);//握手期间不处理上游的输出
        this.outNetData.flip();
        int write = sndDownstream.write(this.outNetData);
        sndDownstream.markWriter();
        int producedBytes = result.bytesProduced();
        logger.info("sslHandshake(" + this.channelID + ") WRAP, " + producedBytes + "/" + write + " (produced/snd)");

        // 一次写不完，需要异步任务继续写
        if (this.outNetData.hasRemaining()) {
            this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.outNetData, sndDownstream), this).onCompleted(f -> {
                this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream, result);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
        } else {
            this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndDownstream, result);
        }
    }

    /** 握手中，Unwrap/Wrap 的后续处理 */
    private void afterUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream, SSLEngineResult result) {
        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW:
                this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);//need more data
                break;
            case BUFFER_OVERFLOW: {
                this.handleFailed(sslEngine, new BufferOverflowException());
                break;
            }
            case OK: {
                if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
                    this.handshake = true;
                    this.inAppData = this.rm.freeObject(this.inAppData);
                    this.inNetData = this.rm.freeObject(this.inNetData);
                    this.outNetData = this.rm.freeObject(this.outNetData);
                    this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                } else {
                    this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                }
                break;
            }
            case CLOSED:
            default: {
                this.handleClose(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
                break;
            }
        }
    }

    /** 结束本轮 handshake 调用  */
    private void handleFinish(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) {
        if (this.appendData) {
            this.appendData = false;
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
        } else {
            this.status.set(false);
        }
    }

    /** 关闭 SSL 会话 */
    private void handleFailed(SSLEngine sslEngine, Throwable e) {
        //        if (e instanceof SSLException) {
        //        } else if (e instanceof ClosedException) {
        //        }
        if (!sslEngine.isOutboundDone()) {
            sslEngine.closeOutbound();
        }
        this.context.closeChannel(this.channelID, e.getMessage());
        this.status.set(false);
    }

    /** 关闭 SSL 会话 */
    private void handleClose(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndDownstream) {
        if (!sslEngine.isOutboundDone()) {
            sslEngine.closeOutbound();
        }

        this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
            this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndDownstream);
        }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
    }
}