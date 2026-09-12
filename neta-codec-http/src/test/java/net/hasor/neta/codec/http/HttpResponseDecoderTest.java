/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import java.util.List;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HttpResponseDecoderTest extends AbstractHttpTest {
    @Test
    public void testEmptyHeaderAndTrailerRetainTheirNames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder()), VrtSoConfig.asClient());
            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nX-Empty:\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer:\r\n\r\n"));
            try {
                assertEquals(4, messages.size());
                HttpHeaders headers = (HttpHeaders) messages.get(1);
                HttpHeaders trailers = (HttpHeaders) messages.get(2);
                assertTrue(headers.headerNames().contains("X-Empty"));
                assertEquals("", headers.getString("X-Empty"));
                assertTrue(trailers.headerNames().contains("X-Trailer"));
                assertEquals("", trailers.getString("X-Trailer"));
            } finally {
                free(messages);
            }
        });
    }

    @Test
    public void testResponseDecoderEmitsFixedLengthBody() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nWiki"));
            assertEquals(3, res.size());
            assertEquals(200, ((HttpResponse) res.get(0)).status().code());
            assertEquals("4", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", body((HttpContent) res.get(2)));
            assertEquals(HttpVersion.HTTP_1_1, pipe.channel().findProtoContext(HttpVersion.class));
            assertEquals(HttpScope.CONNECTION, pipe.channel().findProtoContext(HttpScope.class));
        });
    }

    @Test
    public void testResponseDecoderEmitsSeparatedHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nServer: demo\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: done\r\n\r\n"));
            assertEquals(6, messages.size());
            assertEquals(200, ((HttpResponse) messages.get(0)).status().code());
            assertEquals("chunked", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("demo", ((HttpHeaders) messages.get(1)).getString("Server"));
            assertEquals("Wiki", body((HttpContent) messages.get(2)));
            assertEquals("pedia", body((HttpContent) messages.get(3)));
            assertEquals("done", ((HttpHeaders) messages.get(4)).getString("X-Trail"));
        });
    }

    @Test
    public void testResponseDecoderParsesFragmentedStatusHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("HTTP/1.1 20"), ascii("0 OK\r\nTrans"), ascii("fer-Encoding: chu"), ascii("nked\r\nServer: demo\r"), ascii("\n\r\n4\r\nWiki\r\n0\r\nX-Trai"), ascii("l: done\r\n\r\n"));
            assertEquals(5, messages.size());
            assertEquals(HttpStatus.OK, ((HttpResponse) messages.get(0)).status());
            assertEquals("chunked", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("demo", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.SERVER));
            assertEquals("Wiki", body((HttpContent) messages.get(2)));
            assertEquals("done", ((HttpHeaders) messages.get(3)).getString("X-Trail"));
            assertTrue(messages.get(4) instanceof LastHttpContent);
        });
    }

    @Test
    public void testResponseDecoderDoesNotConsumePartialHeaderLine() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> batch1 = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nServer: demo\r\nX-Desc: hel"));
            assertEquals(2, batch1.size());
            assertEquals(HttpStatus.OK, ((HttpResponse) batch1.get(0)).status());
            assertEquals("demo", ((HttpHeaders) batch1.get(1)).getString(HttpHeaderNames.SERVER));

            List<HttpObject> batch2 = receiveAndIntBound(pipe, ascii("lo\r\nContent-Length: 0\r\n\r\n"));
            assertEquals(2, batch2.size());
            HttpHeaders headers = (HttpHeaders) batch2.get(0);
            assertEquals("hello", headers.getString("X-Desc"));
            assertEquals("0", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertTrue(batch2.get(1) instanceof LastHttpContent);
        });
    }

    @Test
    public void testResponseDecoderDoesNotConsumePartialHeaderLineWithoutCrLf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> batch1 = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nX-Desc: hello"));
            assertEquals(1, batch1.size());
            assertEquals(HttpStatus.OK, ((HttpResponse) batch1.get(0)).status());

            List<HttpObject> batch2 = receiveAndIntBound(pipe, ascii("\r\nContent-Length: 0\r\n\r\n"));
            assertEquals(2, batch2.size());
            HttpHeaders headers = (HttpHeaders) batch2.get(0);
            assertEquals("hello", headers.getString("X-Desc"));
            assertEquals("0", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertTrue(batch2.get(1) instanceof LastHttpContent);
        });
    }

    @Test
    public void testResponseDecoderPreservesOriginalHeaderNames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            List<HttpObject> batch = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nX-Custom-Header: demo\r\nContent-Length: 0\r\n\r\n"));
            HttpHeaders headers = (HttpHeaders) batch.get(1);
            assertTrue(headers.headerNames().contains("X-Custom-Header"));
            assertEquals("demo", headers.getString("x-custom-header"));
        });
    }

    @Test
    public void testResponseDecoderTransparentModeWrapsInboundByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true, 23));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.isTransparentMode());
            assertEquals(23, httpContext.transparentStreamId());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("raw-response-frame"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);
            assertEquals("raw-response-frame", text(((HttpByteBuf) res.get(0)).content()));
            assertEquals(23, res.get(0).streamId());
        });
    }

    @Test
    public void testResponseDecoderDisableTransparentModeResumesHttpParsing() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertFalse(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\nServer: demo\r\n\r\n{}"));
            assertEquals(3, res.size());
            assertTrue(res.get(0) instanceof HttpResponse);
            assertTrue(res.get(1) instanceof LastHttpHeaders);
            assertTrue(res.get(2) instanceof LastHttpContent);
            assertEquals(HttpStatus.ACCEPTED, ((HttpResponse) res.get(0)).status());
            assertEquals(HttpVersion.HTTP_1_1, ((HttpResponse) res.get(0)).protocolVersion());
            assertEquals("2", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("demo", ((HttpHeaders) res.get(1)).getString("Server"));
            assertEquals(2, ((HttpHeaders) res.get(1)).headerSize());
            assertEquals("{}", body((HttpContent) res.get(2)));
        });
    }
}
