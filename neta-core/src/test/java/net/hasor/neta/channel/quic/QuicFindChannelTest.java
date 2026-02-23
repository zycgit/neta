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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * Tests that both QuicChannel and QuicStreamChannel can be found
 * via {@link NetManager#findChannel(long)}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicFindChannelTest extends AbstractSoTest {

    /**
     * After establishing a QUIC connection, the client-side QuicChannel
     * should be findable via NetManager.findChannel().
     */
    @Test
    public void findClientQuicChannel() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        neta.bind(address, serverProto, quicConf);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        assert clientChannel != null : "client channel should not be null";

        // Verify: findChannel should return the client QuicChannel
        long clientId = clientChannel.getChannelId();
        SoChannel<?> found = neta.findChannel(clientId);
        assert found != null : "findChannel should find client QuicChannel by ID " + clientId;
        assert found == clientChannel : "findChannel should return the exact same client QuicChannel instance";
        assert found instanceof QuicChannel : "found channel should be a QuicChannel";

        neta.shutdown();
    }

    /**
     * After establishing a QUIC connection, the server-side QuicChannel
     * should be findable via NetManager.findChannel().
     */
    @Test
    public void findServerQuicChannel() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, quicConf);

        // client connect and send data to trigger server-side channel creation
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        clientChannel.sendData("ping\n");

        // wait for server to receive
        waitFor(() -> !serverRcvData.isEmpty(), 3000);

        // find server-side QuicChannel
        NetChannel serverChannel = findServerChannel(neta, listen);
        assert serverChannel != null : "server-side QuicChannel should be findable via findServerChannel helper";

        // Verify: findChannel should return the server-side QuicChannel
        long serverId = serverChannel.getChannelId();
        SoChannel<?> found = neta.findChannel(serverId);
        assert found != null : "findChannel should find server-side QuicChannel by ID " + serverId;
        assert found == serverChannel : "findChannel should return the exact same server QuicChannel instance";
        assert found instanceof QuicChannel : "found channel should be a QuicChannel";

        neta.shutdown();
    }

    /**
     * After receiving stream data, the QuicStreamChannel that was auto-created
     * should be findable via NetManager.findChannel().
     */
    @Test
    public void findAutoCreatedStreamChannel() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, quicConf);

        // client connects and sends data
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        clientChannel.sendData("Hello Stream\n");

        // wait for server to receive
        waitFor(() -> !serverRcvData.isEmpty(), 3000);

        // Get server-side QuicChannel
        NetChannel serverNetChannel = findServerChannel(neta, listen);
        assert serverNetChannel instanceof QuicChannel : "server channel should be QuicChannel";
        QuicChannel serverQuicChannel = (QuicChannel) serverNetChannel;

        // The auto-created stream channel for stream 0 should exist
        QuicStreamChannel stream0 = serverQuicChannel.findStream(0);
        assert stream0 != null : "stream 0 should exist after receiving data";

        // Verify: findChannel should return the QuicStreamChannel
        long streamId = stream0.getChannelId();
        SoChannel<?> found = neta.findChannel(streamId);
        assert found != null : "findChannel should find QuicStreamChannel by ID " + streamId;
        assert found == stream0 : "findChannel should return the exact same QuicStreamChannel instance";
        assert found instanceof QuicStreamChannel : "found channel should be a QuicStreamChannel";

        neta.shutdown();
    }

    /**
     * QuicStreamChannel created explicitly with newStream() should be findable
     * via NetManager.findChannel().
     */
    @Test
    public void findExplicitlyCreatedStreamChannel() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> clientRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        // server
        neta.bind(address, serverProto, quicConf);

        // client connects
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        assert clientChannel instanceof QuicChannel : "client channel should be QuicChannel";
        QuicChannel clientQuicChannel = (QuicChannel) clientChannel;

        // send data first to trigger handshake completion
        clientChannel.sendData("init\n");
        waitFor(() -> clientQuicChannel.getHandshakeState() == QuicChannel.HandshakeState.ESTABLISHED, 3000);

        // Explicitly create a stream
        long bidiStreamId = clientQuicChannel.nextBidiStreamId();
        QuicStreamChannel streamCh = clientQuicChannel.newStream(bidiStreamId);
        assert streamCh != null : "newStream should create a QuicStreamChannel";

        // Verify: findChannel should return the QuicStreamChannel
        long streamChannelId = streamCh.getChannelId();
        SoChannel<?> found = neta.findChannel(streamChannelId);
        assert found != null : "findChannel should find explicitly created QuicStreamChannel by ID " + streamChannelId;
        assert found == streamCh : "findChannel should return the exact same QuicStreamChannel instance";
        assert found instanceof QuicStreamChannel : "found channel should be a QuicStreamChannel";

        neta.shutdown();
    }
}
