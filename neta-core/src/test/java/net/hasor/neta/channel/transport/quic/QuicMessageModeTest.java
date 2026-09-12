/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetManager;

public class QuicMessageModeTest extends AbstractSoTest {
    @Test
    public void testQuicMessageShouldReuseReleasedInstance() {
        ByteBuf body1 = toBuffer("reuse-1");
        QuicMessage message1 = QuicMessage.of(0, body1, false);
        message1.release();

        assertTrue(body1.isFree());

        ByteBuf body2 = toBuffer("reuse-2");
        QuicMessage message2 = QuicMessage.of(2, body2, true);

        assertSame(message1, message2);
        assertEquals(2L, message2.streamId());
        assertEquals("reuse-2", decodeText(message2.content()));
        assertTrue(message2.isFin());
        assertTrue(message2.isUni());

        message2.release();
        assertTrue(body2.isFree());
    }

    @Test
    public void testMessageModeClientServerEcho() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        List<ReceivedMessage> serverRcvData = new CopyOnWriteArrayList<>();
        List<ReceivedMessage> clientRcvData = new CopyOnWriteArrayList<>();
        AtomicReference<QuicChannel> serverConnRef = new AtomicReference<>();

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> {
                        ReceivedMessage received = ReceivedMessage.from((QuicMessage) data.getData());
                        serverRcvData.add(received);
                        ((QuicChannel) data.getSource()).sendData(QuicMessage.of(received.streamId, toBuffer("echo:" + received.text), false));
                    });
                }
            }, quicCfg).onAccept(ch -> {
                if (ch instanceof QuicChannel) {
                    serverConnRef.compareAndSet(null, (QuicChannel) ch);
                }
            });

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> clientRcvData.add(ReceivedMessage.from((QuicMessage) data.getData())));
                }
            }, quicCfg).get();

            waitFor(() -> serverConnRef.get() != null, 5000);
            assertNotNull(serverConnRef.get());

            long streamId = client.allocateBidiStreamId();
            client.sendData(QuicMessage.of(streamId, toBuffer("hello-message-mode"), false)).get();

            waitFor(() -> !serverRcvData.isEmpty() && !clientRcvData.isEmpty(), 8000);

            assertEquals(1, serverRcvData.size());
            assertEquals(1, clientRcvData.size());
            assertEquals(streamId, serverRcvData.get(0).streamId);
            assertEquals("hello-message-mode", serverRcvData.get(0).text);
            assertFalse(serverRcvData.get(0).fin);
            assertTrue(serverRcvData.get(0).bidi);
            assertEquals(streamId, clientRcvData.get(0).streamId);
            assertEquals("echo:hello-message-mode", clientRcvData.get(0).text);
            assertFalse(clientRcvData.get(0).fin);
            assertTrue(clientRcvData.get(0).bidi);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeMultipleStreamIsolationAndFin() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        List<ReceivedMessage> serverRcvData = new CopyOnWriteArrayList<>();

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> serverRcvData.add(ReceivedMessage.from((QuicMessage) data.getData())));
                }
            }, quicCfg);

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            }, quicCfg).get();

            long bidiStreamId = client.allocateBidiStreamId();
            long uniStreamId = client.allocateUniStreamId();

            client.sendData(QuicMessage.of(bidiStreamId, toBuffer("bidi-one"), false)).get();
            client.sendData(QuicMessage.of(uniStreamId, toBuffer("uni-two"), true)).get();

            waitFor(() -> serverRcvData.size() >= 2, 8000);

            assertEquals(2, serverRcvData.size());
            assertEquals(bidiStreamId, serverRcvData.get(0).streamId);
            assertEquals("bidi-one", serverRcvData.get(0).text);
            assertFalse(serverRcvData.get(0).fin);
            assertTrue(serverRcvData.get(0).bidi);

            assertEquals(uniStreamId, serverRcvData.get(1).streamId);
            assertEquals("uni-two", serverRcvData.get(1).text);
            assertTrue(serverRcvData.get(1).fin);
            assertTrue(serverRcvData.get(1).uni);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeSameStreamMultipleMessages() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        List<ReceivedMessage> serverRcvData = new CopyOnWriteArrayList<>();

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> serverRcvData.add(ReceivedMessage.from((QuicMessage) data.getData())));
                }
            }, quicCfg);

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            }, quicCfg).get();

            long streamId = client.allocateBidiStreamId();
            client.sendData(QuicMessage.of(streamId, toBuffer("msg-1"), false)).get();
            client.sendData(QuicMessage.of(streamId, toBuffer("msg-2"), false)).get();
            client.sendData(QuicMessage.of(streamId, toBuffer("msg-3"), true)).get();

            waitFor(() -> serverRcvData.size() >= 3, 8000);

            assertEquals(3, serverRcvData.size());
            assertEquals(streamId, serverRcvData.get(0).streamId);
            assertEquals(streamId, serverRcvData.get(1).streamId);
            assertEquals(streamId, serverRcvData.get(2).streamId);
            assertEquals("msg-1", serverRcvData.get(0).text);
            assertEquals("msg-2", serverRcvData.get(1).text);
            assertEquals("msg-3", serverRcvData.get(2).text);
            assertFalse(serverRcvData.get(0).fin);
            assertFalse(serverRcvData.get(1).fin);
            assertTrue(serverRcvData.get(2).fin);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeZeroLengthFin() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        List<ReceivedMessage> serverRcvData = new CopyOnWriteArrayList<>();

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> serverRcvData.add(ReceivedMessage.from((QuicMessage) data.getData())));
                }
            }, quicCfg);

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            }, quicCfg).get();

            long streamId = client.allocateUniStreamId();
            client.sendData(QuicMessage.of(streamId, ByteBuf.EMPTY, true)).get();

            waitFor(() -> !serverRcvData.isEmpty(), 8000);

            assertEquals(1, serverRcvData.size());
            assertEquals(streamId, serverRcvData.get(0).streamId);
            assertEquals("", serverRcvData.get(0).text);
            assertTrue(serverRcvData.get(0).fin);
            assertTrue(serverRcvData.get(0).uni);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeServerAllocatedStreamPush() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        List<ReceivedMessage> clientRcvData = new CopyOnWriteArrayList<>();
        AtomicReference<QuicChannel> serverConnRef = new AtomicReference<>();

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> {
                    });
                }
            }, quicCfg).onAccept(ch -> {
                if (ch instanceof QuicChannel) {
                    serverConnRef.compareAndSet(null, (QuicChannel) ch);
                }
            });

            neta.connectAsync(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> clientRcvData.add(ReceivedMessage.from((QuicMessage) data.getData())));
                }
            }, quicCfg).get();

            waitFor(() -> serverConnRef.get() != null, 5000);
            assertNotNull(serverConnRef.get());

            long serverPushStreamId = serverConnRef.get().allocateUniStreamId();
            serverConnRef.get().sendData(QuicMessage.of(serverPushStreamId, toBuffer("server-push"), true)).get();

            waitFor(() -> !clientRcvData.isEmpty(), 8000);

            assertEquals(1, clientRcvData.size());
            assertEquals(serverPushStreamId, clientRcvData.get(0).streamId);
            assertEquals("server-push", clientRcvData.get(0).text);
            assertTrue(clientRcvData.get(0).fin);
            assertTrue(clientRcvData.get(0).uni);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeSendFailureShouldRecycleMessage() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        try {
            neta.bind(address, ctx -> {
                if (ctx.getChannel() instanceof QuicChannel) {
                    ctx.getChannel().subscribe(data -> ((QuicMessage) data.getData()).release());
                }
            }, quicCfg);

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            }, quicCfg).get();

            ByteBuf failedBody = toBuffer("failed-send");
            QuicMessage failedMessage = QuicMessage.of(1, failedBody, false);

            try {
                client.sendData(failedMessage).get();
                fail("peer-initiated stream id should fail in QUIC message mode");
            } catch (Throwable e) {
                assertTrue(hasIllegalState(e));
            }

            waitFor(failedBody::isFree, 1000);
            assertTrue(failedBody.isFree());
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testMessageModeShouldDisableStreamChannels() throws Throwable {
        int port = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        QuicSoConfig quicCfg = quicConfig().setStreamMode(QuicChannelMode.CHANNEL);
        NetManager neta = new NetManager(globalConf());

        try {
            neta.bind(address, ctx -> {
            }, quicCfg);

            QuicChannel client = (QuicChannel) neta.connectAsync(address, ctx -> {
            }, quicCfg).get();

            assertEquals(QuicChannelMode.CHANNEL, client.getStreamMode());
            assertTrue(client.getConfig() instanceof QuicSoConfig);

            try {
                client.newBidiStream().get();
                fail("message mode should disable bidirectional stream channels");
            } catch (Throwable e) {
                assertTrue(hasIllegalState(e));
            }

            try {
                client.newUniStream().get();
                fail("message mode should disable unidirectional stream channels");
            } catch (Throwable e) {
                assertTrue(hasIllegalState(e));
            }
        } finally {
            neta.shutdown();
        }
    }

    private static boolean hasIllegalState(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalStateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ByteBuf toBuffer(String data) {
        return ByteBuf.wrap(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(ByteBuf byteBuf) {
        if (byteBuf == null || byteBuf == ByteBuf.EMPTY) {
            return "";
        }
        return new String(byteBuf.asByteArray(), StandardCharsets.UTF_8);
    }

    private static class ReceivedMessage {
        private final long    streamId;
        private final String  text;
        private final boolean fin;
        private final boolean bidi;
        private final boolean uni;

        private ReceivedMessage(long streamId, String text, boolean fin, boolean bidi, boolean uni) {
            this.streamId = streamId;
            this.text = text;
            this.fin = fin;
            this.bidi = bidi;
            this.uni = uni;
        }

        private static ReceivedMessage from(QuicMessage message) {
            try {
                return new ReceivedMessage(message.streamId(), decodeText(message.content()), message.isFin(), message.isBidi(), message.isUni());
            } finally {
                message.release();
            }
        }
    }
}
