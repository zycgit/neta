/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.HttpClientDuplexAggregator;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.h2.Http2FrameDuplex;
import net.hasor.neta.codec.http.h2.Http2ObjectDuplex;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeAuthorizer;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeEvent;
import net.hasor.nhttp.server.ServerConfig;

/**
 * Regression tests for the plain HTTP pipeline h2c branch arrangement.
 */
public class HttpPipelineH2cUpgradeTest {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String ENCODED_SETTINGS   = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 });
    private static final byte[] CLIENT_PREFACE     = ascii("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

    @Test
    public void h2cUpgrade_switchesToH2ApplicationBranch() throws Throwable {
        NetManager neta = new NetManager();
        try {
            ServerConfig config = ServerConfig.builder().serverName("test-server").http2(true).maxContentLength(MAX_CONTENT_LENGTH).build();

            RequestDispatchCallback callback = new RequestDispatchCallback() {
                @Override
                public void onHttpRequest(ProtoContext context, HttpRequest requestLine, HttpHeaders requestHeaders, BodyChannel bodyChannel, ResponseSink responseSink, NetChannel channel, boolean secure) {
                    try {
                        String bodyText = (requestLine.protocolVersion().majorVersion() >= 2 ? "h2:" : "h1:") + requestLine.uri();
                        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
                        DefaultHttpHeaders headers = new DefaultHttpHeaders();
                        headers.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                        headers.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
                        responseSink.sendHeaders(HttpStatus.OK.code(), headers, false);
                        responseSink.sendContent(ByteBuf.wrap(body), true);
                        responseSink.flush();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onWebSocketOpen(ProtoContext context, WebSocketHandshakeEvent event, NetChannel channel, boolean secure) {
                }

                @Override
                public void onConnectionOpen(NetChannel channel) {
                }

                @Override
                public void onConnectionClose(NetChannel channel) {
                }
            };

            WebSocketHandshakeAuthorizer wsAuthorizer = (request, handshakeCallback) -> handshakeCallback.accept();

            VirtualPipe serverPipe = openVirtualPipe(neta, PipelineFactory.createHttpPipeline(config, callback, false, wsAuthorizer), VrtSoConfig.asServer());
                VirtualPipe clientDecoder = openVirtualPipe(neta, ctx -> ProtoHelper.standard()
                    .nextDuplex("h2-frame", new Http2FrameDuplex(false))
                    .nextDuplex("h2-message", new Http2ObjectDuplex(false))
                    .nextDuplex("h2-client-aggregator", new HttpClientDuplexAggregator(MAX_CONTENT_LENGTH))
                    .config(ctx), VrtSoConfig.asClient());

            serverPipe.channel().receiveData(ByteBuf.wrap(ascii("GET /upgrade HTTP/1.1\r\n" + "Host: example.com\r\n" + "Connection: Upgrade, HTTP2-Settings\r\n" + "Upgrade: h2c\r\n" + "HTTP2-Settings: " + ENCODED_SETTINGS + "\r\n" + "\r\n")));

            assertTrue(waitUntil(() -> !serverPipe.channelOutbound().isEmpty() || !serverPipe.channelInboundErrors().isEmpty() || !serverPipe.channelOutboundErrors().isEmpty(), 1000L));
            assertTrue(serverPipe.channelInboundErrors().isEmpty());
            assertTrue(serverPipe.channelOutboundErrors().isEmpty());

            byte[] firstOutbound = drainRawBytes(serverPipe.channelOutbound());
            int headerEnd = findHeaderEnd(firstOutbound);
            assertTrue(headerEnd > 0);

            String responseHead = new String(firstOutbound, 0, headerEnd, StandardCharsets.US_ASCII);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"));
            assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

            clientDecoder.channel().receiveData(ByteBuf.wrap(Arrays.copyOfRange(firstOutbound, headerEnd, firstOutbound.length)));
            assertTrue(waitUntil(() -> !clientDecoder.channelInbound().isEmpty(), 1000L));

            List<Object> firstInbound = drainQueue(clientDecoder.channelInbound());
            try {
                FullHttpResponse upgraded = findFullHttpResponse(firstInbound, 1);
                assertNotNull(upgraded);
                assertEquals("h2:/upgrade", utf8(upgraded.content()));
            } finally {
                releaseHttpObjects(firstInbound);
            }
            releaseByteBufs(drainQueue(clientDecoder.channelOutbound()));

            DefaultFullHttpRequest afterRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/after");
            afterRequest.streamId(3);
            afterRequest.setHeader(HttpHeaderNames.HOST, "example.com");
            clientDecoder.channel().sendData(afterRequest).get();
            serverPipe.channel().receiveData(ByteBuf.wrap(concat(http2ClientPreamble(), drainRawBytes(clientDecoder.channelOutbound()))));

            assertTrue(waitUntil(() -> !serverPipe.channelOutbound().isEmpty() || !serverPipe.channelInboundErrors().isEmpty() || !serverPipe.channelOutboundErrors().isEmpty(), 1000L));
            assertTrue(serverPipe.channelInboundErrors().isEmpty());
            assertTrue(serverPipe.channelOutboundErrors().isEmpty());

            clientDecoder.channel().receiveData(ByteBuf.wrap(drainRawBytes(serverPipe.channelOutbound())));
            assertTrue(waitUntil(() -> !clientDecoder.channelInbound().isEmpty(), 1000L));

            List<Object> secondInbound = drainQueue(clientDecoder.channelInbound());
            try {
                FullHttpResponse response = findFullHttpResponse(secondInbound, 3);
                assertNotNull(response);
                assertEquals("h2:/after", utf8(response.content()));
            } finally {
                releaseHttpObjects(secondInbound);
            }
            releaseByteBufs(drainQueue(clientDecoder.channelOutbound()));
        } finally {
            neta.shutdown();
        }
    }

    private static VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer initializer, VrtSoConfig config) throws Throwable {
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, config);
        Queue<Object> inbound = new ConcurrentLinkedQueue<Object>();
        Queue<Object> outbound = new ConcurrentLinkedQueue<Object>();
        Queue<Throwable> inboundErrors = new ConcurrentLinkedQueue<Throwable>();
        Queue<Throwable> outboundErrors = new ConcurrentLinkedQueue<Throwable>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> {
            if (payload.getData() != null) {
                inbound.offer(payload.getData());
            }
        });
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, payload -> {
            Object data = payload.getData();
            if (data == null) {
                return;
            }
            if (data instanceof ByteBuf) {
                outbound.offer(((ByteBuf) data).retain());
            } else {
                outbound.offer(data);
            }
        });
        channel.subscribe(payload -> payload.isInbound() && !payload.isSuccess(), SubscribeMode.SYNC, payload -> inboundErrors.offer(payload.getError()));
        channel.subscribe(payload -> payload.isOutbound() && !payload.isSuccess(), SubscribeMode.SYNC, payload -> outboundErrors.offer(payload.getError()));
        return new VirtualPipe(channel, inbound, outbound, inboundErrors, outboundErrors);
    }

    private static byte[] ascii(String data) {
        return data.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] http2ClientPreamble() {
        return concat(CLIENT_PREFACE, new byte[] { 0, 0, 0, 4, 0, 0, 0, 0, 0 }, new byte[] { 0, 0, 0, 4, 1, 0, 0, 0, 0 });
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }

        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static int findHeaderEnd(byte[] bytes) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static boolean waitUntil(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return check.ok();
    }

    private static byte[] drainRawBytes(Queue<Object> queue) {
        List<byte[]> parts = new ArrayList<byte[]>();
        int total = 0;
        Object item;
        while ((item = queue.poll()) != null) {
            if (!(item instanceof ByteBuf)) {
                continue;
            }
            ByteBuf buf = (ByteBuf) item;
            try {
                byte[] part = new byte[buf.readableBytes()];
                buf.readBytes(part);
                parts.add(part);
                total += part.length;
            } finally {
                buf.release();
            }
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static <T> List<T> drainQueue(Queue<?> queue) {
        List<T> result = new ArrayList<T>();
        Object item;
        while ((item = queue.poll()) != null) {
            @SuppressWarnings("unchecked")
            T cast = (T) item;
            result.add(cast);
        }
        return result;
    }

    private static FullHttpResponse findFullHttpResponse(List<Object> messages, long streamId) {
        for (Object item : messages) {
            if (item instanceof FullHttpResponse && ((FullHttpResponse) item).streamId() == streamId) {
                return (FullHttpResponse) item;
            }
        }
        return null;
    }

    private static void releaseByteBufs(List<Object> buffers) {
        for (Object item : buffers) {
            if (item instanceof ByteBuf) {
                ((ByteBuf) item).release();
            }
        }
    }

    private static void releaseHttpObjects(List<Object> messages) {
        for (Object item : messages) {
            if (item instanceof HttpObject) {
                ((HttpObject) item).release();
            }
        }
    }

    private static String utf8(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static class VirtualPipe {
        private final VrtChannel       channel;
        private final Queue<Object>    channelInbound;
        private final Queue<Object>    channelOutbound;
        private final Queue<Throwable> channelInboundErrors;
        private final Queue<Throwable> channelOutboundErrors;

        private VirtualPipe(VrtChannel channel, Queue<Object> channelInbound, Queue<Object> channelOutbound, Queue<Throwable> channelInboundErrors, Queue<Throwable> channelOutboundErrors) {
            this.channel = channel;
            this.channelInbound = channelInbound;
            this.channelOutbound = channelOutbound;
            this.channelInboundErrors = channelInboundErrors;
            this.channelOutboundErrors = channelOutboundErrors;
        }

        private VrtChannel channel() {
            return this.channel;
        }

        private Queue<Object> channelInbound() {
            return this.channelInbound;
        }

        private Queue<Object> channelOutbound() {
            return this.channelOutbound;
        }

        private Queue<Throwable> channelInboundErrors() {
            return this.channelInboundErrors;
        }

        private Queue<Throwable> channelOutboundErrors() {
            return this.channelOutboundErrors;
        }
    }
}
