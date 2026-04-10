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
package net.hasor.neta.codec.http.websocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.JZlib;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.websocket.extensions.DeflateFrameSupport;
import net.hasor.neta.codec.http.websocket.extensions.PerMessageDeflateSupport;
import net.hasor.neta.codec.http.websocket.extensions.XWebkitDeflateFrameSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketFrameExtensionTest extends AbstractWebSocketTest {
    private static String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DefaultFullHttpResponse newUpgradeResponse(String key) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.streamId(11);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key));
        response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
        return response;
    }

    private static WebSocketContextImpl negotiatedPerMessageDeflateContext(boolean server) {
        return negotiatedPerMessageDeflateContext(server, PerMessageDeflateSupport.EXTENSION_NAME);
    }

    private static WebSocketContextImpl negotiatedPerMessageDeflateContext(boolean server, String extHeader) {
        WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
        List<WebSocketExtensionResult> extResults = InnelUtils.parseExtensions(extHeader);
        List<WebSocketExtensionRuntime> runtimeExtensions = InnelUtils.resolveRuntimeExtensions(extResults, settings);
        return new WebSocketContextImpl(server, null, WebSocketVersion.V13.code(), "/chat", extResults, runtimeExtensions);
    }

    private static WebSocketContextImpl negotiatedDeflateFrameContext(boolean server) {
        WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).useDeflateFrameDefaults();
        List<WebSocketExtensionResult> extResults = InnelUtils.parseExtensions(DeflateFrameSupport.EXTENSION_NAME);
        List<WebSocketExtensionRuntime> runtimeExtensions = InnelUtils.resolveRuntimeExtensions(extResults, settings);
        return new WebSocketContextImpl(server, null, WebSocketVersion.V13.code(), "/chat", extResults, runtimeExtensions);
    }

    private static WebSocketContextImpl negotiatedXWebkitDeflateFrameContext(boolean server) {
        WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).useXWebkitDeflateFrameDefaults();
        List<WebSocketExtensionResult> extResults = InnelUtils.parseExtensions(XWebkitDeflateFrameSupport.EXTENSION_NAME);
        List<WebSocketExtensionRuntime> runtimeExtensions = InnelUtils.resolveRuntimeExtensions(extResults, settings);
        return new WebSocketContextImpl(server, null, WebSocketVersion.V13.code(), "/chat", extResults, runtimeExtensions);
    }

    private void performServerPerMessageDeflateHandshake(VirtualPipe pipe) throws Throwable {
        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
        request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
        receiveAndIntBound(pipe, request);
        drainQueue(pipe.channelOutbound());
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.channel()), 1000L));
    }

    private void performClientPerMessageDeflateHandshake(VirtualPipe pipe) throws Throwable {
        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
        request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
        pipe.channel().sendData(request, "ws-client").get();
        drainQueue(pipe.channelOutbound());
        receiveAndIntBound(pipe, newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY)));
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.channel()), 1000L));
    }

    private static byte[] rawDeflate(byte[] input) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            byte[] buffer = new byte[256];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    break;
                }
                output.write(buffer, 0, count);
            }
            byte[] compressed = output.toByteArray();
            if (compressed.length >= 4 && compressed[compressed.length - 4] == 0x00 && compressed[compressed.length - 3] == 0x00 && compressed[compressed.length - 2] == (byte) 0xFF && compressed[compressed.length - 1] == (byte) 0xFF) {
                byte[] trimmed = new byte[compressed.length - 4];
                System.arraycopy(compressed, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return compressed;
        } finally {
            deflater.end();
        }
    }

    private static byte[] rawPerMessageDeflate(byte[] input, int windowBits) {
        Deflater deflater = newPerMessageDeflater(windowBits);
        try {
            deflater.setInput(input);
            byte[] buffer = new byte[256];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            while (true) {
                deflater.setOutput(buffer);
                int status = deflater.deflate(JZlib.Z_SYNC_FLUSH);
                int count = buffer.length - deflater.avail_out;
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
                if (status != JZlib.Z_OK && status != JZlib.Z_BUF_ERROR && status != JZlib.Z_STREAM_END) {
                    throw new IllegalStateException("unexpected jzlib status: " + status);
                }
                if (deflater.avail_in == 0 && (deflater.avail_out > 0 || count == 0)) {
                    break;
                }
            }
            byte[] compressed = output.toByteArray();
            if (compressed.length >= 4 && compressed[compressed.length - 4] == 0x00 && compressed[compressed.length - 3] == 0x00 && compressed[compressed.length - 2] == (byte) 0xFF && compressed[compressed.length - 1] == (byte) 0xFF) {
                byte[] trimmed = new byte[compressed.length - 4];
                System.arraycopy(compressed, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return compressed;
        } finally {
            deflater.end();
        }
    }

    private static Deflater newPerMessageDeflater(int windowBits) {
        try {
            return new Deflater(JZlib.Z_DEFAULT_COMPRESSION, windowBits <= 8 ? 9 : windowBits, true);
        } catch (GZIPException e) {
            throw new IllegalStateException(e);
        }
    }

    private VirtualPipe extensionPipe(NetManager neta, boolean server, WebSocketContext context) throws Throwable {
        return openVirtualPipe(neta, ctx -> {
            ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
        }, server ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
    }

    @Test
    public void testFrameCreateWithRsvBits() {
        WebSocketFrame frame = WebSocketFrame.create(WebSocketOpcode.TEXT, true, true, false, true, false, null, ByteBuf.wrap("Hi".getBytes(StandardCharsets.UTF_8)));
        assertTrue(frame.isRsv1());
        assertFalse(frame.isRsv2());
        assertTrue(frame.isRsv3());
        assertEquals("Hi", text(frame));
    }

    @Test
    public void testFrameDecoderPreservesRsvBitsInDecodedFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, "Hello".getBytes(StandardCharsets.UTF_8));
            rfc6455Frame[0] = (byte) (rfc6455Frame[0] | 0x40 | 0x10);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertTrue(frame.isRsv1());
            assertFalse(frame.isRsv2());
            assertTrue(frame.isRsv3());
        });
    }

    @Test
    public void testFrameDecoderKeepsRsvBitsOnlyOnFirstSyntheticSlice() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13, 3));
            }, VrtSoConfig.asServer());

            byte[] payload = "ABCDEFG".getBytes(StandardCharsets.UTF_8);
            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, payload);
            rfc6455Frame[0] = (byte) (rfc6455Frame[0] | 0x40 | 0x20);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));

            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            WebSocketFrame third = (WebSocketFrame) result.get(2);
            assertTrue(first.isRsv1());
            assertTrue(first.isRsv2());
            assertFalse(first.isRsv3());
            assertFalse(second.isRsv1());
            assertFalse(second.isRsv2());
            assertFalse(second.isRsv3());
            assertFalse(third.isRsv1());
            assertFalse(third.isRsv2());
            assertFalse(third.isRsv3());
        });
    }

    @Test
    public void testFrameDecoderRejectsRsvBitsWithoutNegotiatedExtension() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            }, VrtSoConfig.asServer());

            byte[] broken = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "bad".getBytes(StandardCharsets.UTF_8));
            broken[0] = (byte) (broken[0] | 0x40);
            assertTrue(receiveAndIntBound(pipe, httpByteBuf(broken)).isEmpty());

            byte[] ok = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "ok".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> recovered = receiveAndIntBound(pipe, httpByteBuf(ok));
            assertEquals(1, recovered.size());
            assertEquals("ok", text((WebSocketFrame) recovered.get(0)));
        });
    }

    @Test
    public void testFrameDecoderInflatesPerMessageDeflatePayloadWhenNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketContext context = negotiatedPerMessageDeflateContext(true);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            }, VrtSoConfig.asServer());

            byte[] compressed = rawPerMessageDeflate("hello deflate".getBytes(StandardCharsets.UTF_8), 15);
            byte[] wire = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, compressed);
            wire[0] = (byte) (wire[0] | 0x40);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(wire));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertFalse(frame.isRsv1());
            assertEquals("hello deflate", text(frame));
        });
    }

    @Test
    public void testFrameDecoderRejectsControlFrameRsv1EvenWhenPerMessageDeflateNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketContext context = negotiatedPerMessageDeflateContext(true);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            }, VrtSoConfig.asServer());

            byte[] wire = buildRfc6455Frame(0x09, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "x".getBytes(StandardCharsets.UTF_8));
            wire[0] = (byte) (wire[0] | 0x40);

            assertTrue(receiveAndIntBound(pipe, httpByteBuf(wire)).isEmpty());
        });
    }

    @Test
    public void testFrameEncoderWritesRsvBitsToHeader() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            WebSocketFrame frame = WebSocketFrame.create(WebSocketOpcode.TEXT, true, true, false, true, false, null, ByteBuf.wrap("Hi".getBytes(StandardCharsets.UTF_8)));
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());

            assertEquals((byte) 0xD1, wire[0]);
            assertEquals((byte) 0x02, wire[1]);
        });
    }

    @Test
    public void testFrameEncoderDeflatesPayloadWhenPerMessageDeflateNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
                WebSocketContext context = negotiatedPerMessageDeflateContext(false);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
            }, VrtSoConfig.asClient());

            WebSocketFrame original = WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap("zip me".getBytes(StandardCharsets.UTF_8)));
            pipe.channel().sendData(original, "ws-ext").get();
            List<HttpObject> res = drainQueue(pipe.channelOutbound());
            WebSocketFrame encoded = (WebSocketFrame) res.get(0);

            assertTrue(encoded.isRsv1());
            assertEquals(WebSocketOpcode.TEXT, encoded.opcode());
            assertTrue(encoded.isFinalFragment());
            assertNotEquals("zip me", text(encoded));
        });
    }

    @Test
    public void testFrameDecoderInflatesFragmentedPerMessageDeflateMessageWhenNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketContext context = negotiatedPerMessageDeflateContext(true);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            }, VrtSoConfig.asServer());

            byte[] compressed = rawPerMessageDeflate("fragmented-deflate".getBytes(StandardCharsets.UTF_8), 15);
            int split = Math.max(1, compressed.length / 2);
            byte[] firstPayload = new byte[split];
            byte[] secondPayload = new byte[compressed.length - split];
            System.arraycopy(compressed, 0, firstPayload, 0, split);
            System.arraycopy(compressed, split, secondPayload, 0, secondPayload.length);

            byte[] firstWire = buildRfc6455Frame(0x01, false, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, firstPayload);
            firstWire[0] = (byte) (firstWire[0] | 0x40);
            byte[] secondWire = buildRfc6455Frame(0x00, true, true, new byte[] { 0x05, 0x06, 0x07, 0x08 }, secondPayload);

            List<HttpObject> result = new ArrayList<>();
            result.addAll(receiveAndIntBound(pipe, httpByteBuf(firstWire)));
            result.addAll(receiveAndIntBound(pipe, httpByteBuf(secondWire)));

            assertFalse(result.isEmpty());
            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = result.size() > 1 ? (WebSocketFrame) result.get(1) : null;
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isRsv1());
            String decoded = second == null ? text(first) : text(first) + text(second);
            assertEquals("fragmented-deflate", decoded);
        });
    }

    @Test
    public void testFrameEncoderDeflatesFragmentedPerMessageDeflateMessageWhenNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
                WebSocketContext context = negotiatedPerMessageDeflateContext(false);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
            }, VrtSoConfig.asClient());

            pipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, false, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap("frag-".getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            List<HttpObject> firstResult = drainQueue(pipe.channelOutbound());
            pipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.CONTINUATION, true, true, new byte[] { 0x05, 0x06, 0x07, 0x08 }, ByteBuf.wrap("encode".getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            List<HttpObject> secondResult = drainQueue(pipe.channelOutbound());

            WebSocketFrame firstFrame = (WebSocketFrame) firstResult.get(0);
            WebSocketFrame secondFrame = (WebSocketFrame) secondResult.get(0);
            assertEquals(WebSocketOpcode.TEXT, firstFrame.opcode());
            assertTrue(firstFrame.isRsv1());
            assertFalse(firstFrame.isFinalFragment());
            assertEquals(WebSocketOpcode.CONTINUATION, secondFrame.opcode());
            assertFalse(secondFrame.isRsv1());
            assertTrue(secondFrame.isFinalFragment());
        });
    }

    @Test
    public void testPerMessageDeflateRoundTripsMultipleMessagesWhenContextTakeoverEnabled() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = extensionPipe(neta, false, negotiatedPerMessageDeflateContext(false));
            VirtualPipe serverPipe = extensionPipe(neta, true, negotiatedPerMessageDeflateContext(true));

            String firstMessage = "reuse-window-message-reuse-window-message";
            String secondMessage = "reuse-window-message-reuse-window-message";

            clientPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(firstMessage.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame firstEncoded = (WebSocketFrame) drainQueue(clientPipe.channelOutbound()).get(0);
            clientPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(secondMessage.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame secondEncoded = (WebSocketFrame) drainQueue(clientPipe.channelOutbound()).get(0);

            assertTrue(firstEncoded.isRsv1());
            assertTrue(secondEncoded.isRsv1());
            assertTrue(secondEncoded.content().readableBytes() < firstEncoded.content().readableBytes());

            List<HttpObject> firstDecoded = receiveAndIntBound(serverPipe, firstEncoded);
            List<HttpObject> secondDecoded = receiveAndIntBound(serverPipe, secondEncoded);

            assertEquals(firstMessage, text((WebSocketFrame) firstDecoded.get(0)));
            assertEquals(secondMessage, text((WebSocketFrame) secondDecoded.get(0)));
        });
    }

    @Test
    public void testPerMessageDeflateResetsContextAcrossMessagesWhenNoContextTakeoverNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            String extHeader = "permessage-deflate; client_no_context_takeover; server_no_context_takeover";
            VirtualPipe clientPipe = extensionPipe(neta, false, negotiatedPerMessageDeflateContext(false, extHeader));
            VirtualPipe serverPipe = extensionPipe(neta, true, negotiatedPerMessageDeflateContext(true, extHeader));

            String payload = "reset-window-message-reset-window-message";

            clientPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(payload.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame firstEncoded = (WebSocketFrame) drainQueue(clientPipe.channelOutbound()).get(0);
            clientPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(payload.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame secondEncoded = (WebSocketFrame) drainQueue(clientPipe.channelOutbound()).get(0);

            assertTrue(firstEncoded.isRsv1());
            assertTrue(secondEncoded.isRsv1());
            assertEquals(firstEncoded.content().readableBytes(), secondEncoded.content().readableBytes());

            List<HttpObject> firstDecoded = receiveAndIntBound(serverPipe, firstEncoded);
            List<HttpObject> secondDecoded = receiveAndIntBound(serverPipe, secondEncoded);

            assertEquals(payload, text((WebSocketFrame) firstDecoded.get(0)));
            assertEquals(payload, text((WebSocketFrame) secondDecoded.get(0)));
        });
    }

    @Test
    public void testPerMessageDeflateRoundTripsClientWindowBitsEight() throws Throwable {
        autoCloseNeta(neta -> {
            String extHeader = "permessage-deflate; client_max_window_bits=8; server_max_window_bits=15";
            VirtualPipe clientPipe = extensionPipe(neta, false, negotiatedPerMessageDeflateContext(false, extHeader));
            VirtualPipe serverPipe = extensionPipe(neta, true, negotiatedPerMessageDeflateContext(true, extHeader));

            String payload = "small-window-client-payload-small-window-client-payload";
            clientPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(payload.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame encoded = (WebSocketFrame) drainQueue(clientPipe.channelOutbound()).get(0);

            assertTrue(encoded.isRsv1());
            List<HttpObject> decoded = receiveAndIntBound(serverPipe, encoded);
            assertEquals(payload, text((WebSocketFrame) decoded.get(0)));
        });
    }

    @Test
    public void testPerMessageDeflateRoundTripsServerWindowBitsNine() throws Throwable {
        autoCloseNeta(neta -> {
            String extHeader = "permessage-deflate; client_max_window_bits=15; server_max_window_bits=9";
            VirtualPipe serverPipe = extensionPipe(neta, true, negotiatedPerMessageDeflateContext(true, extHeader));
            VirtualPipe clientPipe = extensionPipe(neta, false, negotiatedPerMessageDeflateContext(false, extHeader));

            String payload = "small-window-server-payload-small-window-server-payload";
            serverPipe.channel().sendData(WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap(payload.getBytes(StandardCharsets.UTF_8))), "ws-ext").get();
            WebSocketFrame encoded = (WebSocketFrame) drainQueue(serverPipe.channelOutbound()).get(0);

            assertTrue(encoded.isRsv1());
            List<HttpObject> decoded = receiveAndIntBound(clientPipe, encoded);
            assertEquals(payload, text((WebSocketFrame) decoded.get(0)));
        });
    }

    @Test
    public void testFrameDecoderInflatesDeflateFramePayloadWhenNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketContext context = negotiatedDeflateFrameContext(true);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
            }, VrtSoConfig.asServer());

            byte[] compressed = rawDeflate("hello frame".getBytes(StandardCharsets.UTF_8));
            byte[] wire = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, compressed);
            wire[0] = (byte) (wire[0] | 0x40);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(wire));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertFalse(frame.isRsv1());
            assertEquals("hello frame", text(frame));
        });
    }

    @Test
    public void testFrameEncoderDeflatesPayloadWhenDeflateFrameNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
                WebSocketContext context = negotiatedDeflateFrameContext(false);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
            }, VrtSoConfig.asClient());

            WebSocketFrame original = WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap("zip frame".getBytes(StandardCharsets.UTF_8)));
            pipe.channel().sendData(original, "ws-ext").get();
            List<HttpObject> res = drainQueue(pipe.channelOutbound());
            WebSocketFrame encoded = (WebSocketFrame) res.get(0);

            assertTrue(encoded.isRsv1());
            assertEquals(WebSocketOpcode.TEXT, encoded.opcode());
            assertTrue(encoded.isFinalFragment());
            assertNotEquals("zip frame", text(encoded));
        });
    }

    @Test
    public void testFrameEncoderDeflatesPayloadWhenXWebkitDeflateFrameNegotiated() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
                WebSocketContext context = negotiatedXWebkitDeflateFrameContext(false);
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), context);
            }, VrtSoConfig.asClient());

            WebSocketFrame original = WebSocketFrame.create(WebSocketOpcode.TEXT, true, false, null, ByteBuf.wrap("webkit frame".getBytes(StandardCharsets.UTF_8)));
            pipe.channel().sendData(original, "ws-ext").get();
            List<HttpObject> res = drainQueue(pipe.channelOutbound());
            WebSocketFrame encoded = (WebSocketFrame) res.get(0);

            assertTrue(encoded.isRsv1());
            assertEquals(WebSocketOpcode.TEXT, encoded.opcode());
            assertTrue(encoded.isFinalFragment());
            assertNotEquals("webkit frame", text(encoded));
        });
    }
}