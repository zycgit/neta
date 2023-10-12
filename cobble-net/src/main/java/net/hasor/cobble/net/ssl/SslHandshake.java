package net.hasor.cobble.net.ssl;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.net.SoContext;
import net.hasor.cobble.net.SoDelayTask;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SslHandshake {
    private static final Logger        logger = Logger.getLogger(SslHandshake.class);
    private final        long          channelID;
    private final        SoContext     context;
    private final        SSLEngine     engine;
    //
    private final        AtomicBoolean status;      // 最近一个请求是否还在处理中（由于 doHandshake 方法会分解为多个异步任务，因此 doHandshake 方法返回并不能表示已经处理完毕）
    private volatile     boolean       appendData;  // 在 status = true 期间如果收到数据会被设置为 true
    private volatile     boolean       handshake;
    //
    private final        SslBuffers    buffers;

    public SslHandshake(long channelID, SoContext context, SSLEngine engine, SslBuffers buffers) {
        this.channelID = channelID;
        this.context = context;
        this.engine = engine;
        this.buffers = buffers;
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
        return new SslHandler(this.channelID, this.context, this.engine, this.buffers);
    }

    /** 开始握手 */
    public void beginHandshake() throws SSLException {
        this.engine.beginHandshake();

        SSLSession session = this.engine.getSession();
        this.buffers.initBuffers(session.getApplicationBufferSize(), session.getPacketBufferSize());
    }

    /** 握手阶段无论 rcv/snd 都统一处理 */
    public void handshake(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        if (this.status.compareAndSet(false, true)) {
            this.doHandshake(this.engine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        } else {
            this.appendData = true;
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
                    this.handleTask(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
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

    /** 在握手的过程中会有若干额外的任务需要执行 */
    private void handleTask(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        Runnable runnable;
        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            runnable.run();
        }
        this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
    }

    /** 握手中，Unwrap 操作，负责处理接收的网络数据 */
    private void handleUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        // no more data to read.
        if (!rcvUpstream.hasReadable() && !this.buffers.inNetData.hasRemaining()) {
            this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            return;
        }

        rcvUpstream.read(this.buffers.inNetData);
        rcvUpstream.markReader();
        this.buffers.inNetData.flip();
        this.buffers.inAppData.clear();

        // handshake
        SSLEngineResult result;
        HandshakeStatus hsStatus;
        do {
            result = sslEngine.unwrap(this.buffers.inNetData, this.buffers.inAppData);
            hsStatus = result.getHandshakeStatus();
            // During an handshake renegotiation we might need to perform several unwraps to consume the handshake data.
        } while (result.getStatus() == Status.OK            // process
                && hsStatus == HandshakeStatus.NEED_UNWRAP  // need more UNWRAP
                && result.bytesProduced() == 0);            // no produced any data, continue SslHandshake.

        // after handshake try unwrap once data.
        if (this.buffers.inAppData.position() == 0 && result.getStatus() == Status.OK && this.buffers.inNetData.hasRemaining()) {
            result = sslEngine.unwrap(this.buffers.inNetData, this.buffers.inAppData);
        }
        this.buffers.inNetData.compact();// prepare the buffer to be written again.
        this.buffers.inAppData.flip();// and the app buffer to be read.

        // has AppData,  AppData -> rcvDownstream
        if (this.buffers.inAppData.hasRemaining()) {
            rcvDownstream.write(this.buffers.inAppData);
            rcvDownstream.markWriter();

            if (this.buffers.inAppData.hasRemaining()) {
                SSLEngineResult copyResult = result;
                this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.buffers.inAppData, rcvDownstream), this).onCompleted(f -> {
                    this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, copyResult);
                }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            } else {
                this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
            }
        } else {
            this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
        }
    }

    /** 握手中，Wrap 操作，负责处理发送网络数据 */
    private void handleWrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        // 当没有足够空间写数据时，异步重试
        if (!sndDownstream.hasWritable()) {
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
            return;
        }

        // write data
        sndUpstream.read(this.buffers.outAppData);
        sndUpstream.markReader();

        this.buffers.outAppData.flip();
        this.buffers.outNetData.clear();
        SSLEngineResult result = sslEngine.wrap(this.buffers.outAppData, this.buffers.outNetData);
        this.buffers.outNetData.flip();
        sndDownstream.write(this.buffers.outNetData);
        sndDownstream.markWriter();

        this.buffers.outAppData.compact();// prepare the buffer to be written again.

        // 一次写不完，需要异步任务继续写
        if (this.buffers.outNetData.hasRemaining()) {
            this.context.submitSoTask(new SslCopyTask(this.channelID, this.context, this.buffers.outNetData, sndDownstream), this).onCompleted(f -> {
                this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
            }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
        } else {
            this.afterUnwrap(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream, result);
        }
    }

    /** 握手中，Unwrap/Wrap 的后续处理 */
    private void afterUnwrap(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream, SSLEngineResult result) {
        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW:
                this.handleFinish(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);//need more data
                break;
            case BUFFER_OVERFLOW: {
                this.handleFailed(sslEngine, new BufferOverflowException());
                break;
            }
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
        if (this.appendData) {
            this.appendData = false;
            this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
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
    private void handleClose(SSLEngine sslEngine, ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) {
        if (!sslEngine.isOutboundDone()) {
            sslEngine.closeOutbound();
        }

        this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
            this.doHandshake(sslEngine, rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
        }).onFailed(f -> this.handleFailed(sslEngine, f.getCause()));
    }
}
