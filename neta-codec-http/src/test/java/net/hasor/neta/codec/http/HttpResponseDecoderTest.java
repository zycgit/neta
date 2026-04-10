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
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpResponseDecoderTest extends AbstractHttpTest {
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