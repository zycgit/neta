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
import net.hasor.cobble.ref.Tuple;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpRequestEncoderTest extends AbstractHttpTest {
    @Test
    public void testRequestEncoderSupportsSeparatedHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"),//
                    new DefaultLastHttpHeaders()                                                 //
                            .addHeader("Host", "example.com")                        //
                            .addHeader("Transfer-Encoding", HttpHeaderValues.CHUNKED),     //
                    new DefaultHttpContent(ascii("Wiki")),                                 //
                    new DefaultTrailerHttpHeaders()                                              //
                            .addHeader("X-Trail", "done"),                           //
                    new DefaultLastHttpContent(ByteBuf.EMPTY));

            assertEquals("POST /upload HTTP/1.1\r\nHost: example.com\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\nX-Trail: done\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testRequestEncoderSupportsFullHttpRequestFixedLength() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", ascii("Wiki"));
            request.addHeader(HttpHeaderNames.HOST, "example.com");
            request.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

            List<ByteBuf> parts = sendAndOutBound(pipe, request);
            assertEquals(3, parts.size());
            assertEquals("POST /upload HTTP/1.1\r\nhost: example.com\r\ncontent-length: 4\r\n\r\nWiki", text(parts));
        });
    }

    @Test
    public void testRequestEncoderSupportsFullHttpRequestChunked() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", ascii("Wiki"));
            request.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

            List<ByteBuf> parts = sendAndOutBound(pipe, request);
            assertEquals(6, parts.size());
            assertEquals("POST /upload HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testRequestFullObjectDelegatesAppendHeaders() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            DefaultHttpHeaders extra = new DefaultHttpHeaders();
            extra.addHeader("X-Request", "req");

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.appendHeaders(extra);
            extra.release();

            List<ByteBuf> parts = sendAndOutBound(pipe, request);
            assertEquals("GET / HTTP/1.1\r\nX-Request: req\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testRequestEncoderStreamsFixedLengthBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            ByteBuf body = ascii("Wiki");
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
            DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
            headers.addHeader(HttpHeaderNames.HOST, "example.com");
            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");
            DefaultHttpContent content = new DefaultHttpContent(body);

            List<ByteBuf> outbound = sendAndOutBound(pipe, request, headers, content, new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("POST /upload HTTP/1.1\r\nhost: example.com\r\ncontent-length: 4\r\n\r\nWiki", text(outbound));
        });
    }

    @Test
    public void testRequestEncoderStreamsChunkBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> outbound = sendAndOutBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"), //
                    new DefaultLastHttpHeaders()//
                            .addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED), //
                    new DefaultHttpContent(ascii("Wiki")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("POST /upload HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(outbound));
        });
    }

    @Test
    public void testRequestEncoderTransparentModePassesRawByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.isTransparentMode());

            ByteBuf payload = ascii("raw-client-frame");
            DefaultHttpByteBuf source = new DefaultHttpByteBuf(payload);
            List<ByteBuf> outbound = sendAndOutBound(pipe, source);
            assertEquals("raw-client-frame", text(outbound));
        });
    }

    @Test
    public void testRequestEncoderDisableTransparentModeResumesHttpEncoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.switchTransparentMode(false));
            assertFalse(httpContext.isTransparentMode());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/resume"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")),//
                    new DefaultLastHttpContent(ascii("Wiki")));

            assertEquals(3, parts.size());
            assertEquals("POST /resume HTTP/1.1\r\nhost: example.com\r\ncontent-length: 4\r\n\r\nWiki", text(parts));
        });
    }
}