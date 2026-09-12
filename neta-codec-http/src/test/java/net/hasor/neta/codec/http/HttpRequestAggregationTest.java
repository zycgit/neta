/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;

public class HttpRequestAggregationTest extends AbstractHttpTest {
    @Test
    public void testRequestAggregatorBuildsFullHttpRequest() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = receiveAndIntBound(pipe,//
                    ascii("POST /request-only HTTP/1.1\r\n"),//
                    ascii("Host: example.com\r\n"),//
                    ascii("Content-Length: 9\r\n"),//
                    ascii("\r\n"),//
                    ascii("Wikipedia"));

            FullHttpRequest fullRequest = (FullHttpRequest) messages.get(0);
            assertEquals("/request-only", fullRequest.uri());
            assertEquals("Wikipedia", text(fullRequest.content()));
            assertEquals("example.com", fullRequest.getString(HttpHeaderNames.HOST));
        });
    }

    @Test
    public void testAggregatorMergesInitialHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = receiveAndIntBound(pipe,//
                    ascii("POST /upload HTTP/1.1\r\n"),//
                    ascii("Host: example.com\r\n"),//
                    ascii("Transfer-Encoding: chunked\r\n"),//
                    ascii("\r\n"),//
                    ascii("4\r\nWiki\r\n"),//
                    ascii("0\r\nX-Trail: done\r\n\r\n"));

            FullHttpRequest fullRequest = (FullHttpRequest) messages.get(0);
            assertEquals("example.com", fullRequest.getString(HttpHeaderNames.HOST));
            assertEquals("done", fullRequest.getString("X-Trail"));
            assertEquals("4", fullRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(fullRequest.containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("Wiki", text(fullRequest.content()));
        });
    }

    @Test
    public void testRequestAggregatorTransparentModeWrapsInboundByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
            }, VrtSoConfig.asServer());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("raw-request-agg-frame"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);
            assertEquals("raw-request-agg-frame", text(((HttpByteBuf) res.get(0)).content()));
        });
    }

    @Test
    public void testRequestAggregatorDisableTransparentModeResumesHttpAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
            }, VrtSoConfig.asServer());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertFalse(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("POST /resume HTTP/1.1\r\nHost: example.com\r\nContent-Length: 4\r\n\r\nWiki"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof FullHttpRequest);

            FullHttpRequest request = (FullHttpRequest) res.get(0);
            assertEquals(HttpMethod.POST, request.method());
            assertEquals("/resume", request.uri());
            assertEquals(HttpVersion.HTTP_1_1, request.protocolVersion());
            assertEquals("example.com", request.getString(HttpHeaderNames.HOST));
            assertEquals("4", request.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", text(request.content()));
        });
    }

    @Test
    public void testFullHttpRequestConstructorTransfersContentOwnership() {
        ByteBuf body = ascii("Wiki");
        DefaultHttpContent content = new DefaultHttpContent(body);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"), new HttpHeaders[] { new DefaultHttpHeaders() }, content.transferContent());
        try {
            assertEquals(1, body.refCnt());

            content.release();

            assertEquals(1, body.refCnt());
            assertEquals("Wiki", text(request.content().retain()));
        } finally {
            request.release();
        }
        assertEquals(0, body.refCnt());
    }

    @Test
    public void testFullHttpRequestAppendTransfersContentOwnership() {
        ByteBuf body = ascii("Wiki");
        DefaultHttpContent content = new DefaultHttpContent(body);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        try {
            request.appendContent(content);
            assertEquals(1, body.refCnt());

            content.release();

            assertEquals(1, body.refCnt());
            assertEquals("Wiki", text(request.content().retain()));
        } finally {
            request.release();
        }
        assertEquals(0, body.refCnt());
    }

    @Test
    public void testFullHttpRequestMergesHeaderBlocksInternally() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("X-Head", "one");
        DefaultLastHttpHeaders trailers = new DefaultLastHttpHeaders();
        trailers.addHeader("X-Trail", "two");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/merge"), new HttpHeaders[] { headers, trailers }, ByteBuf.EMPTY);
        try {
            assertEquals("one", request.getString("X-Head"));
            assertEquals("two", request.getString("X-Trail"));
        } finally {
            request.release();
        }
    }
}
