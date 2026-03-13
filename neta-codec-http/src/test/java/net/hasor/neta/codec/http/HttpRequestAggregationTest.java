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

            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
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

            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
            pipe.channel().fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.disable());
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
}