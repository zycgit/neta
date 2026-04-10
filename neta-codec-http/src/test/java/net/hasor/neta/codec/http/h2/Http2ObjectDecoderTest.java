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
import java.util.Arrays;
import java.util.List;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2ObjectDecoderTest extends AbstractHttp2Test {
    private static class ClearFlagExceptionHolder implements ProtoExceptionHolder {
        private boolean cleared;

        @Override
        public void clear() {
            this.cleared = true;
        }
    }

    //

    @Test
    public void testDecoderDoesNotHandleGenericHttpProtocolException() {
        Http2ObjectDecoder decoder = new Http2ObjectDecoder(true);
        ClearFlagExceptionHolder holder = new ClearFlagExceptionHolder();

        ProtoStatus status = decoder.onError(null, new HttpProtocolStateException("generic-http-error"), holder);

        assertEquals(ProtoStatus.Next, status);
        assertFalse(holder.cleared);
    }

    //

    @Test
    public void testDecoderEmitsHttpObjectsAcrossContinuationFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            byte[] headerBlock = encodeHeaders(headers(         //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/decode",     //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            int split = headerBlock.length / 2;

            List<HttpObject> out = receiveAndIntBound(pipe,//
                    Http2Frame.headers(3, Http2Flags.NONE, Arrays.copyOfRange(headerBlock, 0, split)),//
                    Http2Frame.continuation(3, Http2Flags.END_HEADERS, Arrays.copyOfRange(headerBlock, split, headerBlock.length)),//
                    Http2Frame.data(3, Http2Flags.END_STREAM, "done".getBytes(StandardCharsets.US_ASCII)));

            assertEquals(3, out.size());
            HttpRequest request = (HttpRequest) out.get(0);
            LastHttpHeaders headersMessage = (LastHttpHeaders) out.get(1);
            LastHttpContent dataMessage = (LastHttpContent) out.get(2);

            assertEquals(3, request.streamId());
            assertEquals(HttpMethod.GET, request.method());
            assertEquals("/decode", request.uri());
            assertEquals("example.com", headersMessage.getString(HttpHeaderNames.HOST));
            assertEquals("done", dataMessage.content().readString(dataMessage.content().readableBytes(), StandardCharsets.US_ASCII));
            assertEquals(HttpVersion.HTTP_2_0, pipe.channel().findProtoContext(HttpVersion.class));
            assertEquals(HttpScope.STREAM, pipe.channel().findProtoContext(HttpScope.class));
        });
    }

    @Test
    public void testDecoderConsumesSettingsInternally() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            Http2Frame settings = Http2Frame.settings(Http2Flags.NONE, new byte[] { 0x00, 0x01, 0x00, 0x00, 0x10, 0x00 });
            List<HttpObject> out = receiveAndIntBound(pipe, settings);
            assertTrue(out.isEmpty());
            assertTrue(pipe.channelEvents().isEmpty());
        });
    }

    @Test
    public void testDecoderTurnsPriorityIntoEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, priorityFrame(3, 1, 16, true));
            assertTrue(out.isEmpty());

            Http2PriorityEvent event = findEvent(pipe.channelEvents(), Http2PriorityEvent.class);
            assertNotNull(event);
            assertEquals(3L, event.streamId());
            assertEquals(1, event.streamDependency());
            assertEquals(16, event.weight());
            assertTrue(event.exclusive());
        });
    }

    @Test
    public void testDecoderTurnsPushPromiseIntoEventAcrossContinuationFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(false));
            }, VrtSoConfig.asClient());

            HttpHeaders header1 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/root",       //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            receiveAndIntBound(pipe, Http2Frame.headers(1, Http2Flags.END_HEADERS, encodeHeaders(header1)));

            HttpHeaders header2 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/asset.js",   //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<Http2Frame> pushFrames = pushPromiseFrames(1, 2, header2, 4096, 16);
            List<HttpObject> out = receiveAndIntBound(pipe, pushFrames.toArray());
            assertTrue(out.isEmpty());

            Http2PushPromiseEvent event = findEvent(pipe.channelEvents(), Http2PushPromiseEvent.class);
            assertNotNull(event);
            assertEquals(1L, event.streamId());
            assertEquals(2, event.promisedStreamId());
            assertEquals("GET", event.headers().getString(HttpHeaderNames.PSEUDO_METHOD));
            assertEquals("/asset.js", event.headers().getString(HttpHeaderNames.PSEUDO_PATH));
        });
    }

    @Test
    public void testDecoderRejectsPushPromiseWithOddPromisedStreamId() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(false));
            }, VrtSoConfig.asClient());

            HttpHeaders header1 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/root",       //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            receiveAndIntBound(pipe, Http2Frame.headers(1, Http2Flags.END_HEADERS, encodeHeaders(header1)));

            HttpHeaders header2 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/asset.js",   //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<Http2Frame> pushFrames = pushPromiseFrames(1, 3, header2, 4096, 16384);
            List<HttpObject> out = receiveAndIntBound(pipe, pushFrames.toArray());
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 1, Http2ErrorCode.PROTOCOL_ERROR, "server-initiated");
        });
    }

    @Test
    public void testDecoderRejectsPushPromiseWithNonIncreasingPromisedStreamId() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(false));
            }, VrtSoConfig.asClient());

            HttpHeaders header1 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/root",       //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            receiveAndIntBound(pipe, Http2Frame.headers(1, Http2Flags.END_HEADERS, encodeHeaders(header1)));

            HttpHeaders header2 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/asset.js",   //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            receiveAndIntBound(pipe, pushPromiseFrames(1, 4, header2, 4096, 16384).toArray());

            List<HttpObject> out = receiveAndIntBound(pipe, pushPromiseFrames(1, 2, header2, 4096, 16384).toArray());
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 1, Http2ErrorCode.PROTOCOL_ERROR, "greater than prior remote stream ids");
        });
    }

    @Test
    public void testDecoderRejectsPushPromiseOnHalfClosedRemoteParent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(false));
            }, VrtSoConfig.asClient());

            HttpHeaders header1 = headers(HttpHeaderNames.PSEUDO_STATUS, "200");
            receiveAndIntBound(pipe, Http2Frame.headers(1, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, encodeHeaders(header1)));

            HttpHeaders header2 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/asset.js",   //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<HttpObject> out = receiveAndIntBound(pipe, pushPromiseFrames(1, 2, header2, 4096, 16384).toArray());
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 1, Http2ErrorCode.PROTOCOL_ERROR, "half-closed(local)");
        });
    }

    @Test
    public void testDecoderConsumesWindowUpdateInternally() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            Http2Frame frame = Http2Frame.windowUpdate(3, new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF });
            List<HttpObject> out = receiveAndIntBound(pipe, frame);
            assertTrue(out.isEmpty());

            assertTrue(pipe.channelEvents().isEmpty());
        });
    }

    @Test
    public void testDecoderRejectsSettingsNotMultipleOf6() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            Http2Frame frame = Http2Frame.settings(Http2Flags.NONE, new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 });
            List<HttpObject> out = receiveAndIntBound(pipe, frame);
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 0, Http2ErrorCode.FRAME_SIZE_ERROR, "multiple of 6");
        });
    }

    @Test
    public void testDecoderRejectsRstStreamWrongPayloadSize() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.rstStream(1, new byte[] { 0x01, 0x02 }));
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 0, Http2ErrorCode.FRAME_SIZE_ERROR, "4 bytes");
        });
    }

    @Test
    public void testDecoderEmitsBadLastContentWhenResetTerminatesOpenObjectStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            HttpHeaders header1 = headers(HttpHeaderNames.PSEUDO_METHOD, "POST", HttpHeaderNames.PSEUDO_PATH, "/reset", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<HttpObject> out1 = receiveAndIntBound(pipe, Http2Frame.headers(3, Http2Flags.END_HEADERS, encodeHeaders(header1)));
            try {
                assertEquals(2, out1.size());
                assertTrue(out1.get(0) instanceof HttpRequest);
                assertTrue(out1.get(1) instanceof LastHttpHeaders);
            } finally {
                free(out1);
            }

            List<HttpObject> out2 = receiveAndIntBound(pipe, Http2Frame.rstStream(3, new byte[] { 0x00, 0x00, 0x00, 0x08 }));
            try {
                assertEquals(1, out2.size());
                assertTrue(out2.get(0) instanceof LastHttpContent);
                assertTrue(out2.get(0).isBad());
                assertEquals("HTTP/2 stream reset: CANCEL", out2.get(0).badReason());
                assertEquals(3, out2.get(0).streamId());
            } finally {
                free(out2);
            }

            Http2ResetEvent event = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
            assertNotNull(event);
            assertEquals(3L, event.streamId());
            assertEquals(Http2ErrorCode.CANCEL, event.errorCode());
        });
    }

    @Test
    public void testDecoderDoesNotEmitSecondLastContentWhenResetArrivesAfterNormalEnd() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            HttpHeaders header1 = headers(HttpHeaderNames.PSEUDO_METHOD, "GET", HttpHeaderNames.PSEUDO_PATH, "/done", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<HttpObject> out1 = receiveAndIntBound(pipe, Http2Frame.headers(3, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, encodeHeaders(header1)));
            try {
                assertEquals(3, out1.size());
                assertTrue(out1.get(2) instanceof LastHttpContent);
                assertFalse(out1.get(2).isBad());
            } finally {
                free(out1);
            }

            List<HttpObject> out2 = receiveAndIntBound(pipe, Http2Frame.rstStream(3, new byte[] { 0x00, 0x00, 0x00, 0x08 }));
            assertTrue(out2.isEmpty());

            Http2ResetEvent event = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
            assertNotNull(event);
            assertEquals(3L, event.streamId());
            assertEquals(Http2ErrorCode.CANCEL, event.errorCode());
        });
    }

    @Test
    public void testDecoderRejectsWindowUpdateZeroIncrement() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.windowUpdate(0, new byte[] { 0x00, 0x00, 0x00, 0x00 }));
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 0, Http2ErrorCode.FLOW_CONTROL_ERROR, "non-zero");
        });
    }

    @Test
    public void testDecoderRejectsWindowUpdateZeroIncrementOnStreamAsStreamError() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.windowUpdate(3, new byte[] { 0x00, 0x00, 0x00, 0x00 }));
            assertTrue(out.isEmpty());

            assertStreamReset(pipe, 3, Http2ErrorCode.FLOW_CONTROL_ERROR);
        });
    }

    @Test
    public void testDecoderTurnsStreamErrorIntoOutboundRstStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.windowUpdate(3, new byte[] { 0x00, 0x00, 0x00, 0x00 }));
            assertTrue(out.isEmpty());

            assertStreamReset(pipe, 3, Http2ErrorCode.FLOW_CONTROL_ERROR);
        });
    }

    @Test
    public void testDecoderTurnsConnectionErrorIntoGoawayAndClosesChannel() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            HttpHeaders header1 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/ok",         //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
            List<HttpObject> accepted = receiveAndIntBound(pipe, Http2Frame.headers(1, Http2Flags.END_HEADERS, encodeHeaders(header1)));
            assertEquals(2, accepted.size());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.settings(Http2Flags.NONE, new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 }));
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 1, Http2ErrorCode.FRAME_SIZE_ERROR, "multiple of 6");
        });
    }

    @Test
    public void testDecoderRejectsGoawayTooShort() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.goaway(new byte[] { 0x00, 0x00, 0x00, 0x01 }));
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 0, Http2ErrorCode.FRAME_SIZE_ERROR, "GOAWAY");
        });
    }

    @Test
    public void testDecoderDoesNotValidateHeadersStreamEnvelope() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            HttpHeaders header1 = headers(                      //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/invalid");
            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.headers(0, Http2Flags.END_HEADERS, encodeHeaders(header1)));
            assertEquals(2, out.size());
            assertEquals(0, out.get(0).streamId());
            assertTrue(pipe.channelInboundErrors().isEmpty());
        });
    }

    @Test
    public void testDecoderRejectsSettingsAckWithPayload() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            List<HttpObject> out = receiveAndIntBound(pipe, Http2Frame.settings(Http2Flags.ACK, new byte[] { 0x00 }));
            assertTrue(out.isEmpty());
            assertGoAway(pipe, 0, Http2ErrorCode.FRAME_SIZE_ERROR, "empty payload");
        });
    }

    @Test
    public void testDecoderRejectsPushPromiseReceivedByServerEndpoint() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            Http2Frame frame = Http2Frame.pushPromise(1, Http2Flags.END_HEADERS, new byte[] { 0x00, 0x00, 0x00, 0x02 });
            List<HttpObject> out = receiveAndIntBound(pipe, frame);
            assertTrue(out.isEmpty());

            assertGoAway(pipe, 0, Http2ErrorCode.PROTOCOL_ERROR, "must not receive PUSH_PROMISE");
        });
    }

    @Test
    public void testDecoderDoesNotValidatePingStreamEnvelope() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            Http2Frame frame = new Http2Frame(Http2FrameType.PING, Http2Flags.NONE, 1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            List<HttpObject> out = receiveAndIntBound(pipe, frame);
            assertTrue(out.isEmpty());
            assertTrue(pipe.channelInboundErrors().isEmpty());
        });
    }

    @Test
    public void testDecoderDoesNotValidateInterleavedContinuationEnvelope() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-message-decoder", new Http2ObjectDecoder(true));
            }, VrtSoConfig.asServer());

            byte[] headerBlock = encodeHeaders(headers(         //
                    HttpHeaderNames.PSEUDO_METHOD, "GET",//
                    HttpHeaderNames.PSEUDO_PATH, "/fragmented", //
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));

            int split = headerBlock.length / 2;
            List<HttpObject> out = receiveAndIntBound(pipe,//
                    Http2Frame.headers(3, Http2Flags.NONE, Arrays.copyOfRange(headerBlock, 0, split)),//
                    Http2Frame.data(3, Http2Flags.NONE, "bad".getBytes(StandardCharsets.US_ASCII)));

            assertEquals(1, out.size());
            HttpContent dataMessage = (HttpContent) out.get(0);
            assertEquals("bad", dataMessage.content().readString(dataMessage.content().readableBytes(), StandardCharsets.US_ASCII));
            assertFalse(dataMessage instanceof LastHttpContent);
            assertTrue(pipe.channelInboundErrors().isEmpty());
        });
    }
}