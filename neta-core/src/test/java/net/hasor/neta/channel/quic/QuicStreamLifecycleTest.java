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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.AbstractSoTest;
import org.junit.Test;

/**
 * Tests for QUIC stream resource creation and release lifecycle:
 * stream OPENED/CLOSED events, openStreams tracking, cleanup on connection close, event properties.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStreamLifecycleTest extends AbstractSoTest {

    /** Verify stream OPENED event is fired when data is first sent on a stream. */
    @Test
    public void streamOpenedEvent() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<QuicStreamEvent> serverEvents = new CopyOnWriteArrayList<>();

        // Server handler that captures QuicStreamEvent
        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                while (src.hasMore()) {
                    src.takeMessage().markReader();
                }
                return ProtoStatus.Next;
            }

            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getEventType() == QuicStreamEvent.class) {
                    serverEvents.add((QuicStreamEvent) event.getData());
                }
                return true;
            }
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // Send data on default stream 0
        clientChannel.sendData(ByteBuf.wrap("stream data\n".getBytes()));

        waitFor(() -> !serverEvents.isEmpty(), 5000);

        // Verify OPENED event for stream 0
        boolean foundOpened = false;
        for (QuicStreamEvent evt : serverEvents) {
            if (evt.getStreamId() == 0 && evt.isOpened()) {
                foundOpened = true;
                break;
            }
        }
        assert foundOpened : "should receive stream OPENED event for stream 0, got events: " + serverEvents;

        neta.shutdown();
    }

    /** Verify stream CLOSED event is fired when FIN is received. */
    @Test
    public void streamClosedEvent() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<QuicStreamEvent> serverEvents = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                while (src.hasMore()) {
                    src.takeMessage().markReader();
                }
                return ProtoStatus.Next;
            }

            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getEventType() == QuicStreamEvent.class) {
                    serverEvents.add((QuicStreamEvent) event.getData());
                }
                return true;
            }
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        // Send a STREAM frame with FIN (using sendStreamData directly)
        listen.waitAnyAccept();
        NetChannel serverCh = findServerChannel(neta, listen);
        assert serverCh instanceof QuicChannel : "server channel should be QuicChannel";
        QuicConnection serverConn = ((QuicChannel) serverCh).getQuicConnection();

        // Open and then close stream 4 from server side
        serverConn.sendStreamData(4, "hello stream 4".getBytes(), true); // with FIN

        waitFor(() -> {
            boolean hasOpen = false, hasClose = false;
            for (QuicStreamEvent evt : serverEvents) {
                if (evt.getStreamId() == 4 && evt.isOpened()) {
                    hasOpen = true;
                }
                if (evt.getStreamId() == 4 && evt.isClosed()) {
                    hasClose = true;
                }
            }
            return hasOpen && hasClose;
        }, 5000);

        boolean foundOpened = false, foundClosed = false;
        for (QuicStreamEvent evt : serverEvents) {
            if (evt.getStreamId() == 4) {
                if (evt.isOpened()) {
                    foundOpened = true;
                }
                if (evt.isClosed()) {
                    foundClosed = true;
                }
            }
        }
        assert foundOpened : "should receive OPENED event for stream 4";
        assert foundClosed : "should receive CLOSED event for stream 4";

        neta.shutdown();
    }

    /** Verify openStreams set is properly managed. */
    @Test
    public void openStreamsTracking() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                src.takeMessage().markReader();
            }
            return ProtoStatus.Next;
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        assert clientChannel instanceof QuicChannel : "client channel should be QuicChannel";

        QuicConnection clientConn = ((QuicChannel) clientChannel).getQuicConnection();

        // Initially no open streams
        assert clientConn.getOpenStreams().isEmpty() : "initially no open streams";

        // Send on stream 0 -> stream opens
        clientConn.sendStreamData(0, "data0".getBytes(), false);
        assert clientConn.getOpenStreams().contains(0L) : "stream 0 should be open";

        // Send on stream 4 -> stream opens
        clientConn.sendStreamData(4, "data4".getBytes(), false);
        assert clientConn.getOpenStreams().contains(4L) : "stream 4 should be open";
        assert clientConn.getOpenStreams().size() == 2 : "2 streams should be open";

        // Close stream 0
        clientConn.closeStream(0);
        assert !clientConn.getOpenStreams().contains(0L) : "stream 0 should be closed";
        assert clientConn.getOpenStreams().contains(4L) : "stream 4 should still be open";

        // Send with FIN on stream 4 -> stream auto-closes
        clientConn.sendStreamData(4, "fin".getBytes(), true);
        assert !clientConn.getOpenStreams().contains(4L) : "stream 4 should be closed after FIN";

        assert clientConn.getOpenStreams().isEmpty() : "all streams should be closed";

        neta.shutdown();
    }

    /** Verify streams are cleaned up when connection closes. */
    @Test
    public void streamCleanupOnConnectionClose() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer proto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, proto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, proto, quicConf);
        NetChannel clientChannel = connect.get();
        assert clientChannel instanceof QuicChannel;

        QuicConnection clientConn = ((QuicChannel) clientChannel).getQuicConnection();

        // Open several streams
        clientConn.sendStreamData(0, "data".getBytes(), false);
        clientConn.sendStreamData(4, "data".getBytes(), false);
        clientConn.sendStreamData(8, "data".getBytes(), false);
        assert clientConn.getOpenStreams().size() == 3 : "3 streams should be open";

        // Close the connection
        clientConn.close();

        // All streams should be cleaned up
        assert clientConn.getOpenStreams().isEmpty() : "all streams should be cleaned up after connection close, got: " + clientConn.getOpenStreams();
        assert !clientConn.isOpen() : "connection should be closed";

        neta.shutdown();
    }

    /** Verify QuicStreamEvent properties (unidirectional, client-initiated). */
    @Test
    public void streamEventProperties() throws Throwable {
        // Client-initiated bidirectional stream: streamId LSB bits = 00
        QuicStreamEvent evt0 = new QuicStreamEvent(0, true);
        assert evt0.isClientInitiated() : "stream 0 should be client-initiated";
        assert !evt0.isUnidirectional() : "stream 0 should be bidirectional";

        // Server-initiated bidirectional stream: streamId LSB bits = 01
        QuicStreamEvent evt1 = new QuicStreamEvent(1, true);
        assert !evt1.isClientInitiated() : "stream 1 should be server-initiated";
        assert !evt1.isUnidirectional() : "stream 1 should be bidirectional";

        // Client-initiated unidirectional: streamId LSB bits = 10
        QuicStreamEvent evt2 = new QuicStreamEvent(2, true);
        assert evt2.isClientInitiated() : "stream 2 should be client-initiated";
        assert evt2.isUnidirectional() : "stream 2 should be unidirectional";

        // Server-initiated unidirectional: streamId LSB bits = 11
        QuicStreamEvent evt3 = new QuicStreamEvent(3, true);
        assert !evt3.isClientInitiated() : "stream 3 should be server-initiated";
        assert evt3.isUnidirectional() : "stream 3 should be unidirectional";

        // toString
        assert evt0.toString().contains("OPENED") : "opened event toString should contain OPENED";
        QuicStreamEvent evtClosed = new QuicStreamEvent(0, false);
        assert evtClosed.toString().contains("CLOSED") : "closed event toString should contain CLOSED";
    }

}
