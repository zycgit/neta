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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2ObjectEncoderTest extends AbstractHttp2Test {
    private static String buildLargeHeaderValue(int size) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) ('a' + (i % 26)));
        }
        return builder.toString();
    }

    private static class ClearFlagExceptionHolder implements ProtoExceptionHolder {
        private boolean cleared;

        @Override
        public void clear() {
            this.cleared = true;
        }
    }

    @Test
    public void testEncoderDoesNotHandleGenericHttpProtocolException() {
        Http2ObjectEncoder encoder = new Http2ObjectEncoder(false);
        ClearFlagExceptionHolder holder = new ClearFlagExceptionHolder();

        ProtoStatus status = encoder.onError(null, new HttpProtocolStateException("generic-http-error"), holder);

        assertEquals(ProtoStatus.Next, status);
        assertFalse(holder.cleared);
    }

    //
    @Test
    public void testEncoderAssignsClientStreamIdAndEncodesHeadersAndData() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-message-encoder", new Http2ObjectEncoder(false));
            }, VrtSoConfig.asClient());

            ByteBuf dataBuf = ByteBufAllocator.DEFAULT.buffer(16);
            dataBuf.writeString("hello-h2-message", StandardCharsets.US_ASCII);
            dataBuf.markWriter();

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/msg", dataBuf);
            request.addHeader(HttpHeaderNames.HOST, "example.com");
            request.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");

            List<Http2Frame> outbound = sendAndOutBound(pipe, request);

            assertEquals(4, outbound.size());
            assertEquals(Http2FrameType.PREFACE, outbound.get(0).type());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(1).type());
            assertEquals(Http2FrameType.HEADERS, outbound.get(2).type());
            assertEquals(Http2FrameType.DATA, outbound.get(3).type());

            Http2Frame headersFrame = outbound.get(2);
            Http2Frame dataFrame = outbound.get(3);
            assertEquals(1, headersFrame.streamId());
            assertEquals(1, dataFrame.streamId());
            assertFalse(Http2Flags.endStream(headersFrame.flags()));
            assertTrue(Http2Flags.endHeaders(headersFrame.flags()));
            assertTrue(Http2Flags.endStream(dataFrame.flags()));

            HttpHeaders decodedHeaders = decodeHeaderBlock(headersFrame);
            assertEquals("POST", decodedHeaders.getString(HttpHeaderNames.PSEUDO_METHOD));
            assertEquals("/msg", decodedHeaders.getString(HttpHeaderNames.PSEUDO_PATH));
            assertEquals("example.com", decodedHeaders.getString(HttpHeaderNames.PSEUDO_AUTHORITY));
            assertEquals("text/plain", decodedHeaders.getString(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("hello-h2-message", new String(dataFrame.payload(), dataFrame.payloadOffset(), dataFrame.payloadLength(), StandardCharsets.US_ASCII));
        });
    }

    @Test
    public void testEncoderPreservesRepeatedHeaderValues() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-message-encoder", new Http2ObjectEncoder(false));
            }, VrtSoConfig.asClient());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/multi");
            request.streamId(9);
            request.addHeader(HttpHeaderNames.ACCEPT, "text/html");
            request.addHeader(HttpHeaderNames.ACCEPT, "application/json");
            request.addHeader(HttpHeaderNames.ACCEPT, "text/plain");

            List<Http2Frame> outbound = sendAndOutBound(pipe, request);
            assertEquals(3, outbound.size());

            HttpHeaders decodedHeaders = decodeHeaderBlock(outbound.get(2));
            assertEquals(3, decodedHeaders.getValues(HttpHeaderNames.ACCEPT).size());
            assertEquals("text/html", decodedHeaders.getValues(HttpHeaderNames.ACCEPT).get(0));
            assertEquals("application/json", decodedHeaders.getValues(HttpHeaderNames.ACCEPT).get(1));
            assertEquals("text/plain", decodedHeaders.getValues(HttpHeaderNames.ACCEPT).get(2));
            assertTrue(Http2Flags.endStream(outbound.get(2).flags()));
        });
    }

    @Test
    public void testEncoderSplitsLargeDataByPeerMaxFrameSize() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                Http2DecoderContent decoderState = new Http2DecoderContent(false, h2Settings(8192));
                decoderState.applyRemoteSetting(Http2Settings.SETTINGS_MAX_FRAME_SIZE, 16384);
                ctx.context(Http2DecoderContent.class, decoderState);
                ctx.addLastEncoder("h2-message-encoder", new Http2ObjectEncoder(false));
            }, VrtSoConfig.asClient());

            byte[] largePayload = new byte[20000];
            for (int i = 0; i < largePayload.length; i++) {
                largePayload[i] = (byte) (i & 0xFF);
            }
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(largePayload.length);
            body.writeBytes(largePayload, 0, largePayload.length);
            body.markWriter();

            DefaultLastHttpContent last = new DefaultLastHttpContent(body);
            last.streamId(1);
            List<Http2Frame> outbound = sendAndOutBound(pipe, last);
            assertEquals(4, outbound.size());
            assertEquals(Http2FrameType.PREFACE, outbound.get(0).type());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(1).type());
            assertEquals(Http2FrameType.DATA, outbound.get(2).type());
            assertEquals(Http2FrameType.DATA, outbound.get(3).type());
            assertEquals(16384, outbound.get(2).payloadLength());
            assertEquals(3616, outbound.get(3).payloadLength());
            assertEquals(Http2Flags.NONE, outbound.get(2).flags());
            assertEquals(Http2Flags.END_STREAM, outbound.get(3).flags());
        });
    }

    @Test
    public void testEncoderSplitsLargeHeaderBlockIntoContinuationFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                Http2DecoderContent decoderState = new Http2DecoderContent(false, h2Settings(8192));
                decoderState.applyRemoteSetting(Http2Settings.SETTINGS_MAX_FRAME_SIZE, 16384);
                ctx.context(Http2DecoderContent.class, decoderState);
                ctx.addLastEncoder("h2-message-encoder", new Http2ObjectEncoder(false));
            }, VrtSoConfig.asClient());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/large-header");
            request.streamId(1);
            request.addHeader(HttpHeaderNames.HOST, "example.com");
            request.addHeader("x-large", buildLargeHeaderValue(40000));

            List<Http2Frame> outbound = sendAndOutBound(pipe, request);
            assertTrue(outbound.size() >= 4);
            assertEquals(Http2FrameType.PREFACE, outbound.get(0).type());
            assertEquals(Http2FrameType.SETTINGS, outbound.get(1).type());
            assertEquals(Http2FrameType.HEADERS, outbound.get(2).type());
            assertEquals(Http2Flags.END_STREAM, outbound.get(2).flags() & Http2Flags.END_STREAM);
            assertFalse(Http2Flags.endHeaders(outbound.get(2).flags()));
            assertEquals(Http2FrameType.CONTINUATION, outbound.get(3).type());
            assertTrue(Http2Flags.endHeaders(outbound.get(outbound.size() - 1).flags()));

            int total = 0;
            for (int i = 2; i < outbound.size(); i++) {
                total += outbound.get(i).payloadLength();
            }
            byte[] headerBlock = new byte[total];
            int offset = 0;
            for (int i = 2; i < outbound.size(); i++) {
                System.arraycopy(outbound.get(i).payload(), outbound.get(i).payloadOffset(), headerBlock, offset, outbound.get(i).payloadLength());
                offset += outbound.get(i).payloadLength();
            }
            HpackDecoder decoder = new HpackDecoder(4096, 65535);
            HttpHeaders decoded = decoder.decode(headerBlock, 0, headerBlock.length);
            assertEquals("GET", decoded.getString(HttpHeaderNames.PSEUDO_METHOD));
            assertEquals("/large-header", decoded.getString(HttpHeaderNames.PSEUDO_PATH));
            assertEquals(buildLargeHeaderValue(40000), decoded.getString("x-large"));
        });
    }
}