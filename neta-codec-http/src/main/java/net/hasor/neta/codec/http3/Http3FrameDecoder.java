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
package net.hasor.neta.codec.http3;

import java.util.HashMap;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import net.hasor.neta.codec.quic.QuicVarInt;

/**
 * HTTP/3 frame decoder that converts QUIC stream data into standard {@link HttpObject} instances.
 * <p>
 * This decoder operates on the output of the QUIC transport layer. It receives
 * per-stream data (with stream ID metadata) and parses HTTP/3 frames (RFC 9114).
 * The QPACK-compressed headers are decoded into standard HTTP headers, and the
 * result is emitted as the same {@link HttpObject} types used by HTTP/1.x and HTTP/2.
 * <p>
 * Input ByteBuf format (from QuicFrameDecoder):
 * <pre>
 *   streamId (8 bytes) + fin (1 byte) + stream data (...)
 * </pre>
 * <p>
 * <b>Design Principle:</b> All decoded messages are emitted as standard {@link HttpObject}
 * types ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent}),
 * so the application layer is protocol-agnostic regardless of HTTP version.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("quic", new QuicFrameDecoder(true));
 *   ctx.addLastDecoder("h3", new Http3FrameDecoder(true));
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http3FrameDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private final boolean                serverMode;
    private final QpackDecoder           qpackDecoder;
    private final Map<Long, Http3Stream> streams;
    private final Http3Settings          localSettings;
    private final Http3Settings          remoteSettings;
    private       boolean                settingsReceived;

    /**
     * Creates a new HTTP/3 frame decoder.
     * @param serverMode true for server-side (expects requests), false for client-side (expects responses)
     */
    public Http3FrameDecoder(boolean serverMode) {
        this.serverMode = serverMode;
        this.qpackDecoder = new QpackDecoder();
        this.streams = new HashMap<>();
        this.localSettings = new Http3Settings();
        this.remoteSettings = new Http3Settings();
        this.settingsReceived = false;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            ByteBuf msg = src.takeMessage();
            if (msg == null || msg.readableBytes() < 9) {
                continue;
            }

            // Read metadata header: streamId(8) + fin(1)
            byte[] metaHeader = new byte[9];
            msg.getBytes(0, metaHeader, 0, 9);
            msg.skipReadableBytes(9);

            long streamId = 0;
            for (int i = 0; i < 8; i++) {
                streamId = (streamId << 8) | (metaHeader[i] & 0xFF);
            }
            boolean fin = metaHeader[8] != 0;

            int dataLen = msg.readableBytes();
            byte[] data = new byte[dataLen];
            if (dataLen > 0) {
                msg.getBytes(0, data, 0, dataLen);
            }

            // Check if this is a unidirectional stream (control, QPACK encoder/decoder)
            if ((streamId & 0x02) != 0) {
                // Unidirectional stream - handle control/QPACK streams
                processUnidirectionalStream(streamId, data, dataLen);
                continue;
            }

            // Bidirectional request stream - process HTTP/3 frames
            processRequestStream(context, dst, streamId, data, dataLen, fin);
        }

        return ProtoStatus.Next;
    }

    /**
     * Processes data on a bidirectional request stream.
     * Parses HTTP/3 frames (HEADERS, DATA) and emits HttpObject.
     */
    private void processRequestStream(ProtoContext context, ProtoSndQueue<HttpObject> dst, long streamId, byte[] data, int dataLen, boolean fin) {
        Http3Stream stream = streams.computeIfAbsent(streamId, sid -> {
            Http3Stream s = new Http3Stream(sid);
            s.state(Http3StreamState.OPEN);
            return s;
        });

        int pos = 0;
        while (pos < dataLen) {
            // Read frame type (variable-length int)
            long[] typeResult = QuicVarInt.decode(data, pos);
            long frameType = typeResult[0];
            pos += (int) typeResult[1];

            // Read frame length (variable-length int)
            long[] lenResult = QuicVarInt.decode(data, pos);
            int frameLength = (int) lenResult[0];
            pos += (int) lenResult[1];

            if (pos + frameLength > dataLen) {
                // Incomplete frame - buffer for next read
                break;
            }

            if (frameType == Http3FrameType.HEADERS) {
                processHeadersFrame(context, dst, stream, data, pos, frameLength);
            } else if (frameType == Http3FrameType.DATA) {
                processDataFrame(context, dst, stream, data, pos, frameLength, fin && (pos + frameLength >= dataLen));
            } else if (frameType == Http3FrameType.PUSH_PROMISE) {
                // Push promise - skip for now
            } else if (Http3FrameType.isReserved(frameType)) {
                // Reserved/grease frame - ignore
            }

            pos += frameLength;
        }

        // If FIN received and no more data, emit LastHttpContent if needed
        if (fin && stream.headersReceived() && stream.state() == Http3StreamState.OPEN) {
            dst.offerMessage(new DefaultLastHttpContent(context.byteBufAllocator().buffer(0)));
            stream.state(Http3StreamState.HALF_CLOSED);
        }
    }

    /**
     * Processes a HEADERS frame and emits HttpRequest or HttpResponse.
     */
    private void processHeadersFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, Http3Stream stream, byte[] data, int offset, int length) {
        HttpHeaders headers = qpackDecoder.decode(data, offset, length);

        if (!stream.headersReceived()) {
            // Initial headers - create request or response
            stream.markHeadersReceived();

            if (serverMode) {
                // Decode as HTTP request
                String method = headers.get(":method");
                String path = headers.get(":path");
                String authority = headers.get(":authority");
                String scheme = headers.get(":scheme");

                if (method == null)
                    method = "GET";
                if (path == null)
                    path = "/";

                HttpMethod httpMethod = HttpMethod.valueOf(method);
                DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_3_0, httpMethod, path);

                // Copy non-pseudo headers
                for (String name : headers.names()) {
                    if (!name.startsWith(":")) {
                        for (String value : headers.getAll(name)) {
                            request.headers().add(name, value);
                        }
                    }
                }

                // Map pseudo-headers
                if (authority != null) {
                    request.headers().add("host", authority);
                }

                dst.offerMessage(request);
            } else {
                // Decode as HTTP response
                String statusStr = headers.get(":status");
                int statusCode = 200;
                if (statusStr != null) {
                    statusCode = Integer.parseInt(statusStr);
                }

                HttpStatus status = HttpStatus.valueOf(statusCode);
                DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_3_0, status);

                for (String name : headers.names()) {
                    if (!name.startsWith(":")) {
                        for (String value : headers.getAll(name)) {
                            response.headers().add(name, value);
                        }
                    }
                }

                dst.offerMessage(response);
            }
        } else {
            // Trailers
            stream.markTrailersReceived();
            HttpHeaders trailerHeaders = new HttpHeaders();
            for (String name : headers.names()) {
                if (!name.startsWith(":")) {
                    for (String value : headers.getAll(name)) {
                        trailerHeaders.add(name, value);
                    }
                }
            }
            // Emit trailers as LastHttpContent
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(context.byteBufAllocator().buffer(0));
            for (String name : trailerHeaders.names()) {
                for (String value : trailerHeaders.getAll(name)) {
                    lastContent.trailerHeaders().add(name, value);
                }
            }
            dst.offerMessage(lastContent);
            stream.state(Http3StreamState.HALF_CLOSED);
        }
    }

    /**
     * Processes a DATA frame and emits HttpContent.
     */
    private void processDataFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, Http3Stream stream, byte[] data, int offset, int length, boolean lastData) {
        ByteBuf content = context.byteBufAllocator().buffer(Math.max(length, 1));
        if (length > 0) {
            content.writeBytes(data, offset, length);
        }
        content.markWriter();

        if (lastData) {
            dst.offerMessage(new DefaultLastHttpContent(content));
            stream.state(Http3StreamState.HALF_CLOSED);
        } else {
            dst.offerMessage(new DefaultHttpContent(content));
        }
    }

    /**
     * Processes data on a unidirectional stream (control stream, QPACK streams).
     */
    private void processUnidirectionalStream(long streamId, byte[] data, int dataLen) {
        if (dataLen == 0)
            return;

        // Read stream type (first varint on the stream)
        long[] typeResult = QuicVarInt.decode(data, 0);
        long streamType = typeResult[0];
        int pos = (int) typeResult[1];

        if (streamType == 0x00) {
            // Control stream - parse SETTINGS and other control frames
            processControlStream(data, pos, dataLen - pos);
        }
        // Stream type 0x02 = QPACK encoder stream
        // Stream type 0x03 = QPACK decoder stream
        // These would be handled for dynamic table updates
    }

    /**
     * Processes frames on the control stream.
     */
    private void processControlStream(byte[] data, int offset, int length) {
        int pos = offset;
        int end = offset + length;

        while (pos < end) {
            long[] typeResult = QuicVarInt.decode(data, pos);
            long frameType = typeResult[0];
            pos += (int) typeResult[1];

            long[] lenResult = QuicVarInt.decode(data, pos);
            int frameLength = (int) lenResult[0];
            pos += (int) lenResult[1];

            if (frameType == Http3FrameType.SETTINGS) {
                processSettingsFrame(data, pos, frameLength);
            } else if (frameType == Http3FrameType.GOAWAY) {
                // GOAWAY - graceful shutdown
            }

            pos += frameLength;
        }
    }

    /**
     * Processes a SETTINGS frame.
     */
    private void processSettingsFrame(byte[] data, int offset, int length) {
        int pos = offset;
        int end = offset + length;

        while (pos < end) {
            long[] idResult = QuicVarInt.decode(data, pos);
            long settingId = idResult[0];
            pos += (int) idResult[1];

            long[] valResult = QuicVarInt.decode(data, pos);
            long settingValue = valResult[0];
            pos += (int) valResult[1];

            if (!Http3Settings.isReservedSetting(settingId)) {
                remoteSettings.applySetting(settingId, settingValue);
            }
        }

        settingsReceived = true;
    }

    @Override
    public void onClose(ProtoContext context) {
        for (Http3Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }
}
