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
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpResponseAggregationTest extends AbstractHttpTest {
    @Test
    public void testResponseAggregatorBuildsFullHttpResponse() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
                ctx.addLastDecoder("response-aggregator", new HttpResponseAggregator());
            }, VrtSoConfig.asClient());

            List<HttpObject> res = receiveAndIntBound(pipe,//
                    ascii("HTTP/1.1 200 OK\r\n"),//
                    ascii("Content-Type: text/plain\r\n"),//
                    ascii("Content-Length: 11\r\n"),//
                    ascii("\r\n"),//
                    ascii("hello world"));

            FullHttpResponse fullResponse = (FullHttpResponse) res.get(0);
            assertEquals(HttpStatus.OK, fullResponse.status());
            assertEquals("hello world", text(fullResponse.content()));
            assertEquals("text/plain", fullResponse.getString(HttpHeaderNames.CONTENT_TYPE));
        });
    }

    @Test
    public void testResponseAggregatorTransparentModeWrapsInboundByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
                ctx.addLastDecoder("response-aggregator", new HttpResponseAggregator());
            }, VrtSoConfig.asClient());

            pipe.channel().fireEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("raw-response-agg-frame"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);
            assertEquals("raw-response-agg-frame", text(((HttpByteBuf) res.get(0)).content()));
        });
    }

    @Test
    public void testResponseAggregatorDisableTransparentModeResumesHttpAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
                ctx.addLastDecoder("response-aggregator", new HttpResponseAggregator());
            }, VrtSoConfig.asClient());

            pipe.channel().fireEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
            pipe.channel().fireEvent(HttpThroughEvent.class, HttpThroughEvent.disable());
            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertFalse(httpContext.isTransparentMode());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof FullHttpResponse);

            FullHttpResponse response = (FullHttpResponse) res.get(0);
            assertEquals(HttpStatus.ACCEPTED, response.status());
            assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
            assertEquals("application/json", response.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("2", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("{}", text(response.content()));
        });
    }
}