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
package net.hasor.neta.channel.transport.quic;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoadListener;
import net.hasor.neta.codec.LineBasedFrameHandler;
import net.hasor.neta.codec.string.StringDuplexer;
import org.junit.Test;

/**
 * QUIC 传输层集成测试（非 TLS 明文模式）。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicBidiStreamSndRcvTest extends AbstractSoTest {
    /**
     * 场景：client 发起单条双向流（bidi）
     * 验证：server 能收到数据并原路回显。
     */
    @Test
    public void testBidiStreamClient2ServerEcho() throws Throwable {
        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        PlayLoadListener serverListener = data -> {
            serverRcvData.add((String) data.getData());
            ((NetChannel) data.getSource()).sendData("echo:" + data.getData() + "\n");
        };
        PlayLoadListener clientListener = data -> {
            clientRcvData.add((String) data.getData());
        };

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(serverListener);
            }
        }, quicCfg);
        // client
        QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
            }
        }, quicCfg).get();

        // new stream and send data
        QuicStreamChannel clientStream = client.newBidiStream().get();
        clientStream.subscribe(clientListener);
        clientStream.sendData("hello\n");
        waitFor(() -> !serverRcvData.isEmpty() && !clientRcvData.isEmpty(), 8000);

        assert serverRcvData.size() == 1;
        assert clientRcvData.size() == 1;
        assert "hello".equals(serverRcvData.get(0)) : "Server expected 'hello', got: '" + serverRcvData.get(0) + "'";
        assert "echo:hello".equals(clientRcvData.get(0)) : "Client expected 'hello', got: '" + clientRcvData.get(0) + "'";

        neta.shutdown();
    }

    /**
     * 场景：client 同时开两条独立双向流，服务器在每个流上 echo 消息。
     * 验证：各流数据相互隔离、互不干扰。
     */
    @Test
    public void testBidiMultipleStreamClient2ServerEcho() throws Throwable {
        List<String> serverRcvData = new ArrayList<>();
        List<String> client1RcvData = new ArrayList<>();
        List<String> client2RcvData = new ArrayList<>();
        PlayLoadListener serverListener = data -> {
            serverRcvData.add((String) data.getData());
            ((NetChannel) data.getSource()).sendData("echo:" + data.getData() + "\n");
        };
        PlayLoadListener client1Listener = data -> {
            client1RcvData.add((String) data.getData());
        };
        PlayLoadListener client2Listener = data -> {
            client2RcvData.add((String) data.getData());
        };

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(serverListener);
            }
        }, quicCfg);

        // client
        QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
            }
        }, quicCfg).get();

        // new stream and send data
        QuicStreamChannel clientStream1 = client.newBidiStream().get();
        clientStream1.subscribe(client1Listener);
        clientStream1.sendData("hello stream 1\n");
        QuicStreamChannel clientStream2 = client.newBidiStream().get();
        clientStream2.subscribe(client2Listener);
        clientStream2.sendData("hello stream 2\n");

        waitFor(() -> !serverRcvData.isEmpty() && !client1RcvData.isEmpty() && !client2RcvData.isEmpty(), 8000);

        assert serverRcvData.size() == 2;
        assert client1RcvData.size() == 1;
        assert client2RcvData.size() == 1;
        assert serverRcvData.contains("hello stream 1") && serverRcvData.contains("hello stream 2");
        assert "echo:hello stream 1".equals(client1RcvData.get(0)) : "Client expected 'hello', got: '" + client1RcvData.get(0) + "'";
        assert "echo:hello stream 2".equals(client2RcvData.get(0)) : "Client expected 'hello', got: '" + client2RcvData.get(0) + "'";

        neta.shutdown();
    }

    /**
     * 场景：server 主动开启一条双向流（bidi），连续向 client 发送 3 条消息。
     * 验证：client 在同一条流上按发送顺序收到全部 3 条消息（顺序保证）。
     */
    @Test
    public void testBidiStreamServer2ClientMultipleMessages() throws Throwable {
        List<String> clientRcvData = new ArrayList<>();
        AtomicReference<QuicChannel> serverConnRef = new AtomicReference<>();

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: codec on stream channels; capture connection via onAccept
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

        // client: subscribe on the server-pushed bidi stream
        neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(data -> clientRcvData.add((String) data.getData()));
            }
        }, quicCfg).get();

        // server opens one bidi stream and sends three messages in sequence
        waitFor(() -> serverConnRef.get() != null, 5000);
        QuicStreamChannel serverStream = serverConnRef.get().newBidiStream().get();
        serverStream.sendData("msg-1\n");
        serverStream.sendData("msg-2\n");
        serverStream.sendData("msg-3\n");

        waitFor(() -> clientRcvData.size() >= 3, 8000);

        assert clientRcvData.size() == 3 : "Expected 3 messages, got: " + clientRcvData.size();
        assert "msg-1".equals(clientRcvData.get(0)) : "Expected 'msg-1' at index 0, got: '" + clientRcvData.get(0) + "'";
        assert "msg-2".equals(clientRcvData.get(1)) : "Expected 'msg-2' at index 1, got: '" + clientRcvData.get(1) + "'";
        assert "msg-3".equals(clientRcvData.get(2)) : "Expected 'msg-3' at index 2, got: '" + clientRcvData.get(2) + "'";

        neta.shutdown();
    }

    /**
     * 场景：client 主动开启一条双向流（bidi），连续发送 3 条消息。
     * 验证：server 在同一条流上按发送顺序收到全部 3 条消息（顺序保证）。
     */
    @Test
    public void testBidiStreamClient2ServerMultipleMessages() throws Throwable {
        List<String> serverRcvData = new ArrayList<>();

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: collect received messages in order
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
                ctx.getChannel().subscribe(data -> serverRcvData.add((String) data.getData()));
            }
        }, quicCfg);

        // client: connect and open one bidi stream
        QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplexer());
            }
        }, quicCfg).get();

        QuicStreamChannel clientStream = client.newBidiStream().get();
        clientStream.sendData("msg-1\n");
        clientStream.sendData("msg-2\n");
        clientStream.sendData("msg-3\n");

        waitFor(() -> serverRcvData.size() >= 3, 8000);

        assert serverRcvData.size() == 3 : "Expected 3 messages, got: " + serverRcvData.size();
        assert "msg-1".equals(serverRcvData.get(0)) : "Expected 'msg-1' at index 0, got: '" + serverRcvData.get(0) + "'";
        assert "msg-2".equals(serverRcvData.get(1)) : "Expected 'msg-2' at index 1, got: '" + serverRcvData.get(1) + "'";
        assert "msg-3".equals(serverRcvData.get(2)) : "Expected 'msg-3' at index 2, got: '" + serverRcvData.get(2) + "'";

        neta.shutdown();
    }
}
