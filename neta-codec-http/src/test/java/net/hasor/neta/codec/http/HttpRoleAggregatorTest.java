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
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpRoleAggregatorTest extends AbstractHttpTest {
    @Test
    public void testServerDuplexeAggregatorAggregatesInboundRequests() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = receiveAndIntBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/server"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    joinHeaders(DefaultTrailerHttpHeaders.class, Tuple.of("X-Trail", "done")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));

            FullHttpRequest fullRequest = (FullHttpRequest) messages.get(0);
            assertEquals("/server", fullRequest.uri());
            assertEquals("example.com", fullRequest.getString(HttpHeaderNames.HOST));
            assertEquals("done", fullRequest.getString("X-Trail"));
            assertEquals("4", fullRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", text(fullRequest.content()));
        });
    }

    @Test
    public void testServerDuplexeAggregatorAggregatesOutboundResponses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator());
            }, VrtSoConfig.asServer());

            List<HttpObject> messages = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "text/plain"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "7")),//
                    new DefaultLastHttpContent(ascii("created")));

            FullHttpResponse fullResponse = (FullHttpResponse) messages.get(0);
            assertEquals(HttpStatus.CREATED, fullResponse.status());
            assertEquals("text/plain", fullResponse.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("created", text(fullResponse.content()));
        });
    }

    @Test
    public void testServerRoleAggregatorTransparentModeWrapsRawFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertTrue(serverContext.isTransparentMode());

            // enable
            List<HttpObject> serverInbound = receiveAndIntBound(serverPipe, new DefaultHttpByteBuf(ascii("server-agg-in")));
            List<HttpObject> serverOutbound = sendAndOutBound(serverPipe, new DefaultHttpByteBuf(ascii("server-agg-out")));
            assertEquals(1, serverInbound.size());
            assertEquals(1, serverOutbound.size());
            assertTrue(serverInbound.get(0) instanceof HttpByteBuf);
            assertTrue(serverOutbound.get(0) instanceof HttpByteBuf);
            assertEquals("server-agg-in", text(((HttpByteBuf) serverInbound.get(0)).content()));
            assertEquals("server-agg-out", text(((HttpByteBuf) serverOutbound.get(0)).content()));
        });
    }

    @Test
    public void testServerRoleAggregatorDisableTransparentModeResumesRequestAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertFalse(serverContext.isTransparentMode());

            List<HttpObject> serverInbound = receiveAndIntBound(serverPipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/server-resume"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")),//
                    new DefaultLastHttpContent(ascii("Wiki")));

            assertEquals(1, serverInbound.size());
            FullHttpRequest serverRequest = (FullHttpRequest) serverInbound.get(0);
            assertEquals(HttpMethod.POST, serverRequest.method());
            assertEquals("/server-resume", serverRequest.uri());
            assertEquals("example.com", serverRequest.getString(HttpHeaderNames.HOST));
            assertEquals("4", serverRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", text(serverRequest.content()));
        });
    }

    @Test
    public void testServerRoleAggregatorDisableTransparentModeResumesResponseAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe serverPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator());
            }, VrtSoConfig.asServer());

            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            serverPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext serverContext = serverPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(serverContext);
            assertFalse(serverContext.isTransparentMode());

            List<HttpObject> serverOutbound = sendAndOutBound(serverPipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "text/plain"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "7")),//
                    new DefaultLastHttpContent(ascii("created")));
            assertEquals(1, serverOutbound.size());
            FullHttpResponse serverResponse = (FullHttpResponse) serverOutbound.get(0);
            assertEquals(HttpStatus.CREATED, serverResponse.status());
            assertEquals("text/plain", serverResponse.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("7", serverResponse.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("created", text(serverResponse.content()));
        });
    }

    //

    @Test
    public void testClientDuplexeAggregatorAggregatesInboundResponses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-agg", new HttpClientDuplexeAggregator());
            }, VrtSoConfig.asClient());

            List<HttpObject> messages = receiveAndIntBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.ACCEPTED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));

            FullHttpResponse fullResponse = (FullHttpResponse) messages.get(0);
            assertEquals(HttpStatus.ACCEPTED, fullResponse.status());
            assertEquals("application/json", fullResponse.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("{}", text(fullResponse.content()));
        });
    }

    @Test
    public void testClientDuplexeAggregatorAggregatesOutboundRequests() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-agg", new HttpClientDuplexeAggregator());
            }, VrtSoConfig.asClient());

            List<HttpObject> messages = sendAndOutBound(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/client"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));

            FullHttpRequest fullRequest = (FullHttpRequest) messages.get(0);
            assertEquals("/client", fullRequest.uri());
            assertEquals("application/json", fullRequest.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("{}", text(fullRequest.content()));
        });
    }

    @Test
    public void testClientRoleAggregatorTransparentModeWrapsRawFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-agg", new HttpClientDuplexeAggregator());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertTrue(clientContext.isTransparentMode());

            List<HttpObject> clientInbound = receiveAndIntBound(clientPipe, new DefaultHttpByteBuf(ascii("client-agg-in")));
            List<HttpObject> clientOutbound = sendAndOutBound(clientPipe, new DefaultHttpByteBuf(ascii("client-agg-out")));
            assertEquals(1, clientInbound.size());
            assertEquals(1, clientOutbound.size());
            assertTrue(clientInbound.get(0) instanceof HttpByteBuf);
            assertTrue(clientOutbound.get(0) instanceof HttpByteBuf);
            assertEquals("client-agg-in", text(((HttpByteBuf) clientInbound.get(0)).content()));
            assertEquals("client-agg-out", text(((HttpByteBuf) clientOutbound.get(0)).content()));
        });
    }

    @Test
    public void testClientRoleAggregatorDisableTransparentModeResumesRequestAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-agg", new HttpClientDuplexeAggregator());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertFalse(clientContext.isTransparentMode());

            List<HttpObject> clientOutbound = sendAndOutBound(clientPipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/client-resume"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));

            assertEquals(1, clientOutbound.size());
            FullHttpRequest clientRequest = (FullHttpRequest) clientOutbound.get(0);
            assertEquals(HttpMethod.POST, clientRequest.method());
            assertEquals("/client-resume", clientRequest.uri());
            assertEquals("application/json", clientRequest.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("2", clientRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("{}", text(clientRequest.content()));
        });
    }

    @Test
    public void testClientRoleAggregatorDisableTransparentModeResumesResponseAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe clientPipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-agg", new HttpClientDuplexeAggregator());
            }, VrtSoConfig.asClient());

            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(true));
            clientPipe.channel().fireEvent(HttpThroughEvent.class, new HttpThroughEvent(false));
            HttpContext clientContext = clientPipe.channel().findProtoContext(HttpContext.class);
            assertNotNull(clientContext);
            assertFalse(clientContext.isTransparentMode());

            List<HttpObject> clientInbound = receiveAndIntBound(clientPipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.ACCEPTED),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.CONTENT_TYPE, "application/json"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "2")),//
                    new DefaultLastHttpContent(ascii("{}")));
            assertEquals(1, clientInbound.size());
            FullHttpResponse clientResponse = (FullHttpResponse) clientInbound.get(0);
            assertEquals(HttpStatus.ACCEPTED, clientResponse.status());
            assertEquals("application/json", clientResponse.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("2", clientResponse.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("{}", text(clientResponse.content()));
        });
    }
}