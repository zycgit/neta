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
package net.hasor.neta.codec.http.websocket;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.codec.http.AbstractHttpTest;
import net.hasor.neta.codec.http.HttpObject;

public class AbstractWebSocketTest extends AbstractHttpTest {
    protected static ProtoHandler<HttpObject, HttpObject> switchRouteOnHandshake(final ProtoRoutingControl routingControl, String targetRoute) {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getData() instanceof WebSocketHandshakeEvent) {
                    if (routingControl != null) {
                        routingControl.switchRoute(targetRoute);
                    }
                }
                return true;
            }
        };
    }

    protected static String text(WebSocketFrame... buffer) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            for (WebSocketFrame b : buffer) {
                buf.writeBuffer(b.content());
                b.release();
            }
            buf.markWriter();

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes, 0, bytes.length);
            return new String(bytes, StandardCharsets.US_ASCII);
        } finally {
            buf.free();
        }
    }

    protected static byte[] buildRfc6455Frame(int opcode, boolean fin, boolean masked, byte[] maskKey, byte[] payload) {
        int headerSize = 2;
        if (payload.length >= 126 && payload.length <= 65535) {
            headerSize += 2;
        } else if (payload.length > 65535) {
            headerSize += 8;
        }
        if (masked) {
            headerSize += 4;
        }

        byte[] frame = new byte[headerSize + payload.length];
        int writeIndex = 0;
        frame[writeIndex++] = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));

        int lengthMarker = payload.length < 126 ? payload.length : (payload.length <= 65535 ? 126 : 127);
        frame[writeIndex++] = (byte) ((masked ? 0x80 : 0x00) | lengthMarker);
        if (lengthMarker == 126) {
            frame[writeIndex++] = (byte) ((payload.length >>> 8) & 0xFF);
            frame[writeIndex++] = (byte) (payload.length & 0xFF);
        } else if (lengthMarker == 127) {
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame[writeIndex++] = (byte) ((length >>> shift) & 0xFF);
            }
        }

        if (masked) {
            System.arraycopy(maskKey, 0, frame, writeIndex, 4);
            writeIndex += 4;
            for (int i = 0; i < payload.length; i++) {
                frame[writeIndex++] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        } else {
            System.arraycopy(payload, 0, frame, writeIndex, payload.length);
        }
        return frame;
    }

    protected static byte[] buildHixieTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[payload.length + 2];
        frame[0] = 0x00;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        frame[frame.length - 1] = (byte) 0xFF;
        return frame;
    }

    protected static WebSocketFrame textFrame(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() * 3, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return WebSocketUtils.textFrame(true, false, null, buf);
    }

    protected static WebSocketFrame binaryFrame(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return WebSocketUtils.binaryFrame(true, false, null, buf);
    }

    protected static WebSocketFrame fragment(WebSocketOpcode opcode, String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(64, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        switch (opcode) {
            case TEXT:
                return WebSocketUtils.textFrame(false, false, null, buf);
            case BINARY:
                return WebSocketUtils.binaryFrame(false, false, null, buf);
            default:
                throw new IllegalArgumentException("unsupported fragment opcode: " + opcode);
        }
    }

    protected static WebSocketFrame continuation(String text, boolean fin) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(64, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return WebSocketUtils.continuationFrame(fin, false, null, buf);
    }

    protected static WebSocketFrame emptyContinuation(boolean fin) {
        return WebSocketUtils.continuationFrame(fin, false, null, ByteBuf.EMPTY);
    }

    protected static WebSocketFrame pingWithPayload(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(64, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return WebSocketUtils.pingFrame(false, null, buf);
    }

    protected static WebSocketFrame closeFrame(int statusCode, String reason) {
        return WebSocketUtils.closeFrame(statusCode, reason);
    }

    protected static WebSocketFrame closeFrameEmpty() {
        return WebSocketUtils.closeFrame(false, null, ByteBuf.EMPTY);
    }

}
