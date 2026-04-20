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

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import net.hasor.cobble.ref.Tuple;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;

public class HttpRoleDuplexeTest extends AbstractHttpTest {
    @Test
    public void testServerDuplexeDecodesInboundRequests() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("GET /server HTTP/1.1\r\nHost: example.com\r\n\r\n"));

            assertEquals(3, messages.size());
            assertTrue(messages.get(0) instanceof HttpRequest);
            assertEquals(HttpMethod.GET, ((HttpRequest) messages.get(0)).method());
            assertEquals("/server", ((HttpRequest) messages.get(0)).uri());
            assertEquals("example.com", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.HOST));
        });
    }

    @Test
    public void testServerDuplexeEncodesOutboundResponses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
            }, VrtSoConfig.asServer());

            List<ByteBuf> parts = sendAndOutBound(pipe, //
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED), //
                    joinHeaders(DefaultLastHttpHeaders.class, //
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "text/plain"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "7")), //
                    new DefaultLastHttpContent(ascii("created")));

            assertEquals("HTTP/1.1 201 Created\r\ncontent-type: text/plain\r\ncontent-length: 7\r\n\r\ncreated", text(parts));
        });
    }

    @Test
    public void testServerRoleDuplexeTransparentModeWrapsRawFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true, 31));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertTrue(serverContext.isTransparentMode());
            assertEquals(31, serverContext.transparentStreamId());

            List<HttpObject> serverInbound = receiveAndIntBound(serverPipe, ascii("server-raw-in"));
            assertEquals(1, serverInbound.size());
            assertTrue(serverInbound.get(0) instanceof HttpByteBuf);
            assertEquals("server-raw-in", text(((HttpByteBuf) serverInbound.get(0)).content()));
            assertEquals(31, serverInbound.get(0).streamId());

            DefaultHttpByteBuf serverSource = new DefaultHttpByteBuf(ascii("server-raw-out"));
            List<ByteBuf> serverOutbound = sendAndOutBound(serverPipe, serverSource);
            assertEquals(1, serverOutbound.size());
            assertEquals("server-raw-out", text(serverOutbound));
        });
    }

    @Test
    public void testServerRoleDuplexeDisableTransparentModeResumesRequestDecoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertFalse(serverContext.isTransparentMode());

            List<HttpObject> serverInbound = receiveAndIntBound(serverPipe, ascii("GET /server-resume HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            assertEquals(3, serverInbound.size());
            assertTrue(serverInbound.get(0) instanceof HttpRequest);
            assertEquals(HttpMethod.GET, ((HttpRequest) serverInbound.get(0)).method());
            assertEquals("/server-resume", ((HttpRequest) serverInbound.get(0)).uri());
            assertEquals("example.com", ((HttpHeaders) serverInbound.get(1)).getString(HttpHeaderNames.HOST));
            assertTrue(serverInbound.get(2) instanceof LastHttpContent);
        });
    }

    @Test
    public void testServerRoleDuplexeDisableTransparentModeResumesResponseEncoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertFalse(serverContext.isTransparentMode());

            List<ByteBuf> serverOutbound = sendAndOutBound(serverPipe, new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED), joinHeaders(DefaultLastHttpHeaders.class, Tuple.of(HttpHeaderNames.CONTENT_TYPE, "text/plain"), Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "7")), new DefaultLastHttpContent(ascii("created")));
            assertEquals(3, serverOutbound.size());
            assertEquals("HTTP/1.1 201 Created\r\ncontent-type: text/plain\r\ncontent-length: 7\r\n\r\ncreated", text(serverOutbound));
        });
    }

    //

    @Test
    public void testClientDuplexeDecodesInboundResponses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
            }, VrtSoConfig.asClient());

            List<HttpObject> messages = receiveAndIntBound(pipe, ascii("HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\n{}"));

            assertEquals(3, messages.size());
            assertTrue(messages.get(0) instanceof HttpResponse);
            assertEquals(HttpStatus.ACCEPTED, ((HttpResponse) messages.get(0)).status());
            assertEquals("2", ((HttpHeaders) messages.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("{}", body((HttpContent) messages.get(2)));
        });
    }

    @Test
    public void testClientDuplexeEncodesOutboundRequests() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/client"),//
                    joinHeaders(DefaultLastHttpHeaders.class, //
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")), //
                    new DefaultLastHttpContent(ascii("{}")));

            assertEquals("POST /client HTTP/1.1\r\ncontent-type: application/json\r\ncontent-length: 2\r\n\r\n{}", text(parts));
        });
    }

    @Test
    public void testClientRoleDuplexeTransparentModeWrapsRawFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertTrue(clientContext.isTransparentMode());

            List<HttpObject> clientInbound = receiveAndIntBound(clientPipe, ascii("client-raw-in"));
            assertEquals(1, clientInbound.size());
            assertTrue(clientInbound.get(0) instanceof HttpByteBuf);
            assertEquals("client-raw-in", text(((HttpByteBuf) clientInbound.get(0)).content()));

            DefaultHttpByteBuf clientSource = new DefaultHttpByteBuf(ascii("client-raw-out"));
            List<ByteBuf> clientOutbound = sendAndOutBound(clientPipe, clientSource);
            assertEquals(1, clientOutbound.size());
            assertEquals("client-raw-out", text(clientOutbound));
            assertNull(clientSource.content());
        });
    }

    @Test
    public void testClientRoleDuplexeDisableTransparentModeResumesResponseDecoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertFalse(clientContext.isTransparentMode());

            List<HttpObject> clientInbound = receiveAndIntBound(clientPipe, ascii("HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\n{}"));
            assertEquals(3, clientInbound.size());
            assertTrue(clientInbound.get(0) instanceof HttpResponse);
            assertEquals(HttpStatus.ACCEPTED, ((HttpResponse) clientInbound.get(0)).status());
            assertEquals("2", ((HttpHeaders) clientInbound.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("{}", body((HttpContent) clientInbound.get(2)));
        });
    }

    @Test
    public void testClientRoleDuplexeDisableTransparentModeResumesRequestEncoding() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertFalse(clientContext.isTransparentMode());

            List<ByteBuf> clientOutbound = sendAndOutBound(clientPipe, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/client-resume"), joinHeaders(DefaultLastHttpHeaders.class, Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"), Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")), new DefaultLastHttpContent(ascii("{}")));
            assertEquals(3, clientOutbound.size());
            assertEquals("POST /client-resume HTTP/1.1\r\ncontent-type: application/json\r\ncontent-length: 2\r\n\r\n{}", text(clientOutbound));
        });
    }
}