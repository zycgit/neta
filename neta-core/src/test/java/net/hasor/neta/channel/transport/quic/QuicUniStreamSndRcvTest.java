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

import org.junit.Test;

import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.codec.LineBasedFrameHandler;
import net.hasor.neta.codec.string.StringDuplex;

/**
 * QUIC 传输层集成测试（非 TLS 明文模式）。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicUniStreamSndRcvTest extends AbstractSoTest {

    /**
     * 场景：server 主动开启单向流（uni），向 client 推送一条消息。
     * 验证：消息可达
     */
    @Test
    public void testUniStreamServer2Client() throws Throwable {
        List<String> clientRcvData = new ArrayList<>();
        AtomicReference<QuicChannel> serverConnRef = new AtomicReference<>();

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: codec on stream channels; capture connection-level QuicChannel via onAccept
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
            }
        }, quicCfg).onAccept(c -> {
            if (c instanceof QuicChannel) {
                serverConnRef.compareAndSet(null, (QuicChannel) c);
            }
        });

        // client: codec + subscribe on the server-pushed uni stream
        neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
                ctx.getChannel().subscribe(data -> clientRcvData.add((String) data.getData()));
            }
        }, quicCfg).get();

        // wait for server to see the connection, then open a uni stream and push data
        waitFor(() -> serverConnRef.get() != null, 5000);
        QuicStreamChannel serverStream = serverConnRef.get().newUniStream().get();
        serverStream.sendData("hello\n");

        //
        waitFor(() -> !clientRcvData.isEmpty(), 8000);
        assert clientRcvData.size() == 1 : "Expected 1 message, got: " + clientRcvData.size();
        assert "hello".equals(clientRcvData.get(0)) : "Client expected 'hello', got: '" + clientRcvData.get(0) + "'";

        neta.shutdown();
    }

    /**
     * 场景：server 主动开启两条向流（uni），用不同的流向 client 推送条消息，
     * 验证：单向流消息隔离。
     */
    @Test
    public void testUniMultipleStreamServer2Client() throws Throwable {
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
                ctx.addLast(new StringDuplex());
            }
        }, quicCfg).onAccept(c -> {
            if (c instanceof QuicChannel) {
                serverConnRef.compareAndSet(null, (QuicChannel) c);
            }
        });

        // client: each server-pushed stream subscribes to the shared clientRcvData list
        neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
                ctx.getChannel().subscribe(data -> clientRcvData.add((String) data.getData()));
            }
        }, quicCfg).get();

        // wait for server to see the connection, then open two independent uni streams
        waitFor(() -> serverConnRef.get() != null, 5000);
        QuicStreamChannel serverStream1 = serverConnRef.get().newUniStream().get();
        QuicStreamChannel serverStream2 = serverConnRef.get().newUniStream().get();
        serverStream1.sendData("msg from stream 1\n");
        serverStream2.sendData("msg from stream 2\n");

        waitFor(() -> clientRcvData.size() >= 2, 8000);

        assert clientRcvData.size() == 2 : "Expected 2 messages, got: " + clientRcvData.size();
        assert clientRcvData.contains("msg from stream 1") : "Missing 'msg from stream 1', got: " + clientRcvData;
        assert clientRcvData.contains("msg from stream 2") : "Missing 'msg from stream 2', got: " + clientRcvData;

        neta.shutdown();
    }

    /**
     * 场景：server 主动开启一条单向流（uni），连续发送 3 条消息。
     * 验证：client 在同一条流上按发送顺序收到全部 3 条消息（顺序保证）。
     */
    @Test
    public void testUniStreamServer2ClientMultipleMessages() throws Throwable {
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
                ctx.addLast(new StringDuplex());
            }
        }, quicCfg).onAccept(c -> {
            if (c instanceof QuicChannel) {
                serverConnRef.compareAndSet(null, (QuicChannel) c);
            }
        });

        // client: subscribe on the server-pushed uni stream
        neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
                ctx.getChannel().subscribe(data -> clientRcvData.add((String) data.getData()));
            }
        }, quicCfg).get();

        // server opens one uni stream and sends three messages in sequence
        waitFor(() -> serverConnRef.get() != null, 5000);
        QuicStreamChannel serverStream = serverConnRef.get().newUniStream().get();
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
     * 场景：client 主动开启一条单向流（uni），连续发送 3 条消息。
     * 验证：server 在同一条流上按发送顺序收到全部 3 条消息（顺序保证）。
     */
    @Test
    public void testUniStreamClient2ServerMultipleMessages() throws Throwable {
        List<String> serverRcvData = new ArrayList<>();

        // neta
        int port = safePort();
        QuicSoConfig quicCfg = quicConfig();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        NetManager neta = new NetManager(globalConf());

        // server: codec + subscribe on the client-pushed uni stream via onAccept
        neta.bind(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
            }
        }, quicCfg).onAccept(c -> {
            if (c instanceof QuicStreamChannel) {
                c.subscribe(data -> serverRcvData.add((String) data.getData()));
            }
        });

        // client: connect and open one uni stream
        QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            if (ctx.getChannel() instanceof QuicStreamChannel) {
                ctx.addLastDecoder(new LineBasedFrameHandler());
                ctx.addLast(new StringDuplex());
            }
        }, quicCfg).get();

        QuicStreamChannel clientStream = client.newUniStream().get();
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