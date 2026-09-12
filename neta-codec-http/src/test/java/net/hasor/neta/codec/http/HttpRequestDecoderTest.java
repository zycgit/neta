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
import static org.junit.Assert.*;

public class HttpRequestDecoderTest extends AbstractHttpTest {
    @Test
    public void testConnectionAndExpectRemainAvailableAcrossInputBoundaries() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("req-decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            String wire = "GET / HTTP/1.1\r\ncOnNeCtIoN: keep-alive\r\nExPeCt: 100-continue\r\n\r\n";
            for (int split = 1; split < wire.length(); split++) {
                List<HttpObject> first = receiveAndIntBound(pipe, ascii(wire.substring(0, split)));
                List<HttpObject> second = receiveAndIntBound(pipe, ascii(wire.substring(split)));
                try {
                    int connections = 0;
                    int expectations = 0;
                    for (List<HttpObject> batch : java.util.Arrays.asList(first, second)) {
                        for (HttpObject message : batch) {
                            if (message instanceof HttpHeaders) {
                                HttpHeaders headers = (HttpHeaders) message;
                                if (headers.getString(HttpHeaderNames.CONNECTION) != null) {
                                    assertEquals("keep-alive", headers.getString(HttpHeaderNames.CONNECTION));
                                    assertTrue(headers.headerNames().contains("cOnNeCtIoN"));
                                    connections++;
                                }
                                if (headers.getString(HttpHeaderNames.EXPECT) != null) {
                                    assertEquals("100-continue", headers.getString(HttpHeaderNames.EXPECT));
                                    assertTrue(headers.headerNames().contains("ExPeCt"));
                                    expectations++;
                                }
                            }
                        }
                    }
                    assertEquals(1, connections);
                    assertEquals(1, expectations);
                } finally {
                    free(first);
                    free(second);
                }
            }
        });
    }

    @Test
    public void testEmptyHeaderAndTrailerRetainTheirNames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLastDecoder("req-decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("POST / HTTP/1.1\r\nX-Empty:\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer:\r\n\r\n"));
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
    public void testRequestDecoderSkipsLeadingEmptyLines() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("\r\n\r\nGET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            assertEquals(3, res.size());
            assertTrue(res.get(0) instanceof HttpRequest);
            assertTrue(res.get(1) instanceof LastHttpHeaders);
            assertTrue(res.get(2) instanceof LastHttpContent);
            assertEquals(HttpMethod.GET, ((HttpRequest) res.get(0)).method());
            assertEquals("/hello", ((HttpRequest) res.get(0)).uri());
            assertEquals("example.com", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.HOST));
            assertEquals(HttpVersion.HTTP_1_1, pipe.channel().findProtoContext(HttpVersion.class));
            assertEquals(HttpScope.CONNECTION, pipe.channel().findProtoContext(HttpScope.class));
        });
    }

    @Test
    public void testRequestDecoderDoesNotConsumeHalfInitialLine() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> res1 = receiveAndIntBound(pipe, ascii("GET /hel"));
            assertTrue(res1.isEmpty());

            List<HttpObject> res2 = receiveAndIntBound(pipe, ascii("lo HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            assertEquals(3, res2.size());
            assertEquals(HttpMethod.GET, ((HttpRequest) res2.get(0)).method());
            assertEquals("/hello", ((HttpRequest) res2.get(0)).uri());
            assertEquals("example.com", ((HttpHeaders) res2.get(1)).getString(HttpHeaderNames.HOST));
        });
    }

    @Test
    public void testRequestDecoderEmitsEmptyLastHeadersWhenHeaderSectionEndsImmediately() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\n\r\n"));
            assertEquals(3, res.size());
            assertEquals(0, ((HttpHeaders) res.get(1)).headerSize());
        });
    }

    @Test
    public void testRequestDecoderDoesNotConsumePartialHeaderLine() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch_1st = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\nUser-Agent: tes"));
            assertEquals(2, batch_1st.size());
            assertEquals("example.com", ((HttpHeaders) batch_1st.get(1)).getString(HttpHeaderNames.HOST));

            List<HttpObject> batch_2st = receiveAndIntBound(pipe, ascii("t\r\n\r\n"));
            assertEquals(2, batch_2st.size());
            HttpHeaders headers = (HttpHeaders) batch_2st.get(0);
            assertNull(headers.getString(HttpHeaderNames.HOST));
            assertEquals("test", headers.getString(HttpHeaderNames.USER_AGENT));
        });
    }

    @Test
    public void testRequestDecoderDoesNotConsumePartialHeaderLineWithoutCrLf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch_1st = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nX-Desc: hello"));
            assertEquals(1, batch_1st.size());
            assertEquals("/hello", ((HttpRequest) batch_1st.get(0)).uri());

            List<HttpObject> batch_2st = receiveAndIntBound(pipe, ascii("\r\nHost: example.com\r\n\r\n"));
            HttpHeaders lastHeaders = (HttpHeaders) batch_2st.get(0);
            assertEquals("hello", lastHeaders.getString("X-Desc"));
            assertEquals("example.com", lastHeaders.getString(HttpHeaderNames.HOST));
        });
    }

    @Test
    public void testRequestDecoderReturnsAllCompleteHeadersInCurrentBatch() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch_1st = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\nUser-Agent: tester\r\n"));
            assertEquals(2, batch_1st.size());
            assertEquals("example.com", ((HttpHeaders) batch_1st.get(1)).getString(HttpHeaderNames.HOST));
            assertEquals("tester", ((HttpHeaders) batch_1st.get(1)).getString(HttpHeaderNames.USER_AGENT));

            List<HttpObject> batch_2st = receiveAndIntBound(pipe, ascii("\r\n"));
            assertEquals(2, batch_2st.size());
            assertEquals(0, ((HttpHeaders) batch_2st.get(0)).headerSize());
        });
    }

    @Test
    public void testRequestDecoderPreservesOriginalHeaderNames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nX-Custom-Header: demo\r\n\r\n"));
            HttpHeaders headers = (HttpHeaders) batch.get(1);
            assertTrue(headers.headerNames().contains("X-Custom-Header"));
            assertEquals("demo", headers.getString("x-custom-header"));
        });
    }

    @Test
    public void testRequestDecoderEmitsSeparatedHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nHost: example.com\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trail: done\r\n\r\n"));
            assertEquals(6, res.size());
            assertEquals(HttpMethod.POST, ((HttpRequest) res.get(0)).method());
            assertEquals("/upload", ((HttpRequest) res.get(0)).uri());
            assertEquals("chunked", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("example.com", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.HOST));
            assertEquals("Wiki", body((HttpContent) res.get(2)));
            assertEquals("pedia", body((HttpContent) res.get(3)));
            assertEquals("done", ((HttpHeaders) res.get(4)).getString("X-Trail"));
        });
    }

    @Test
    public void testRequestDecoderChunkDataMayBeFragmentedAcrossPackets() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch_1st = receiveAndIntBound(pipe, ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWi"));
            assertEquals(3, batch_1st.size());
            assertEquals("Wi", body((HttpContent) batch_1st.get(2)));

            List<HttpObject> batch_2st = receiveAndIntBound(pipe, ascii("ki\r\n0\r\n\r\n"));
            assertEquals(2, batch_2st.size());
            assertEquals("ki", body((HttpContent) batch_2st.get(0)));
        });
    }

    @Test
    public void testRequestDecoderParsesFragmentedInitialHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> batch1 = receiveAndIntBound(pipe, ascii("PO"));
            assertTrue(batch1.isEmpty());

            List<HttpObject> batch2 = receiveAndIntBound(pipe, ascii("ST /upload HTTP/1.1\r\nTransfer-Encoding: chu"));
            assertEquals(1, batch2.size());
            assertTrue(batch2.get(0) instanceof HttpRequest);
            assertEquals(HttpMethod.POST, ((HttpRequest) batch2.get(0)).method());
            assertEquals("/upload", ((HttpRequest) batch2.get(0)).uri());

            List<HttpObject> batch3 = receiveAndIntBound(pipe, ascii("nked\r\nHost: example.com\r\n\r\n4\r"));
            assertEquals(1, batch3.size());
            assertTrue(batch3.get(0) instanceof LastHttpHeaders);
            assertEquals("chunked", ((HttpHeaders) batch3.get(0)).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals("example.com", ((HttpHeaders) batch3.get(0)).getString(HttpHeaderNames.HOST));

            List<HttpObject> batch4 = receiveAndIntBound(pipe, ascii("\nWiki\r\n0\r\nX-Trail: do"));
            assertEquals(1, batch4.size());
            assertTrue(batch4.get(0) instanceof HttpContent);
            assertEquals("Wiki", body((HttpContent) batch4.get(0)));

            List<HttpObject> batch5 = receiveAndIntBound(pipe, ascii("ne\r\n\r\n"));
            assertEquals(2, batch5.size());
            assertTrue(batch5.get(0) instanceof TrailerHttpHeaders);
            assertEquals("done", ((HttpHeaders) batch5.get(0)).getString("X-Trail"));
            assertTrue(batch5.get(1) instanceof LastHttpContent);
        });
    }

    @Test
    public void testRequestDecoderRejectsInvalidChunkDelimiter() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWikiX\r\n0\r\n\r\n"));
            assertEquals(4, messages.size());
            assertTrue(messages.get(0) instanceof HttpRequest);
            assertTrue(messages.get(0).isBad());
            assertEquals("invalid chunk delimiter", messages.get(0).badReason());
            assertTrue(messages.get(1) instanceof LastHttpHeaders);
            assertEquals("Wiki", body((HttpContent) messages.get(2)));
            assertTrue(messages.get(3) instanceof LastHttpContent);
        });
    }

    @Test
    public void testRequestDecoderTransparentModeWrapsInboundByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true, 17));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.isTransparentMode());
            assertEquals(17, httpContext.transparentStreamId());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("raw-ws-frame"));
            assertEquals(1, res.size());
            assertEquals("raw-ws-frame", text(((HttpByteBuf) res.get(0)).content()));
            assertEquals(17, res.get(0).streamId());
        });
    }

    @Test
    public void testRequestDecoderDisableTransparentModeResumesHttpParsing() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            pipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertFalse(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            assertEquals(3, res.size());
            assertTrue(res.get(0) instanceof HttpRequest);
            assertTrue(res.get(1) instanceof LastHttpHeaders);
            assertTrue(res.get(2) instanceof LastHttpContent);
            assertEquals(HttpMethod.GET, ((HttpRequest) res.get(0)).method());
            assertEquals("/hello", ((HttpRequest) res.get(0)).uri());
            assertEquals(HttpVersion.HTTP_1_1, ((HttpRequest) res.get(0)).protocolVersion());
            assertEquals("example.com", ((HttpHeaders) res.get(1)).getString(HttpHeaderNames.HOST));
            assertEquals(1, ((HttpHeaders) res.get(1)).headerSize());
            assertEquals("", body((HttpContent) res.get(2)));
        });
    }
}
