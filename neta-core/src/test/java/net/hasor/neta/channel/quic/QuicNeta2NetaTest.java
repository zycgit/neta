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
package net.hasor.neta.channel.quic;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * QUIC Neta-to-Neta cross-validation tests.
 * Uses raw (non-SSL) QUIC mode for testing.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicNeta2NetaTest extends AbstractSoTest {

    // ── Basic bidirectional communication ───────────────────────────────

    /** Client sends data to server, server receives it. */
    @Test
    public void clientToServer() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, quicConf);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        assert clientChannel != null : "client channel should not be null";

        // send data
        clientChannel.sendData("Hello QUIC Server\n");

        // wait for server to receive
        waitFor(() -> !serverRcvData.isEmpty(), 3000);
        assert serverRcvData.size() >= 1 : "server should receive data, got: " + serverRcvData.size();
        assert serverRcvData.get(0).equals("Hello QUIC Server") : "data mismatch: " + serverRcvData.get(0);

        neta.shutdown();
    }

    /** Server sends data to client, client receives it. */
    @Test
    public void serverToClient() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> clientRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, quicConf);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // wait for server-side channel
        listen.waitAnyAccept();
        NetChannel serverChannel = findServerChannel(neta, listen);
        assert serverChannel != null : "server-side channel should not be null";

        // server sends data to client
        serverChannel.sendData("Hello QUIC Client\n");

        // wait for client to receive
        waitFor(() -> !clientRcvData.isEmpty(), 3000);
        assert clientRcvData.size() >= 1 : "client should receive data, got: " + clientRcvData.size();
        assert clientRcvData.get(0).equals("Hello QUIC Client") : "data mismatch: " + clientRcvData.get(0);

        neta.shutdown();
    }

    /** Bidirectional communication - both sides send and receive. */
    @Test
    public void bidirectional() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();
        List<String> clientRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, quicConf);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // client -> server
        clientChannel.sendData("Hello Server, this message from client.\n");

        // wait for server accept and server-side channel
        listen.waitAnyAccept();
        NetChannel serverChannel = findServerChannel(neta, listen);
        assert serverChannel != null : "server-side channel should not be null";

        // server -> client
        serverChannel.sendData("Hello Client, this message from server.\n");

        // wait for both sides to receive
        waitFor(() -> !serverRcvData.isEmpty() && !clientRcvData.isEmpty(), 3000);

        assert serverRcvData.get(0).equals("Hello Server, this message from client.") : "server got: " + serverRcvData.get(0);
        assert clientRcvData.get(0).equals("Hello Client, this message from server.") : "client got: " + clientRcvData.get(0);

        neta.shutdown();
    }

    // ── Multiple messages ──────────────────────────────────────────────

    /** Client sends multiple messages, server receives all of them in order. */
    @Test
    public void multipleMessages() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        int msgCount = 10;
        for (int i = 0; i < msgCount; i++) {
            clientChannel.sendData("Message-" + i + "\n");
        }

        waitFor(() -> serverRcvData.size() >= msgCount, 3000);
        assert serverRcvData.size() == msgCount : "expected " + msgCount + " messages, got: " + serverRcvData.size();
        for (int i = 0; i < msgCount; i++) {
            assert serverRcvData.get(i).equals("Message-" + i) : "message " + i + " mismatch: " + serverRcvData.get(i);
        }

        neta.shutdown();
    }

    // ── Channel lifecycle ──────────────────────────────────────────────

    /** Verify client channel close is clean. */
    @Test
    public void clientClose() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer proto = ProtoHelper.standard().build();
        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, proto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, proto, quicConf);
        NetChannel clientChannel = connect.get();

        assert !clientChannel.isClose() : "client channel should be open";

        clientChannel.close();

        waitFor(() -> clientChannel.isClose(), 3000);
        assert clientChannel.isClose() : "client channel should be closed after close()";

        neta.shutdown();
    }

    /** Verify server shutdown closes all channels. */
    @Test
    public void serverShutdown() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer proto = ProtoHelper.standard().build();
        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, proto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, proto, quicConf);
        NetChannel clientChannel = connect.get();

        listen.waitAnyAccept();
        NetChannel serverChannel = findServerChannel(neta, listen);

        neta.shutdown();

        waitFor(() -> clientChannel.isClose(), 3000);
        assert clientChannel.isClose() : "client channel should be closed after shutdown";
        assert listen.isClose() : "listen should be closed after shutdown";
    }

    // ── Echo test ──────────────────────────────────────────────────────

    /** Server echoes all received messages back to client. */
    @Test
    public void echoTest() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> clientRcvData = new CopyOnWriteArrayList<>();

        // server: echo handler - receives and sends back
        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                while (src.hasMore()) {
                    ByteBuf data = src.takeMessage();
                    ((NetChannel) context.getChannel()).sendData(data);
                }
                return ProtoStatus.Next;
            }
        }).build();

        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        clientChannel.sendData("Echo this message\n");

        waitFor(() -> !clientRcvData.isEmpty(), 3000);
        assert clientRcvData.get(0).equals("Echo this message") : "echo mismatch: " + clientRcvData.get(0);

        neta.shutdown();
    }

    // ── Handshake verification ─────────────────────────────────────────

    /** Verify QUIC connection establishes properly with raw mode handshake. */
    @Test
    public void handshakeEstablished() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicBoolean serverActive = new AtomicBoolean(false);
        AtomicBoolean clientActive = new AtomicBoolean(false);

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public void onActive(ProtoContext context) {
                serverActive.set(true);
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public void onActive(ProtoContext context) {
                clientActive.set(true);
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // client should always trigger onActive
        assert clientActive.get() : "client onActive should be triggered";

        // trigger server-side channel creation by sending data
        clientChannel.sendData(ByteBuf.wrap(new byte[] { 0x0A }));

        waitFor(() -> serverActive.get(), 3000);
        assert serverActive.get() : "server onActive should be triggered after receiving data";

        neta.shutdown();
    }

    // ── Large message test ─────────────────────────────────────────────

    /** Transfer large data across QUIC connection. */
    @Test
    public void largeMessage() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicReference<byte[]> receivedRef = new AtomicReference<>();

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                data.markReader();
                receivedRef.set(bytes);
            }
            return ProtoStatus.Next;
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // Send 8KB of data
        byte[] largeData = new byte[8192];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i & 0xFF);
        }
        clientChannel.sendData(ByteBuf.wrap(largeData));

        waitFor(() -> receivedRef.get() != null, 3000);
        byte[] received = receivedRef.get();
        assert received != null : "should receive data";
        assert received.length == largeData.length : "length mismatch: expected " + largeData.length + ", got " + received.length;
        for (int i = 0; i < largeData.length; i++) {
            assert received[i] == largeData[i] : "byte mismatch at " + i;
        }

        neta.shutdown();
    }

}
