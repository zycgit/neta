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
package net.hasor.neta.codec.http;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.event.HttpThroughEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpRequestDecoderTest extends AbstractHttpTest {
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

            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("raw-ws-frame"));
            assertEquals(1, res.size());
            assertEquals("raw-ws-frame", text(((HttpByteBuf) res.get(0)).content()));
        });
    }

    @Test
    public void testRequestDecoderDisableTransparentModeResumesHttpParsing() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.disable());
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