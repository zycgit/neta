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

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.codec.http.*;

public class AbstractHttp2Test extends AbstractHttpTest {
    protected static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    protected static Http2Settings h2Settings() {
        return new Http2Settings();
    }

    protected static Http2Settings h2Settings(int maxHeaderListSize) {
        return h2Settings().headerTableSize(4096).maxHeaderListSize(maxHeaderListSize);
    }

    //

    protected static byte[] frame(int length, int type, int flags, int streamId) {
        return frame(length, type, flags, streamId, new byte[0]);
    }

    protected static byte[] frame(int length, int type, int flags, int streamId, byte[] payload) {
        return concat(frameHeader(length, type, flags, streamId), payload);
    }

    protected static byte[] frameHeader(int length, int type, int flags, int streamId) {
        byte[] header = new byte[9];
        header[0] = (byte) ((length >>> 16) & 0xFF);
        header[1] = (byte) ((length >>> 8) & 0xFF);
        header[2] = (byte) (length & 0xFF);
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) ((streamId >>> 24) & 0xFF);
        header[6] = (byte) ((streamId >>> 16) & 0xFF);
        header[7] = (byte) ((streamId >>> 8) & 0xFF);
        header[8] = (byte) (streamId & 0xFF);
        return header;
    }

    //

    protected static byte[] encodeHeaders(HttpHeaders headers) {
        HpackEncoder encoder = new HpackEncoder(4096);
        return encoder.encode(headers);
    }

    protected static void sendResponse(ProtoContext context, long streamId, String uri, String body, String prefix) throws Throwable {
        context.sendData(textResponse(streamId, prefix + uri + ":" + body)).get();
    }

    protected ProtoHandler<HttpObject, Object> echoRequestHandler() {
        return (context, src, dst) -> {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }

                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    sendResponse(context, request.streamId(), request.uri(), utf8(request.content()), "echo:");
                } finally {
                    request.release();
                }
            }

            return ProtoStatus.Next;
        };
    }

    protected static HttpHeaders decodeHeaderBlock(Http2Frame headersFrame) {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        return decoder.decode(headersFrame.payload(), headersFrame.payloadOffset(), headersFrame.payloadLength());
    }

    //

    protected static int readHttp2Int31(byte[] payload, int offset) {
        return ((payload[offset] & 0x7F) << 24) | ((payload[offset + 1] & 0xFF) << 16) | ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
    }

    protected static long readHttp2UnsignedInt(byte[] payload, int offset) {
        return ((long) (payload[offset] & 0xFF) << 24) | ((long) (payload[offset + 1] & 0xFF) << 16) | ((long) (payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
    }

    //

    protected static <T> T findEvent(Iterable<SoEvent> events, Class<T> eventType) {
        for (SoEvent event : events) {
            if (event != null && eventType.isInstance(event.getData())) {
                return eventType.cast(event.getData());
            }
        }
        return null;
    }

    protected static Http2Frame findFrame(List<Http2Frame> frames, int frameType) {
        for (Http2Frame frame : frames) {
            if (frame != null && frame.type() == frameType) {
                return frame;
            }
        }
        return null;
    }

    //

    protected void assertStreamReset(VirtualPipe pipe, int expectedStreamId, long expectedErrorCode) throws InterruptedException {
        assertTrue(waitUntil(() -> !pipe.channelOutbound().isEmpty() || !pipe.channelInboundErrors().isEmpty(), 1000L));
        assertTrue(pipe.channelInboundErrors().isEmpty());

        List<Http2Frame> outbound = drainQueue(pipe.channelOutbound());
        Http2Frame rstStreamFrame = findFrame(outbound, Http2FrameType.RST_STREAM);
        assertNotNull(rstStreamFrame);
        assertEquals(expectedStreamId, rstStreamFrame.streamId());
        assertEquals(expectedErrorCode, readHttp2UnsignedInt(rstStreamFrame.payload(), 0));

        Http2ResetEvent resetEvent = findEvent(pipe.channelEvents(), Http2ResetEvent.class);
        assertNotNull(resetEvent);
        assertEquals(expectedStreamId, resetEvent.streamId());
        assertEquals(expectedErrorCode, resetEvent.errorCode());
        assertFalse(resetEvent.isRemote());
    }

    protected void assertGoAway(VirtualPipe pipe, int expectedLastAccepted, long expectedErrorCode, String debugFragment) throws InterruptedException {
        assertTrue(waitUntil(() -> !pipe.channelOutbound().isEmpty() || pipe.channel().isClose() || !pipe.channelInboundErrors().isEmpty(), 1000L));
        assertTrue(pipe.channelInboundErrors().isEmpty());
        assertTrue(pipe.channel().isClose());

        List<Http2Frame> outbound = drainQueue(pipe.channelOutbound());
        Http2Frame goAwayFrame = findFrame(outbound, Http2FrameType.GOAWAY);
        assertNotNull(goAwayFrame);
        assertEquals(expectedLastAccepted, readHttp2Int31(goAwayFrame.payload(), 0));
        assertEquals(expectedErrorCode, readHttp2UnsignedInt(goAwayFrame.payload(), 4));

        Http2GoawayEvent goawayEvent = findEvent(pipe.channelEvents(), Http2GoawayEvent.class);
        assertNotNull(goawayEvent);
        assertEquals(expectedLastAccepted, goawayEvent.lastAcceptedId());
        assertEquals(expectedErrorCode, goawayEvent.errorCode());
        assertFalse(goawayEvent.isRemote());

        String debugData = new String(goAwayFrame.payload(), 8, goAwayFrame.payloadLength() - 8, StandardCharsets.UTF_8);
        if (debugFragment != null) {
            assertTrue(debugData.contains(debugFragment));
            assertTrue(new String(goawayEvent.debugData(), StandardCharsets.UTF_8).contains(debugFragment));
        }
    }

    protected static Http2Frame priorityFrame(int streamId, int streamDependency, int weight, boolean exclusive) {
        byte[] payload = new byte[5];
        int encodedDependency = exclusive ? (streamDependency | 0x80000000) : streamDependency;
        payload[0] = (byte) ((encodedDependency >>> 24) & 0xFF);
        payload[1] = (byte) ((encodedDependency >>> 16) & 0xFF);
        payload[2] = (byte) ((encodedDependency >>> 8) & 0xFF);
        payload[3] = (byte) (encodedDependency & 0xFF);
        payload[4] = (byte) (weight - 1);
        return Http2Frame.priority(streamId, payload);
    }

    protected static List<Http2Frame> pushPromiseFrames(int streamId, int promisedStreamId, HttpHeaders headers, int maxHeaderTableSize, int maxFrameSize) {
        HpackEncoder encoder = new HpackEncoder(maxHeaderTableSize);
        encoder.beginEncode();
        for (String headerName : headers.headerNames()) {
            for (String value : headers.getValues(headerName)) {
                encoder.encodeHeaderDirect(headerName.toLowerCase(), value);
            }
        }

        int headerBlockLength = encoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLength];
        System.arraycopy(encoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLength);
        int firstChunkLength = Math.min(headerBlock.length, maxFrameSize - 4);
        byte[] firstPayload = new byte[4 + firstChunkLength];
        firstPayload[0] = (byte) ((promisedStreamId >>> 24) & 0x7F);
        firstPayload[1] = (byte) ((promisedStreamId >>> 16) & 0xFF);
        firstPayload[2] = (byte) ((promisedStreamId >>> 8) & 0xFF);
        firstPayload[3] = (byte) (promisedStreamId & 0xFF);
        if (firstChunkLength > 0) {
            System.arraycopy(headerBlock, 0, firstPayload, 4, firstChunkLength);
        }
        int firstFlags = firstChunkLength == headerBlock.length ? Http2Flags.END_HEADERS : Http2Flags.NONE;

        List<Http2Frame> frames = new ArrayList<>();
        frames.add(Http2Frame.pushPromise(streamId, firstFlags, firstPayload));
        int offset = firstChunkLength;
        while (offset < headerBlock.length) {
            int chunkLength = Math.min(headerBlock.length - offset, maxFrameSize);
            int flags = (offset + chunkLength) == headerBlock.length ? Http2Flags.END_HEADERS : Http2Flags.NONE;
            frames.add(new Http2Frame(Http2FrameType.CONTINUATION, flags, streamId, headerBlock, offset, chunkLength));
            offset += chunkLength;
        }
        return frames;
    }

    //

    protected VirtualPipe openHttp2VirtualPipe(NetManager neta, ProtoHandler<HttpObject, Object> handler) throws Throwable {
        return this.openHttp2VirtualPipe(neta, handler, new ProtoPartitionControl[1]);
    }

    protected VirtualPipe openHttp2VirtualPipe(NetManager neta, ProtoHandler<HttpObject, Object> handler, ProtoPartitionControl[] ref) throws Throwable {
        int MAX_CONTENT_LENGTH = 1048576;
        return openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(false))   //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                    .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                    .build().config(clientCtx);
        }, serverCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))    //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(true)) //
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                        ref[0] = pb.control();

                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        pb.policy(policy).byDefault(pbc -> {
                            pbc.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                        }).byInitializer(pbc -> {
                            pbc.addLast("h2-server-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                            pbc.addLastDecoder("h2-handler", handler);
                        });
                    }).build().config(serverCtx);
        });
    }
}
