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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpSceneTest extends AbstractHttpTest {
    @Test
    public void testRequestDecoderOnErrorResetsStateAndDoesNotClearException() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());

            // 1st bad
            List<HttpObject> broken = receiveAndIntBound(pipe, ascii("BROKEN-REQUEST\r\n"));
            assertTrue(broken.isEmpty());

            // 2st ok
            List<HttpObject> recovered = receiveAndIntBound(pipe, ascii("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n"));
            assertEquals(3, recovered.size());
            assertTrue(recovered.get(0) instanceof HttpRequest);
            assertEquals(HttpMethod.GET, ((HttpRequest) recovered.get(0)).method());
            assertEquals("/hello", ((HttpRequest) recovered.get(0)).uri());
            assertEquals("example.com", ((HttpHeaders) recovered.get(1)).getString(HttpHeaderNames.HOST));
        });
    }

    @Test
    public void testResponseDecoderOnErrorResetsStateAndAcceptsNextResponse() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());

            // 1st bad
            List<HttpObject> broken = receiveAndIntBound(pipe, ascii("BROKEN-RESPONSE\r\n"));
            assertTrue(broken.isEmpty());

            // 2st ok
            List<HttpObject> recovered = receiveAndIntBound(pipe, ascii("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nWiki"));
            assertEquals(3, recovered.size());
            assertTrue(recovered.get(0) instanceof HttpResponse);
            assertEquals(HttpStatus.OK, ((HttpResponse) recovered.get(0)).status());
            assertEquals("4", ((HttpHeaders) recovered.get(1)).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", body((HttpContent) recovered.get(2)));
        });
    }

    @Test
    public void testRequestAggregatorAggregatesBadChunkedRequest() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator());
            }, VrtSoConfig.asServer());

            List<HttpObject> res = receiveAndIntBound(pipe, ascii("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWikiX\r\n0\r\n\r\n"));
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof FullHttpRequest);
            FullHttpRequest request = (FullHttpRequest) res.get(0);
            assertTrue(request.isBad());
            assertEquals("invalid chunk delimiter", request.badReason());
            assertEquals(HttpMethod.POST, request.method());
            assertEquals("/upload", request.uri());
            assertEquals("Wiki", body(request));
        });
    }

    @Test
    public void testResponseAggregatorOnErrorIgnoresResponseDecodeFailures() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder(4096, 16, 8192));
                ctx.addLastDecoder("aggregator", new HttpResponseAggregator());
            }, VrtSoConfig.asClient());

            List<HttpObject> res = receiveAndIntBound(pipe, //
                    ascii("HTTP/1.1 200 OK\r\n"),//
                    ascii("X-Long: 12345678901234567890\r\n"), //
                    ascii("\r\n"));
            assertTrue(res.isEmpty());
        });
    }

    @Test
    public void testRequestEncoderPassesThroughBrokenObjectStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("req-encoder", new HttpRequestEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe, //
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"),//
                    new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("POST /upload HTTP/1.1\r\n0\r\nX-Trail: done\r\n4\r\nWiki\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testResponseEncoderPassesThroughBrokenObjectStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> parts = sendAndOutBound(pipe,//
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK),//
                    new DefaultTrailerHttpHeaders().addHeader("X-Trail", "done"),//
                    new DefaultHttpContent(ascii("Wiki")),//
                    new DefaultLastHttpContent(ByteBuf.EMPTY));
            assertEquals("HTTP/1.1 200 OK\r\n0\r\nX-Trail: done\r\n4\r\nWiki\r\n\r\n", text(parts));
        });
    }

    @Test
    public void testRequestAggregatorAutoRepliesContinueForSupportedExpectation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator(16));
            }, VrtSoConfig.asServer());

            // expect: 100-continue
            List<HttpObject> outbound = receiveAndOutBound(pipe, ascii("POST /upload HTTP/1.1\r\nHost: example.com\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n"));
            assertEquals(1, outbound.size());
            assertTrue(outbound.get(0) instanceof FullHttpResponse);
            FullHttpResponse response = (FullHttpResponse) outbound.get(0);
            assertEquals(HttpStatus.CONTINUE, response.status());
            assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
            assertEquals("0", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(response));

            //
            List<HttpObject> inbound = receiveAndIntBound(pipe, ascii("Wiki"));
            assertEquals(1, inbound.size());
            assertTrue(inbound.get(0) instanceof FullHttpRequest);
            FullHttpRequest request = (FullHttpRequest) inbound.get(0);
            assertEquals(HttpMethod.POST, request.method());
            assertEquals("/upload", request.uri());
            assertEquals("example.com", request.getString(HttpHeaderNames.HOST));
            assertEquals("4", request.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("Wiki", body(request));
        });
    }

    @Test
    public void testRequestAggregatorAutoRepliesExpectationFailedForUnsupportedExpectation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator(16));
            }, VrtSoConfig.asServer());

            //
            List<HttpObject> outbound = receiveAndOutBound(pipe, ascii("POST /upload HTTP/1.1\r\nHost: example.com\r\nExpect: custom-check\r\nContent-Length: 4\r\n\r\nWiki"));
            assertEquals(1, outbound.size());
            assertTrue(outbound.get(0) instanceof FullHttpResponse);
            FullHttpResponse response = (FullHttpResponse) outbound.get(0);
            assertEquals(HttpStatus.EXPECTATION_FAILED, response.status());
            assertEquals("0", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(response));

            List<HttpObject> inbound = receiveAndIntBound(pipe);
            assertTrue(inbound.isEmpty());
        });
    }

    @Test
    public void testRequestAggregatorAutoRepliesRequestEntityTooLargeWhenHeadersDeclareOversizeBody() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
                ctx.addLastDecoder("req-agg", new HttpRequestAggregator(2));
            }, VrtSoConfig.asServer());

            List<HttpObject> outbound = receiveAndOutBound(pipe, ascii("POST /upload HTTP/1.1\r\nHost: example.com\r\nContent-Length: 4\r\n\r\nWiki"));
            assertEquals(1, outbound.size());
            assertTrue(outbound.get(0) instanceof FullHttpResponse);
            FullHttpResponse response = (FullHttpResponse) outbound.get(0);
            assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
            assertEquals("0", response.getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("", body(response));

            List<HttpObject> inbound = receiveAndIntBound(pipe);
            assertTrue(inbound.isEmpty());
        });
    }
}