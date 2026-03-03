package net.hasor.neta.channel.quic;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.codec.LineBasedFrameHandler;
import net.hasor.neta.codec.string.StringDuplexer;
import org.junit.Test;

public class QuicUniStreamSndRcvBadTest extends AbstractSoTest {
    /**
     * 场景：server 向 client 创建的单向流（uni），client 非法反向发送消息。
     * 验证 server 检测到 STREAM_STATE_ERROR 后关闭连接，client 和 server 都能感知到该异常。
     */
    @Test
    public void testUniStreamWithIllegalClientWrite() throws Throwable {
        List<String> clientRcvData = new ArrayList<>();
        AtomicReference<QuicChannel> serverConnRef = new AtomicReference<>();
        AtomicReference<QuicStreamChannel> clientStreamRef = new AtomicReference<>();
        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: codec on stream channels; capture QuicChannel on accept
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
            }
        }, quicCfg).onAccept(c -> {
            if (c instanceof QuicChannel) {
                serverConnRef.compareAndSet(null, (QuicChannel) c);
            }
        });

        // client: codec + data collection + error capture on the server-pushed stream
        neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(data -> {
                    if (data.getError() != null) {
                        clientError.compareAndSet(null, data.getError());
                    } else {
                        clientRcvData.add((String) data.getData());
                    }
                });
                clientStreamRef.compareAndSet(null, (QuicStreamChannel) ctx.getChannel());
            }
        }, quicCfg).get();

        // server opens a uni stream and pushes "hello\n" to client
        waitFor(() -> serverConnRef.get() != null, 5000);
        QuicStreamChannel serverStream = serverConnRef.get().newUniStream().get();
        serverStream.subscribe(data -> {
            if (data.getError() != null) {
                serverError.compareAndSet(null, data.getError());
            }
        });
        serverStream.sendData("hello\n");

        // client receives "hello" from the server-initiated uni stream
        waitFor(() -> !clientRcvData.isEmpty(), 8000);
        assert "hello".equals(clientRcvData.get(0)) : "Expected 'hello', got: " + clientRcvData.get(0);

        // client illegally writes back on the server-initiated uni stream
        clientStreamRef.get().sendData("illegal\n");

        // server detects STREAM_STATE_ERROR and closes; client must observe CONNECTION_CLOSE
        waitFor(() -> clientError.get() != null, 8000);
        assert clientError.get() instanceof QuicConnectionCloseException : "Expected QuicConnectionCloseException on client, got: " + clientError.get().getClass().getName();
        QuicConnectionCloseException cce = (QuicConnectionCloseException) clientError.get();
        assert cce.getErrorCode() == QuicErrorCode.STREAM_STATE_ERROR : "Expected STREAM_STATE_ERROR (0x05), got: 0x" + Long.toHexString(cce.getErrorCode());

        // server must observe the STREAM_STATE_ERROR it triggered locally
        waitFor(() -> serverError.get() != null, 3000);
        assert serverError.get() instanceof QuicException : "Expected QuicException on server, got: " + serverError.get().getClass().getName();
        QuicException sqe = (QuicException) serverError.get();
        assert sqe.getErrorCode() == QuicErrorCode.STREAM_STATE_ERROR : "Expected server STREAM_STATE_ERROR, got: 0x" + Long.toHexString(sqe.getErrorCode());

        neta.shutdown();
    }

    /**
     * 场景：client 向 server 创建的单向流（uni），server 非法反向发送消息。
     * 验证 client 检测到 STREAM_STATE_ERROR 后关闭连接，server 和 client 都能感知到该异常。
     */
    @Test
    public void testClientUniStreamWithIllegalServerWrite() throws Throwable {
        List<String> serverRcvData = new ArrayList<>();
        AtomicReference<QuicChannel> clientConnRef = new AtomicReference<>();
        AtomicReference<QuicStreamChannel> serverStreamRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<Throwable> clientError = new AtomicReference<>();

        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: codec + data collection + error capture on the client-pushed stream
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(data -> {
                    if (data.getError() != null) {
                        serverError.compareAndSet(null, data.getError());
                    } else {
                        serverRcvData.add((String) data.getData());
                    }
                });
                serverStreamRef.compareAndSet(null, (QuicStreamChannel) ctx.getChannel());
            }
        }, quicCfg);

        // client: codec + error capture; capture QuicChannel ref
        QuicChannel clientConn = (QuicChannel) neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(data -> {
                    if (data.getError() != null) {
                        clientError.compareAndSet(null, data.getError());
                    }
                });
            }
            if (ctx.getChannel() instanceof QuicChannel) {
                clientConnRef.compareAndSet(null, (QuicChannel) ctx.getChannel());
            }
        }, quicCfg).get();

        clientConnRef.compareAndSet(null, clientConn);

        // client opens a uni stream and sends "hello\n" to server
        QuicStreamChannel clientStream = clientConn.newUniStream().get();
        clientStream.subscribe(data -> {
            if (data.getError() != null) {
                clientError.compareAndSet(null, data.getError());
            }
        });
        clientStream.sendData("hello\n");

        // server receives "hello" from the client-initiated uni stream
        waitFor(() -> !serverRcvData.isEmpty(), 8000);
        assert "hello".equals(serverRcvData.get(0)) : "Expected 'hello', got: " + serverRcvData.get(0);

        // server illegally writes back on the client-initiated uni stream
        serverStreamRef.get().sendData("illegal\n");

        // client detects STREAM_STATE_ERROR and closes; server must observe CONNECTION_CLOSE
        waitFor(() -> serverError.get() != null, 8000);
        assert serverError.get() instanceof QuicConnectionCloseException : "Expected QuicConnectionCloseException on server, got: " + serverError.get().getClass().getName();
        QuicConnectionCloseException sce = (QuicConnectionCloseException) serverError.get();
        assert sce.getErrorCode() == QuicErrorCode.STREAM_STATE_ERROR : "Expected STREAM_STATE_ERROR (0x05), got: 0x" + Long.toHexString(sce.getErrorCode());

        // client must observe the STREAM_STATE_ERROR it triggered locally
        waitFor(() -> clientError.get() != null, 3000);
        assert clientError.get() instanceof QuicException : "Expected QuicException on client, got: " + clientError.get().getClass().getName();
        QuicException cqe = (QuicException) clientError.get();
        assert cqe.getErrorCode() == QuicErrorCode.STREAM_STATE_ERROR : "Expected client STREAM_STATE_ERROR, got: 0x" + Long.toHexString(cqe.getErrorCode());

        neta.shutdown();
    }
}
