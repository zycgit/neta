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
package net.hasor.neta.codec.http.websocket.extension;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.websocket.*;

/**
 * First-batch built-in extension support limited to RFC 6455 single-extension
 * {@code permessage-deflate} negotiation.
 */
public class PerMessageDeflateSupport implements WebSocketExtensionSupport {
    public static final  String                   EXTENSION_NAME = "permessage-deflate";
    private static final PerMessageDeflateSupport INSTANCE       = new PerMessageDeflateSupport();
    private static final byte[]                   DEFLATE_TAIL   = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };

    public static PerMessageDeflateSupport instance() {
        return INSTANCE;
    }

    @Override
    public String extensionName() {
        return EXTENSION_NAME;
    }

    @Override
    public String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        verifyRfc6455(request.version(), "websocket handshake failed: built-in extension support is currently limited to RFC6455.");

        String requested = normalizeSinglePerMessageDeflate(request.requestedExtensions(), "websocket handshake failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        String proposed = normalizeSinglePerMessageDeflate(proposedExtensions, "websocket handshake failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (proposed == null) {
            return null;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsolicited websocket extension.");
        } else {
            return proposed;
        }
    }

    @Override
    public void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions) {
        verifyRfc6455(version, "websocket upgrade failed: built-in extension support is currently limited to RFC6455.");

        String requested = normalizeSinglePerMessageDeflate(requestedExtensions, "websocket upgrade failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        String negotiated = normalizeSinglePerMessageDeflate(negotiatedExtensions, "websocket upgrade failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (negotiated == null) {
            return;
        }

        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket extension.");
        }
    }

    @Override
    public WebSocketExtensionResult parseNegotiatedExtension(String headerValue) {
        String negotiated = normalizeSinglePerMessageDeflate(headerValue, "websocket extension runtime initialization failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        return negotiated == null ? null : new WebSocketExtensionResult(EXTENSION_NAME);
    }

    @Override
    public WebSocketRuntimeExtension createRuntimeExtension(WebSocketExtensionResult negotiatedExtension) {
        if (negotiatedExtension == null) {
            return null;
        }

        if (!StringUtils.equalsIgnoreCase(EXTENSION_NAME, negotiatedExtension.name())) {
            throw new IllegalArgumentException("unsupported websocket extension: " + negotiatedExtension.name());
        }

        if (!negotiatedExtension.parameters().isEmpty()) {
            throw new IllegalArgumentException("permessage-deflate runtime currently does not support extension parameters.");
        }

        return new PerMessageDeflateRuntimeExtension(negotiatedExtension);
    }

    private static void verifyRfc6455(WebSocketVersion version, String message) {
        if (version == null || !version.isRfc6455Framing()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static String normalizeSinglePerMessageDeflate(String headerValue, String invalidMessage) {
        List<String> values = parseHeaderValues(headerValue);
        if (values.isEmpty()) {
            return null;
        }

        if (values.size() != 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        String value = values.get(0);
        if (!StringUtils.equals(EXTENSION_NAME, value)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        } else {
            return EXTENSION_NAME;
        }
    }

    private static List<String> parseHeaderValues(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return Collections.emptyList();
        }

        String[] parts = headerValue.split(",");
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part != null ? part.trim() : null;
            if (StringUtils.isNotBlank(value)) {
                values.add(value);
            }
        }

        if (values.isEmpty()) {
            return Collections.emptyList();
        } else {
            return values;
        }
    }

    private static boolean isDataOpcode(WebSocketOpcode opcode) {
        return opcode == WebSocketOpcode.TEXT || opcode == WebSocketOpcode.BINARY;
    }

    private static byte[] readPayload(WebSocketFrame frame) {
        ByteBuf content = frame.content();
        if (content == null || content.readableBytes() == 0) {
            return new byte[0];
        }

        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(0, bytes, 0, bytes.length);
        return bytes;
    }

    private static ByteBuf wrapPayload(ProtoContext context, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return ByteBuf.EMPTY;
        }

        ByteBuf buffer = context.byteBufAllocator().buffer(bytes.length, Integer.MAX_VALUE);
        buffer.writeBytes(bytes, 0, bytes.length);
        buffer.markWriter();
        return buffer;
    }

    private static byte[] cloneMaskKey(WebSocketFrame frame) {
        byte[] maskKey = frame.maskingKey();
        if (maskKey == null || maskKey.length == 0) {
            return null;
        }

        byte[] copy = new byte[maskKey.length];
        System.arraycopy(maskKey, 0, copy, 0, maskKey.length);
        return copy;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length));
            byte[] buffer = new byte[256];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    break;
                }
                output.write(buffer, 0, count);
            }

            byte[] compressed = output.toByteArray();
            if (compressed.length >= 4 && compressed[compressed.length - 4] == 0x00 && compressed[compressed.length - 3] == 0x00 && compressed[compressed.length - 2] == (byte) 0xFF && compressed[compressed.length - 1] == (byte) 0xFF) {
                byte[] trimmed = new byte[compressed.length - 4];
                System.arraycopy(compressed, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }

            return compressed;
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] input) {
        Inflater inflater = new Inflater(true);
        try {
            byte[] complete = new byte[input.length + DEFLATE_TAIL.length];
            System.arraycopy(input, 0, complete, 0, input.length);
            System.arraycopy(DEFLATE_TAIL, 0, complete, input.length, DEFLATE_TAIL.length);
            inflater.setInput(complete);

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length * 2));
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    output.write(buffer, 0, count);
                    continue;
                }
                if (inflater.needsInput()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload requires a dictionary, which is not supported.");
                }
                if (count == 0) {
                    break;
                }
            }

            if (!inflater.finished()) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is truncated or invalid.");
            }

            return output.toByteArray();
        } catch (DataFormatException e) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is invalid.", e);
        } finally {
            inflater.end();
        }
    }

    private static final class PerMessageDeflateRuntimeExtension implements WebSocketRuntimeExtension {
        private final WebSocketExtensionResult negotiatedExtension;
        private       Inflater                 inboundInflater;
        private       boolean                  inboundActive;
        private       Deflater                 outboundDeflater;
        private       boolean                  outboundActive;

        private PerMessageDeflateRuntimeExtension(WebSocketExtensionResult negotiatedExtension) {
            this.negotiatedExtension = negotiatedExtension;
        }

        @Override
        public WebSocketExtensionResult negotiatedExtension() {
            return this.negotiatedExtension;
        }

        @Override
        public boolean handlesInboundFrame(WebSocketFrame frame) {
            if (frame == null || frame.isRsv2() || frame.isRsv3()) {
                return false;
            }

            if (frame.isRsv1()) {
                return true;
            }

            if (!this.inboundActive) {
                return false;
            }

            WebSocketOpcode opcode = frame.opcode();
            return opcode == WebSocketOpcode.CONTINUATION || isDataOpcode(opcode);
        }

        @Override
        public boolean handlesOutboundFrame(WebSocketFrame frame) {
            if (frame == null || frame.isRsv2() || frame.isRsv3()) {
                return false;
            }

            if (frame.isRsv1()) {
                return isDataOpcode(frame.opcode());
            }

            if (this.outboundActive) {
                WebSocketOpcode opcode = frame.opcode();
                return opcode == WebSocketOpcode.CONTINUATION || isDataOpcode(opcode);
            }

            return isDataOpcode(frame.opcode());
        }

        @Override
        public WebSocketFrame decodeFrame(ProtoContext context, WebSocketFrame frame) {
            WebSocketOpcode opcode = frame.opcode();
            if (opcode == null) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "websocket frame opcode must not be null.");
            }
            if (frame.isRsv2() || frame.isRsv3()) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate only uses RSV1.");
            }

            if (frame.isRsv1()) {
                if (!isDataOpcode(opcode)) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate does not apply to websocket control frames.");
                }
                if (this.inboundActive) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "compressed websocket message continuation expected before starting a new compressed message.");
                }
                if (!frame.isFinalFragment()) {
                    this.inboundInflater = new Inflater(true);
                    this.inboundActive = true;
                }
            } else if (this.inboundActive) {
                if (opcode == WebSocketOpcode.CONTINUATION) {
                    // expected path
                } else if (isDataOpcode(opcode)) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "compressed websocket message continuation expected before starting a new data frame.");
                } else {
                    return frame;
                }
            } else {
                return frame;
            }

            byte[] payload = this.inflateChunk(readPayload(frame), frame.isFinalFragment());
            ByteBuf content = wrapPayload(context, payload);
            WebSocketFrame decodedFrame = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), false, false, false, frame.isMasked(), cloneMaskKey(frame), content, content.readableBytes());
            decodedFrame.streamId(frame.streamId());
            if (frame.isFinalFragment()) {
                this.endInbound();
            }

            return decodedFrame;
        }

        @Override
        public WebSocketFrame encodeFrame(ProtoContext context, WebSocketFrame frame) {
            WebSocketOpcode opcode = frame.opcode();
            if (opcode == null) {
                return frame;
            }

            if (this.outboundActive) {
                if (opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE) {
                    return frame;
                }
                if (opcode != WebSocketOpcode.CONTINUATION) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "compressed websocket message continuation expected before starting a new outbound data frame.");
                }
            } else if (!isDataOpcode(opcode)) {
                return frame;
            }

            if (frame.isRsv1()) {
                return frame;
            }
            if (frame.isRsv2() || frame.isRsv3()) {
                return frame;
            }

            boolean firstFragment = !this.outboundActive;
            byte[] payload;
            if (!frame.isFinalFragment()) {
                if (!this.outboundActive) {
                    this.outboundDeflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                    this.outboundActive = true;
                }
                payload = this.deflateChunk(readPayload(frame), false);
            } else if (this.outboundActive) {
                payload = this.deflateChunk(readPayload(frame), true);
                this.endOutbound();
            } else {
                payload = deflate(readPayload(frame));
            }

            ByteBuf content = wrapPayload(context, payload);
            WebSocketFrame encodedFrame = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), firstFragment, false, false, frame.isMasked(), cloneMaskKey(frame), content, content.readableBytes());
            encodedFrame.streamId(frame.streamId());
            return encodedFrame;
        }

        @Override
        public void reset() {
            this.endInbound();
            this.endOutbound();
        }

        private byte[] inflateChunk(byte[] input, boolean finalFragment) {
            Inflater inflater = this.inboundInflater;
            if (inflater == null) {
                inflater = new Inflater(true);
                this.inboundInflater = inflater;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length * 2));
            if (input.length > 0) {
                inflater.setInput(input);
            }

            inflateAvailable(inflater, output, false);
            if (finalFragment) {
                inflater.setInput(DEFLATE_TAIL);
                inflateAvailable(inflater, output, true);
            }

            return output.toByteArray();
        }

        private byte[] deflateChunk(byte[] input, boolean finalFragment) {
            Deflater deflater = this.outboundDeflater;
            if (deflater == null) {
                deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                this.outboundDeflater = deflater;
            }

            if (input.length > 0) {
                deflater.setInput(input);
            }

            if (finalFragment) {
                deflater.finish();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length));
            byte[] buffer = new byte[256];
            while (true) {
                int count = finalFragment ? deflater.deflate(buffer) : deflater.deflate(buffer, 0, buffer.length, Deflater.NO_FLUSH);
                if (count > 0) {
                    output.write(buffer, 0, count);
                    continue;
                }
                if (finalFragment) {
                    if (deflater.finished()) {
                        break;
                    }
                } else if (deflater.needsInput()) {
                    break;
                }
                if (count == 0) {
                    break;
                }
            }

            return output.toByteArray();
        }

        private void inflateAvailable(Inflater inflater, ByteArrayOutputStream output, boolean expectFinished) {
            byte[] buffer = new byte[256];
            try {
                while (true) {
                    int count = inflater.inflate(buffer);
                    if (count > 0) {
                        output.write(buffer, 0, count);
                        continue;
                    }
                    if (inflater.finished()) {
                        break;
                    }
                    if (inflater.needsDictionary()) {
                        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload requires a dictionary, which is not supported.");
                    }
                    if (inflater.needsInput() || count == 0) {
                        break;
                    }
                }
            } catch (DataFormatException e) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is invalid.", e);
            }

            if (expectFinished && !inflater.finished()) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is truncated or invalid.");
            }
        }

        private void endInbound() {
            if (this.inboundInflater != null) {
                this.inboundInflater.end();
                this.inboundInflater = null;
            }
            this.inboundActive = false;
        }

        private void endOutbound() {
            if (this.outboundDeflater != null) {
                this.outboundDeflater.end();
                this.outboundDeflater = null;
            }
            this.outboundActive = false;
        }
    }
}