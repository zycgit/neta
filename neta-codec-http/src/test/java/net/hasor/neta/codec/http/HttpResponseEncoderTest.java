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

public class HttpResponseEncoderTest extends AbstractHttpTest {
    @Test
    public void testResponseEncoderSupportsSeparatedHeadersAndTrailers() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of("Server", "demo"), Tuple.of("Transfer-Encoding", HttpHeaderValues.CHUNKED)),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    joinHeaders(DefaultTrailerHttpHeaders.class, Tuple.of("X-Trail", "done")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("HTTP/1.1 200 OK\r\nServer: demo\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\nX-Trail: done\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseFixedLength() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("Wiki"));
            response.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals(3, parts.size());
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\nWiki", text(parts));
        });
    }

    @Test
    public void testResponseEncoderSupportsFullHttpResponseChunked() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("Wiki"));
            response.addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals(6, parts.size());
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseFullObjectDelegatesAppendHeaders() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
            response.appendHeaders(joinHeaders(DefaultLastHttpHeaders.class, Tuple.of("X-Response", "resp")));

            List<ByteBuf> parts = sendAndOutBound(pipe, response);
            assertEquals("HTTP/1.1 200 OK\r\nX-Response: resp\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseEncoderStreamsChunkBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> outbound = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK),//
                    new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n4\r\nWiki\r\n0\r\n\r\n", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderStreamsFixedLengthBodyZeroCopy() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
            DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
            headers.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");
            DefaultHttpContent content = new DefaultHttpContent(ascii("Wiki"));

            List<ByteBuf> outbound = sendAndOutBound(pipe, response, headers, content, new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 4\r\n\r\nWiki", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderTransparentModePassesRawByteBuf() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.isTransparentMode());

            DefaultHttpByteBuf source = new DefaultHttpByteBuf(ascii("raw-response-outbound"));
            List<ByteBuf> outbound = sendAndOutBound(pipe, source);
            assertEquals("raw-response-outbound", text(outbound));
        });
    }

    @Test
    public void testResponseEncoderDisableTransparentModeResumesHttpEncoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            HttpContext httpContext = pipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(httpContext);
            assertTrue(httpContext.switchTransparentMode(true));
            assertTrue(httpContext.switchTransparentMode(false));
            assertFalse(httpContext.isTransparentMode());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.ACCEPTED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));

            assertEquals(3, parts.size());
            assertEquals("HTTP/1.1 202 Accepted\r\ncontent-type: application/json\r\ncontent-length: 2\r\n\r\n{}", text(parts));
        });
    }
}