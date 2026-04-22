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
package net.hasor.neta.codec.http.h2;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpObject;

public class Http2EventFlowTest extends AbstractHttp2Test {
    private static ByteBuf wrapPingPayload() {
        return ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
    }

    @Test
    public void testClientPingEventProducesClientSidePongEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(false));
                ctx.addLast("h2-message", new Http2ObjectDuplex(false));
            }, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(true));
                ctx.addLast("h2-message", new Http2ObjectDuplex(true));
            }, VrtTransfer.direct());

            byte[] payload = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
            pipe.client().fireEvent(Http2PingEvent.class, new Http2PingEvent(1, ByteBuf.wrap(payload)));

            assertTrue("clientEvents=" + pipe.clientEvents() + ", serverEvents=" + pipe.serverEvents() + ", clientInboundErrors=" + pipe.clientInboundErrors() + ", clientOutboundErrors=" + pipe.clientOutboundErrors() + ", serverInboundErrors=" + pipe.serverInboundErrors() + ", serverOutboundErrors=" + pipe.serverOutboundErrors(), waitUntil(() -> findEvent(pipe.clientEvents(), Http2PongEvent.class) != null, 1000L));
            Http2PongEvent pongEvent = findEvent(pipe.clientEvents(), Http2PongEvent.class);
            assertNotNull(pongEvent);
            assertTrue(pongEvent.isRemote());
            try {
                byte[] actual = new byte[8];
                pongEvent.getData().getBytes(0, actual, 0, actual.length);
                assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, actual);
            } finally {
                pongEvent.release();
            }

            assertNull(findEvent(pipe.serverEvents(), Http2PongEvent.class));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());
        });
    }

    @Test
    public void testClientPingEventProducesOutboundFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(false));
                ctx.addLast("h2-message", new Http2ObjectDuplex(false));
            }, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            });

            pipe.client().fireEvent(Http2PingEvent.class, new Http2PingEvent(1, wrapPingPayload()));

            assertTrue("serverInbound=" + pipe.serverInbound() + ", serverInboundErrors=" + pipe.serverInboundErrors() + ", clientOutboundErrors=" + pipe.clientOutboundErrors(), waitUntil(() -> !pipe.serverInbound().isEmpty() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.serverInbound());
            assertEquals("outbound size=" + outbound.size(), 2, outbound.size());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(0).type());
            assertEquals(Http2FrameType.PING, outbound.get(1).type());
            assertEquals(Http2Flags.NONE, outbound.get(1).flags());
            assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, outbound.get(1).payload());
        });
    }

    @Test
    public void testDecoderTurnsPingAckIntoPongEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            byte[] payload = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.ping(Http2Flags.ACK, payload));
            assertTrue(out.isEmpty());
            assertEquals(1, pipe.channelEvents().size());

            Http2PongEvent pongEvent = findEvent(pipe.channelEvents(), Http2PongEvent.class);
            assertNotNull(pongEvent);
            assertTrue(pongEvent.isRemote());
            try {
                byte[] actual = new byte[8];
                pongEvent.getData().getBytes(0, actual, 0, actual.length);
                assertArrayEquals(payload, actual);
            } finally {
                pongEvent.release();
            }
        });
    }

    @Test
    public void testDecoderConsumesInboundPingWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.ping(Http2Flags.NONE, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
            assertTrue(out.isEmpty());
            assertTrue(pipe.channelEvents().isEmpty());
        });
    }

    @Test
    public void testPriorityEventProducesOutboundPriorityFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(false));
                ctx.addLast("h2-message", new Http2ObjectDuplex(false));
            }, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            });

            pipe.client().fireEvent(Http2PriorityEvent.class, new Http2PriorityEvent(3, 1, 16, false));

            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.serverInbound());
            assertEquals(2, outbound.size());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(0).type());
            assertEquals(Http2FrameType.PRIORITY, outbound.get(1).type());
            assertEquals(3, outbound.get(1).streamId());
        });
    }

    @Test
    public void testServerPushPromiseEventProducesOutboundFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(false));
            }, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(true));
                ctx.addLast("h2-message", new Http2ObjectDuplex(true));
            });

            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            headers.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
            headers.addHeader(HttpHeaderNames.PSEUDO_PATH, "/asset.css");
            headers.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            pipe.server().fireEvent(Http2PushPromiseEvent.class, new Http2PushPromiseEvent(1, 2, headers));

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() || !pipe.clientInboundErrors().isEmpty() || !pipe.serverOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.clientInbound());
            Http2Frame settingsFrame = findFrame(outbound, Http2FrameType.SETTINGS);
            Http2Frame pushPromiseFrame = findFrame(outbound, Http2FrameType.PUSH_PROMISE);
            assertNotNull(settingsFrame);
            assertNotNull(pushPromiseFrame);
            assertEquals(1, pushPromiseFrame.streamId());
        });
    }

    @Test
    public void testDecoderTurnsGoAwayIntoEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x02, 'b', 'y', 'e' };
            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.goaway(payload));
            assertTrue(out.isEmpty());
            assertEquals(1, pipe.channelEvents().size());

            Http2GoawayEvent goawayEvent = findEvent(pipe.channelEvents(), Http2GoawayEvent.class);
            assertNotNull(goawayEvent);
            assertEquals(3L, goawayEvent.lastAcceptedId());
            assertEquals(2L, goawayEvent.errorCode());
            assertTrue(goawayEvent.isRemote());
            assertArrayEquals("bye".getBytes(StandardCharsets.US_ASCII), goawayEvent.debugData());
        });
    }

    @Test
    public void testDecoderTurnsResetStreamIntoEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.rstStream(7, new byte[] { 0x00, 0x00, 0x00, 0x08 }));
            assertTrue(out.isEmpty());
            assertEquals(1, pipe.channelEvents().size());

            Http2ResetEvent resetEvent = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertEquals(7L, resetEvent.streamId());
            assertEquals(8L, resetEvent.errorCode());
            assertTrue(resetEvent.isRemote());
        });
    }

    @Test
    public void testServerStreamResetEventProducesOutboundRstStreamFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(false));
            }, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(true));
                ctx.addLast("h2-message", new Http2ObjectDuplex(true));
            });

            pipe.server().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(7, Http2ResetEvent.CANCEL));

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() || !pipe.clientInboundErrors().isEmpty() || !pipe.serverOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.clientInbound());
            Http2Frame rstStreamFrame = findFrame(outbound, Http2FrameType.RST_STREAM);
            assertNotNull(rstStreamFrame);
            assertEquals(7, rstStreamFrame.streamId());
            assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x08 }, rstStreamFrame.payload());
        });
    }

    @Test
    public void testServerGoAwayEventProducesOutboundGoawayFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(false));
            }, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(true));
                ctx.addLast("h2-message", new Http2ObjectDuplex(true));
            });

            pipe.server().fireEvent(Http2GoawayEvent.class, new Http2GoawayEvent(0, 3, Http2ErrorCode.PROTOCOL_ERROR, "bye".getBytes(StandardCharsets.US_ASCII)));

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() || !pipe.clientInboundErrors().isEmpty() || !pipe.serverOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.clientInbound());
            Http2Frame goAwayFrame = findFrame(outbound, Http2FrameType.GOAWAY);
            assertNotNull(goAwayFrame);
            assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 'b', 'y', 'e' }, goAwayFrame.payload());
        });
    }

    @Test
    public void testInvalidPriorityEventProducesLocalResetFrameAndEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(false));
                ctx.addLast("h2-message", new Http2ObjectDuplex(false));
            }, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            });

            pipe.client().fireEvent(Http2PriorityEvent.class, new Http2PriorityEvent(3, 3, 16, false));

            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());

            List<Http2Frame> outbound = drainQueue(pipe.serverInbound());
            Http2Frame rstStreamFrame = findFrame(outbound, Http2FrameType.RST_STREAM);
            assertNotNull(rstStreamFrame);
            assertEquals(3, rstStreamFrame.streamId());
            assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x01 }, rstStreamFrame.payload());

            Http2ResetEvent resetEvent = findEvent(pipe.clientEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertFalse(resetEvent.isRemote());
            assertEquals(3L, resetEvent.streamId());
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, resetEvent.errorCode());
            assertFalse(pipe.client().isClose());
        });
    }

    @Test
    public void testClientPushPromiseEventProducesLocalGoawayAndClose() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplex(false));
                ctx.addLast("h2-message", new Http2ObjectDuplex(false));
            }, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            });

            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            headers.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
            headers.addHeader(HttpHeaderNames.PSEUDO_PATH, "/invalid-push");
            headers.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            pipe.client().fireEvent(Http2PushPromiseEvent.class, new Http2PushPromiseEvent(1, 2, headers));

            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty() || pipe.client().isClose() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 1000L));
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.client().isClose());

            List<Http2Frame> outbound = drainQueue(pipe.serverInbound());
            Http2Frame goAwayFrame = findFrame(outbound, Http2FrameType.GOAWAY);
            assertNotNull(goAwayFrame);

            Http2GoawayEvent goawayEvent = findEvent(pipe.clientEvents(), Http2GoawayEvent.class);
            assertNotNull(goawayEvent);
            assertFalse(goawayEvent.isRemote());
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goawayEvent.errorCode());
            assertTrue(new String(goawayEvent.debugData(), StandardCharsets.UTF_8).contains("must not send PUSH_PROMISE"));
        });
    }
}