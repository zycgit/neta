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
package net.hasor.neta.codec.http.h2;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Http2ObjectDuplexeTest extends AbstractHttp2Test {
    @Test
    public void testClientMessageDuplexeDecodesInboundFramesAndEncodesOutboundObjects() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-message", new Http2ObjectDuplexe(false));
            }, VrtSoConfig.asClient());

            byte[] headerBlock = encodeHeaders(headers(HttpHeaderNames.PSEUDO_STATUS, "200", HttpHeaderNames.CONTENT_TYPE, "text/plain"));
            List<HttpObject> inbound = receiveAndIntBound(pipe,//
                    Http2Frame.headers(1, Http2Flags.END_HEADERS, headerBlock),//
                    Http2Frame.data(1, Http2Flags.END_STREAM, "done".getBytes(StandardCharsets.US_ASCII)));

            assertEquals(3, inbound.size());
            assertTrue(inbound.get(0) instanceof HttpResponse);
            assertTrue(inbound.get(1) instanceof LastHttpHeaders);
            assertTrue(inbound.get(2) instanceof LastHttpContent);
            assertEquals(1, inbound.get(0).streamId());
            List<Http2Frame> autoFrames = drainQueue(pipe.channelOutbound());
            assertEquals(4, autoFrames.size());
            assertEquals(Http2FrameType.PREFACE, autoFrames.get(0).type());
            assertEquals(Http2FrameType.SETTINGS, autoFrames.get(1).type());
            assertEquals(Http2FrameType.WINDOW_UPDATE, autoFrames.get(2).type());
            assertEquals(Http2FrameType.WINDOW_UPDATE, autoFrames.get(3).type());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/duplexe");
            request.addHeader(HttpHeaderNames.HOST, "example.com");

            List<Http2Frame> outbound = sendAndOutBound(pipe, request);
            assertEquals(1, outbound.size());
            assertEquals(Http2FrameType.HEADERS, outbound.get(0).type());
            assertEquals(1, outbound.get(0).streamId());
            HttpHeaders decoded = decodeHeaderBlock(outbound.get(0));
            assertEquals("GET", decoded.getString(HttpHeaderNames.PSEUDO_METHOD));
            assertEquals("/duplexe", decoded.getString(HttpHeaderNames.PSEUDO_PATH));
            assertTrue(Http2Flags.endStream(outbound.get(0).flags()));
        });
    }

    @Test
    public void testServerMessageDuplexeDoesNotEmitClientPrefaceOnFirstSend() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-message", new Http2ObjectDuplexe(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, Http2Frame.settings(Http2Flags.NONE, new byte[] { 0x00, 0x01, 0x00, 0x00, 0x10, 0x00 }));
            assertTrue(inbound.isEmpty());
            assertTrue(pipe.channelEvents().isEmpty());
            List<Http2Frame> autoFrames = drainQueue(pipe.channelOutbound());
            assertEquals(2, autoFrames.size());
            assertEquals(Http2FrameType.SETTINGS, autoFrames.get(0).type());
            assertEquals(Http2Flags.NONE, autoFrames.get(0).flags());
            assertEquals(Http2FrameType.SETTINGS, autoFrames.get(1).type());
            assertEquals(Http2Flags.ACK, autoFrames.get(1).flags());

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
            response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");

            response.streamId(3);
            List<Http2Frame> outbound = sendAndOutBound(pipe, response);
            assertEquals(1, outbound.size());
            assertEquals(Http2FrameType.HEADERS, outbound.get(0).type());
            assertEquals(3, outbound.get(0).streamId());
            assertTrue(Http2Flags.endStream(outbound.get(0).flags()));

            HttpHeaders decoded = decodeHeaderBlock(outbound.get(0));
            assertEquals("204", decoded.getString(HttpHeaderNames.PSEUDO_STATUS));
            assertEquals("text/plain", decoded.getString(HttpHeaderNames.CONTENT_TYPE));
        });
    }

    @Test
    public void testClientMessageDuplexeQueuesPrefaceBeforeAutoSettingsAck() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-message", new Http2ObjectDuplexe(false));
            }, VrtSoConfig.asClient());

            List<HttpObject> inbound = receiveAndIntBound(pipe, Http2Frame.settings(Http2Flags.NONE, new byte[] { 0x00, 0x01, 0x00, 0x00, 0x10, 0x00 }));
            assertTrue(inbound.isEmpty());
            List<Http2Frame> outbound = drainQueue(pipe.channelOutbound());
            assertEquals(3, outbound.size());
            assertEquals(Http2FrameType.PREFACE, outbound.get(0).type());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(1).type());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(2).type());
            assertEquals(Http2Flags.ACK, outbound.get(2).flags());
        });
    }
}
