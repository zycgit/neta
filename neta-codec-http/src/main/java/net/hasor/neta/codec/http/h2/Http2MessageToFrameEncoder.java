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
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/** Encodes semantic {@link Http2Message} values into wire-level {@link Http2Frame} objects. */
public class Http2MessageToFrameEncoder implements ProtoHandler<Http2Message, Http2Frame> {
    private static final byte[]  CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private final        boolean serverMode;
    private final        int     maxHeaderTableSize;

    public Http2MessageToFrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    public Http2MessageToFrameEncoder(boolean serverMode, int maxHeaderTableSize) {
        this.serverMode = serverMode;
        this.maxHeaderTableSize = maxHeaderTableSize;
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        if (context.context(Http2EncoderContent.class) == null) {
            context.context(Http2EncoderContent.class, new Http2EncoderContent(this.serverMode, this.maxHeaderTableSize));
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Message> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        Http2EncoderContent state = context.context(Http2EncoderContent.class);
        if (!state.isPrefaceSent()) {
            dst.offerMessage(new Http2Frame(Http2FrameType.PREFACE, 0, 0, CLIENT_PREFACE));
            dst.offerMessage(Http2Frame.settings(Http2Flags.NONE, new byte[0]));
            state.markPrefaceSent();
        }
        while (src.hasMore()) {
            Http2Message msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            assignStreamIdIfNecessary(state, msg);
            if (msg instanceof Http2HeadersMessage) {
                encodeHeaders(state, (Http2HeadersMessage) msg, dst);
            } else if (msg instanceof Http2DataMessage) {
                encodeData((Http2DataMessage) msg, dst);
            } else if (msg instanceof Http2SettingsMessage) {
                encodeSettings((Http2SettingsMessage) msg, dst);
            } else if (msg instanceof Http2PingMessage) {
                Http2PingMessage ping = (Http2PingMessage) msg;
                dst.offerMessage(Http2Frame.ping(ping.ack() ? Http2Flags.ACK : Http2Flags.NONE, ping.opaqueData()));
            } else if (msg instanceof Http2WindowUpdateMessage) {
                Http2WindowUpdateMessage window = (Http2WindowUpdateMessage) msg;
                dst.offerMessage(Http2FrameToMessageDecoder.buildWindowUpdate(window.streamId(), window.increment()));
            } else if (msg instanceof Http2ResetStreamMessage) {
                Http2ResetStreamMessage reset = (Http2ResetStreamMessage) msg;
                dst.offerMessage(Http2Frame.rstStream(reset.streamId(), int32(reset.errorCode())));
            } else if (msg instanceof Http2GoAwayMessage) {
                Http2GoAwayMessage goAway = (Http2GoAwayMessage) msg;
                byte[] debugData = goAway.debugData();
                byte[] payload = new byte[8 + debugData.length];
                write31Bits(payload, 0, goAway.lastStreamId());
                write32Bits(payload, 4, goAway.errorCode());
                System.arraycopy(debugData, 0, payload, 8, debugData.length);
                dst.offerMessage(Http2Frame.goaway(payload));
            }
        }
        return ProtoStatus.Next;
    }

    private void assignStreamIdIfNecessary(Http2EncoderContent state, Http2Message msg) {
        if (msg.streamId() > 0) {
            state.setCurrentStreamId(msg.streamId());
            return;
        }
        if (!this.serverMode && msg instanceof Http2HeadersMessage) {
            msg.streamId(state.allocateNextStreamId());
            return;
        }
        int currentStreamId = state.currentStreamId();
        if (currentStreamId > 0) {
            msg.streamId(currentStreamId);
        }
    }

    private void encodeHeaders(Http2EncoderContent state, Http2HeadersMessage message, ProtoSndQueue<Http2Frame> dst) {
        state.beginHeaderEncode();
        for (String headerName : message.headers().headerNames()) {
            for (String value : message.headers().getValues(headerName)) {
                state.encodeHeader(headerName.toLowerCase(), value);
            }
        }
        byte[] headerBlock = state.finishHeaderEncode();
        int flags = Http2Flags.END_HEADERS | (message.endStream() ? Http2Flags.END_STREAM : Http2Flags.NONE);
        dst.offerMessage(Http2Frame.headers(message.streamId(), flags, headerBlock));
    }

    private void encodeData(Http2DataMessage message, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = message.content();
        int bodyLen = body != null ? body.readableBytes() : 0;
        byte[] bodyBytes = new byte[bodyLen];
        if (bodyLen > 0) {
            body.getBytes(0, bodyBytes, 0, bodyLen);
        }
        dst.offerMessage(Http2Frame.data(message.streamId(), message.endStream() ? Http2Flags.END_STREAM : Http2Flags.NONE, bodyBytes));
    }

    private void encodeSettings(Http2SettingsMessage message, ProtoSndQueue<Http2Frame> dst) {
        if (message.ack()) {
            dst.offerMessage(Http2Frame.settingsAck());
            return;
        }
        Map<Integer, Long> settings = message.settings();
        byte[] payload = new byte[settings.size() * 6];
        int offset = 0;
        for (Map.Entry<Integer, Long> entry : settings.entrySet()) {
            int id = entry.getKey();
            long value = entry.getValue();
            payload[offset++] = (byte) ((id >> 8) & 0xFF);
            payload[offset++] = (byte) (id & 0xFF);
            write32Bits(payload, offset, value);
            offset += 4;
        }
        dst.offerMessage(Http2Frame.settings(Http2Flags.NONE, payload));
    }

    private static byte[] int32(long value) {
        byte[] payload = new byte[4];
        write32Bits(payload, 0, value);
        return payload;
    }

    private static void write31Bits(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >> 24) & 0x7F);
        target[offset + 1] = (byte) ((value >> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static void write32Bits(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }
}