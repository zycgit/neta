/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpDecoderInputBoundaryTest extends AbstractHttpTest {
    @Test
    public void fragmentedRequestLinesSurviveGrowthReuseAndAbort() throws Throwable {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            List<HttpObject> retained = new ArrayList<>();
            String[] targets = { "/first", "/" + "long".repeat(80), "/last" };
            try {
                for (String target : targets) {
                    byte[] wire = ("GET " + target + " HTTP/1.1\r\nX-Test: unchanged\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    for (int offset = 0; offset < wire.length; offset += 7) {
                        retained.addAll(receiveAndIntBound(pipe, input(wire, offset, Math.min(7, wire.length - offset), 0)));
                    }
                }
                int requests = 0;
                for (HttpObject output : retained) {
                    if (output instanceof HttpRequest) {
                        assertEquals(targets[requests++], ((HttpRequest) output).uri());
                    }
                }
                assertEquals(targets.length, requests);
                assertTrue(pipe.channelInboundErrors().isEmpty());
                assertTrue(receiveAndIntBound(pipe, ascii("GET /aborted")).isEmpty());
            } finally {
                free(retained);
            }
        });
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void requestMethodsPreserveRawTextAcrossPacketBoundaries() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            for (String method : new String[] { "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE", "CONNECT", "get", "X-Custom" }) {
                byte[] wire = (method + " /method HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                for (int split = 0; split <= wire.length; split++) {
                    List<HttpObject> out = new ArrayList<>();
                    try {
                        out.addAll(receiveAndIntBound(pipe, input(wire, 0, split, 0)));
                        out.addAll(receiveAndIntBound(pipe, input(wire, split, wire.length - split, 0)));
                        assertTrue(pipe.channelInboundErrors().isEmpty());
                        assertEquals(3, out.size());
                        DefaultHttpRequest request = (DefaultHttpRequest) out.get(0);
                        assertEquals(method, request.methodText());
                        assertSame(HttpVersion.HTTP_1_1, request.protocolVersion());
                        assertEquals(HttpMethod.valueOf(method), request.method());
                        assertEquals("/method", request.uri());
                    } finally {
                        free(out);
                    }
                }
            }
        });
    }

    @Test
    public void requestVersionMatchesAcrossOrdersBuffersAndPacketBoundaries() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            for (HttpVersion version : new HttpVersion[] { HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1, HttpVersion.HTTP_2_0, HttpVersion.HTTP_3_0 }) {
                for (String prefix : new String[] { "HTTP", "http", "HtTp" }) {
                    byte[] wire = ("GET / " + prefix + version.toString().substring(4) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    for (java.nio.ByteOrder order : new java.nio.ByteOrder[] { java.nio.ByteOrder.BIG_ENDIAN, java.nio.ByteOrder.LITTLE_ENDIAN }) {
                        for (int mode = 0; mode < 4; mode++) {
                            for (int split = 0; split <= wire.length; split++) {
                                List<HttpObject> out = new ArrayList<>();
                                try {
                                    ByteBuf first = input(wire, 0, split, mode, order);
                                    ByteBuf second = input(wire, split, wire.length - split, mode, order);
                                    out.addAll(receiveAndIntBound(pipe, first));
                                    out.addAll(receiveAndIntBound(pipe, second));
                                    assertTrue(pipe.channelInboundErrors().isEmpty());
                                    assertEquals(3, out.size());
                                    assertEquals(version, ((HttpRequest) out.get(0)).protocolVersion());
                                    assertTrue(out.get(2) instanceof LastHttpContent);
                                } finally {
                                    free(out);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    @Test
    public void requestFixedLengthAtEveryBoundary() throws Throwable {
        checkBoundaries(true, false);
    }

    @Test
    public void headerClassificationPreservesNamesAndFraming() throws Throwable {
        for (boolean request : new boolean[] { true, false }) {
            autoCloseNeta(neta -> {
                VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                    if (request) {
                        ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                    } else {
                        ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                    }
                }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
                for (boolean chunked : new boolean[] { false, true }) {
                    String line = request ? "POST / HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n";
                    String fields = "Xxxxxx: ignored\r\nXxxxxxxxxx: close\r\nXxxxxxxxxxxxxx: invalid\r\nXxxxxxxxxxxxxxxxx: chunked\r\n";
                    fields += "cOnNeCtIoN: keep-alive\r\ncOnTeNt-LeNgTh: 3\r\n";
                    if (chunked) {
                        fields += "tRaNsFeR-EnCoDiNg: chunked\r\nContent-Length: invalid\r\n";
                    }
                    String body = chunked ? "3\r\nabc\r\n0\r\n\r\n" : "abc";
                    List<HttpObject> out = receiveAndIntBound(pipe, ascii(line + fields + "\r\n" + body));
                    try {
                        assertTrue(pipe.channelInboundErrors().isEmpty());
                        StringBuilder content = new StringBuilder();
                        int ends = 0;
                        boolean sawHeader = false;
                        for (HttpObject item : out) {
                            if (item instanceof HttpHeaders && ((HttpHeaders) item).containsHeader("Xxxxxxxxxxxxxx")) {
                                HttpHeaders headers = (HttpHeaders) item;
                                assertEquals("invalid", headers.getString("Xxxxxxxxxxxxxx"));
                                assertEquals("3", headers.getString("content-length"));
                                sawHeader = true;
                            }
                            if (item instanceof HttpContent) {
                                ByteBuf buf = ((HttpContent) item).content();
                                content.append(buf.getString(0, buf.readableBytes(), StandardCharsets.US_ASCII));
                            }
                            if (item instanceof LastHttpContent) {
                                ends++;
                            }
                        }
                        assertTrue(sawHeader);
                        assertEquals("abc", content.toString());
                        assertEquals(1, ends);
                    } finally {
                        free(out);
                    }
                }
            });
        }
    }

    @Test
    public void requestChunkedAtEveryBoundary() throws Throwable {
        checkBoundaries(true, true);
    }

    @Test
    public void responseFixedLengthAtEveryBoundary() throws Throwable {
        checkBoundaries(false, false);
    }

    @Test
    public void responseChunkedAtEveryBoundary() throws Throwable {
        checkBoundaries(false, true);
    }

    private void checkBoundaries(boolean request, boolean chunked) throws Throwable {
        String firstLine = request ? "POST /first HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n";
        String next = request ? "GET /next HTTP/1.1\r\nContent-Length: 0\r\n\r\n" : "HTTP/1.1 204 No Content\r\n\r\n";
        String framing = chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 6\r\n";
        String body = chunked ? "3;ext=yes\r\nabc\r\n3\r\ndef\r\n0\r\nX-End: yes\r\n\r\n" : "abcdef";
        byte[] wire = ("\r\n" + firstLine + framing + "X-Empty:\r\nX-Test: stable\r\n\r\n" + body + next).getBytes(StandardCharsets.US_ASCII);
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                if (request) {
                    ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                } else {
                    ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                }
            }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
            for (int bufferMode = 0; bufferMode < 4; bufferMode++) {
                for (boolean queuedTogether : new boolean[] { false, true }) {
                    for (int split = 0; split <= wire.length; split++) {
                        List<HttpObject> out = new ArrayList<>();
                        try {
                            ByteBuf first = input(wire, 0, split, bufferMode);
                            ByteBuf second = input(wire, split, wire.length - split, bufferMode);
                            if (queuedTogether) {
                                out.addAll(receiveAndIntBound(pipe, first, ByteBuf.EMPTY, second));
                            } else {
                                out.addAll(receiveAndIntBound(pipe, first));
                                out.addAll(receiveAndIntBound(pipe, ByteBuf.EMPTY, second));
                            }
                            assertTrue("split=" + split, pipe.channelInboundErrors().isEmpty());
                            int messages = 0;
                            int ends = 0;
                            int trailers = 0;
                            StringBuilder payload = new StringBuilder();
                            boolean sawHeader = false;
                            for (HttpObject item : out) {
                                if (item instanceof HttpRequest) {
                                    assertEquals(messages == 0 ? "/first" : "/next", ((HttpRequest) item).uri());
                                    messages++;
                                } else if (item instanceof HttpResponse) {
                                    assertEquals(messages == 0 ? HttpStatus.OK : HttpStatus.NO_CONTENT, ((HttpResponse) item).status());
                                    messages++;
                                }
                                if (item instanceof HttpHeaders) {
                                    HttpHeaders headers = (HttpHeaders) item;
                                    if (headers.containsHeader("X-Test")) {
                                        assertEquals("stable", headers.getString("X-Test"));
                                        sawHeader = true;
                                    }
                                    if (headers.containsHeader("X-Empty")) {
                                        assertEquals("", headers.getString("X-Empty"));
                                    }
                                    if (item instanceof TrailerHttpHeaders) {
                                        assertEquals("yes", headers.getString("X-End"));
                                        trailers++;
                                    }
                                }
                                if (item instanceof HttpContent) {
                                    ByteBuf content = ((HttpContent) item).content();
                                    payload.append(content.getString(0, content.readableBytes(), StandardCharsets.US_ASCII));
                                }
                                if (item instanceof LastHttpContent) {
                                    ends++;
                                }
                            }
                            assertEquals(2, messages);
                            assertEquals(2, ends);
                            assertEquals(chunked ? 1 : 0, trailers);
                            assertTrue(sawHeader);
                            assertEquals("abcdef", payload.toString());
                        } finally {
                            free(out);
                        }
                    }
                }
            }
        });
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void requestBadHeaderReleasesEarlierEntries() throws Throwable {
        checkBadHeader(true);
    }

    @Test
    public void requestMethodTextKeepsItsOriginalSpelling() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            for (String method : new String[] { "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE", "CONNECT", "get", "CUSTOM" }) {
                List<HttpObject> out = receiveAndIntBound(pipe, ascii(method + " / HTTP/1.1\r\nContent-Length: 0\r\n\r\n"));
                try {
                    DefaultHttpRequest request = (DefaultHttpRequest) out.get(0);
                    assertEquals(method, request.methodText());
                    assertEquals(HttpMethod.valueOf(method), request.method());
                } finally {
                    free(out);
                }
            }
        });
    }

    @Test
    public void requestLineFieldsSurviveEverySplitAndLaterInput() throws Throwable {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            for (String method : new String[] { "GET", "get", "CUSTOM-METHOD" }) {
                String target = "/resource?q=%20&name=unchanged";
                byte[] wire = (method + " " + target + " HTTP/1.1\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                int lineEnd = method.length() + target.length() + 12;
                for (int mode = 0; mode < 4; mode++) {
                    for (int split = 1; split < lineEnd; split++) {
                        List<HttpObject> out = new ArrayList<>();
                        try {
                            out.addAll(receiveAndIntBound(pipe, input(wire, 0, split, mode)));
                            out.addAll(receiveAndIntBound(pipe, input(wire, split, wire.length - split, mode)));
                            List<HttpObject> following = receiveAndIntBound(pipe, ascii("GET /later HTTP/1.1\r\n\r\n"));
                            free(following);
                            assertTrue(pipe.channelInboundErrors().isEmpty());
                            DefaultHttpRequest decoded = (DefaultHttpRequest) out.get(0);
                            assertEquals(method, decoded.methodText());
                            assertEquals(target, decoded.uri());
                            assertEquals(HttpVersion.HTTP_1_1, decoded.protocolVersion());
                        } finally {
                            free(out);
                        }
                    }
                }
            }
        });
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    @Test
    public void responseBadHeaderReleasesEarlierEntries() throws Throwable {
        checkBadHeader(false);
    }

    private void checkBadHeader(boolean request) throws Throwable {
        long count = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
        long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                if (request) {
                    ctx.addLastDecoder("decoder", new HttpRequestDecoder());
                } else {
                    ctx.addLastDecoder("decoder", new HttpResponseDecoder());
                }
            }, request ? VrtSoConfig.asServer() : VrtSoConfig.asClient());
            String line = request ? "GET / HTTP/1.1\r\n" : "HTTP/1.1 200 OK\r\n";
            byte[] wire = (line + "X-Valid: kept\r\nContent-Length: invalid\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            List<HttpObject> out = receiveAndIntBound(pipe, input(wire, 0, wire.length, 3));
            try {
                assertFalse(pipe.channelInboundErrors().isEmpty());
                HttpContext state = pipe.channel().findProtoContext(HttpContext.class);
                assertNull(request ? state.req.headerEntries : state.resp.headerEntries);
            } finally {
                free(out);
            }
        });
        assertEquals(count, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
        assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
    }

    private static ByteBuf input(byte[] wire, int offset, int length, int bufferMode, java.nio.ByteOrder order) {
        if (bufferMode >= 2) {
            return input(wire, offset, length, bufferMode).order(order);
        }
        ByteBuffer buffer = bufferMode == 1 ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
        buffer.put(wire, offset, length).flip();
        return ByteBuf.wrap(buffer).order(order).asReadOnly();
    }

    private static ByteBuf input(byte[] wire, int offset, int length, int bufferMode) {
        if (bufferMode >= 2) {
            ByteBuf buffer = bufferMode == 2 ? ByteBufAllocator.DEFAULT.heapBuffer(length + 16, length + 16) : ByteBufAllocator.DEFAULT.directBuffer(length + 16, length + 16);
            buffer.writeBytes(wire, offset, length);
            buffer.markWriter();
            return buffer;
        }
        ByteBuffer buffer = bufferMode == 1 ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
        buffer.put(wire, offset, length).flip();
        return ByteBuf.wrap(buffer).asReadOnly();
    }
}
