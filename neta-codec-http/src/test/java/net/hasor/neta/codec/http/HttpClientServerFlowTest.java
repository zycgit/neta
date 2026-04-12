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
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpClientServerFlowTest extends AbstractHttpTest {
    @Test
    public void testClientEncoderToServerDecoderCompletesNormalRequestResponseFlow() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
                ctx.addLastDecoder("resp-agg", new HttpResponseAggregator());
            }, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator(16));
            });

            // batch_1
            List<HttpObject> batch1 = clientSendRequestObjects(pipe, new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/flow"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")),//
                    new DefaultLastHttpContent(ascii("Wiki")));
            assertEquals(1, batch1.size());
            assertTrue(batch1.get(0) instanceof FullHttpRequest);
            FullHttpRequest request = (FullHttpRequest) batch1.get(0);
            assertEquals(HttpMethod.POST, request.method());
            assertEquals("/flow", request.uri());
            assertEquals("example.com", request.getString(HttpHeaderNames.HOST));
            assertEquals("4", request.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", body(request));

            // batch_2
            DefaultFullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED, ascii("done"));
            res.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            res.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");
            List<HttpObject> batch_2 = serverSendResponseObjects(pipe, res);
            assertEquals(1, batch_2.size());
            assertTrue(batch_2.get(0) instanceof FullHttpResponse);
            FullHttpResponse fullResponse = (FullHttpResponse) batch_2.get(0);
            assertEquals(HttpStatus.CREATED, fullResponse.status());
            assertEquals("text/plain", fullResponse.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("4", fullResponse.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("done", body(fullResponse));
        });
    }

    @Test
    public void testClientServerFlowHandlesExpectContinueBeforeSendingRequestBody() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
                ctx.addLastDecoder("resp-agg", new HttpResponseAggregator());
            }, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator(16));
            });

            List<HttpObject> beforeBody = clientSendRequestObjects(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/continue"),//
                    joinHeaders(DefaultLastHttpHeaders.class, //
                            Tuple.of(HttpHeaderNames.HOST, "example.com"), //
                            Tuple.of(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE), //
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")));
            assertTrue(beforeBody.isEmpty());

            List<HttpObject> provisionalResponses = drainQueue(pipe.clientInbound());
            assertEquals(1, provisionalResponses.size());
            assertTrue(provisionalResponses.get(0) instanceof FullHttpResponse);
            FullHttpResponse provisional = (FullHttpResponse) provisionalResponses.get(0);
            assertEquals(HttpStatus.CONTINUE, provisional.status());
            assertEquals("0", provisional.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(provisional));

            pipe.client().sendData(new DefaultLastHttpContent(ascii("Wiki"))).get();
            waitUntil(() -> !pipe.serverInbound().isEmpty() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 200L);
            List<HttpObject> requests = drainQueue(pipe.serverInbound());
            assertEquals(1, requests.size());
            assertTrue(requests.get(0) instanceof FullHttpRequest);
            FullHttpRequest request = (FullHttpRequest) requests.get(0);
            assertEquals(HttpMethod.POST, request.method());
            assertEquals("/continue", request.uri());
            assertEquals("Wiki", body(request));

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("ack!"));
            response.addHeader(HttpHeaderNames.CONTENT_LENGTH, "4");

            List<HttpObject> finalResponses = serverSendResponseObjects(pipe, response);
            assertEquals(1, finalResponses.size());
            assertTrue(finalResponses.get(0) instanceof FullHttpResponse);
            FullHttpResponse finalResponse = (FullHttpResponse) finalResponses.get(0);
            assertEquals(HttpStatus.OK, finalResponse.status());
            assertEquals("ack!", body(finalResponse));
        });
    }

    @Test
    public void testClientServerFlowAutoRepliesExpectationFailedForUnsupportedExpectation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
                ctx.addLastDecoder("resp-agg", new HttpResponseAggregator());
            }, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator(16));
            });

            List<HttpObject> requests = clientSendRequestObjects(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/expectation"),//
                    joinHeaders(DefaultLastHttpHeaders.class,//
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.EXPECT, "custom-check"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")),//
                    new DefaultLastHttpContent(ascii("Wiki")));
            assertTrue(requests.isEmpty());

            List<HttpObject> responses = drainQueue(pipe.clientInbound());
            assertEquals(1, responses.size());
            assertTrue(responses.get(0) instanceof FullHttpResponse);
            FullHttpResponse response = (FullHttpResponse) responses.get(0);
            assertEquals(HttpStatus.EXPECTATION_FAILED, response.status());
            assertEquals("0", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(response));
        });
    }

    @Test
    public void testClientServerFlowAutoRepliesRequestEntityTooLarge() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("client-http", new HttpClientDuplexe());
                ctx.addLastDecoder("resp-agg", new HttpResponseAggregator());
            }, ctx -> {
                ctx.addLast("server-http", new HttpServerDuplexe());
                ctx.addLast("server-agg", new HttpServerDuplexeAggregator(2));
            });

            List<HttpObject> requests = clientSendRequestObjects(pipe,//
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/oversize"),//
                    joinHeaders(DefaultLastHttpHeaders.class, //
                            Tuple.of(HttpHeaderNames.HOST, "example.com"),//
                            Tuple.of(HttpHeaderNames.CONTENT_LENGTH, "4")),//
                    new DefaultLastHttpContent(ascii("Wiki")));
            assertTrue(requests.isEmpty());

            List<HttpObject> responses = drainQueue(pipe.clientInbound());
            assertEquals(1, responses.size());
            assertTrue(responses.get(0) instanceof FullHttpResponse);
            FullHttpResponse response = (FullHttpResponse) responses.get(0);
            assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
            assertEquals("0", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(response));
        });
    }
}