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
package net.hasor.neta.codec.http.websocket.extensions;
import java.io.ByteArrayOutputStream;
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
 * Built-in support for the legacy {@code deflate-frame} websocket extension.
 * <p>
 * The current implementation accepts a single negotiated {@code deflate-frame}
 * extension without parameters and applies raw DEFLATE independently to each
 * data frame.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class DeflateFrameSupport implements WebSocketExtension {
    public static final String               EXTENSION_NAME = "deflate-frame";
    private static final DeflateFrameSupport INSTANCE       = new DeflateFrameSupport();
    private static final byte[]              DEFLATE_TAIL   = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };
    private final String                     extensionName;

    /**
     * Return the singleton support instance.
     * @return singleton support instance
     */
    public static DeflateFrameSupport instance() {
        return INSTANCE;
    }

    protected DeflateFrameSupport() {
        this(EXTENSION_NAME);
    }

    protected DeflateFrameSupport(String extensionName) {
        this.extensionName = extensionName;
    }

    @Override
    public String extensionName() {
        return this.extensionName;
    }

    @Override
    public String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }

        verifyRfc6455(request.version(), "websocket handshake failed: deflate-frame is currently limited to RFC6455.");
        WebSocketExtensionResult requested = parseSingleDeflateFrame(request.requestedExtensions(), this.extensionName, "websocket handshake failed: only one requested extension is supported and it must be " + this.extensionName + " without parameters.");
        WebSocketExtensionResult proposed = parseSingleDeflateFrame(proposedExtensions, this.extensionName, "websocket handshake failed: only one negotiated extension is supported and it must be " + this.extensionName + " without parameters.");
        if (proposed == null) {
            return null;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsolicited websocket extension.");
        }

        return proposed.asHeaderValue();
    }

    @Override
    public void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions) {
        verifyRfc6455(version, "websocket upgrade failed: deflate-frame is currently limited to RFC6455.");

        WebSocketExtensionResult requested = parseSingleDeflateFrame(requestedExtensions, this.extensionName, "websocket upgrade failed: only one requested extension is supported and it must be " + this.extensionName + " without parameters.");
        WebSocketExtensionResult negotiated = parseSingleDeflateFrame(negotiatedExtensions, this.extensionName, "websocket upgrade failed: only one negotiated extension is supported and it must be " + this.extensionName + " without parameters.");
        if (negotiated == null) {
            return;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket extension.");
        }
    }

    @Override
    public WebSocketExtensionResult parseNegotiatedExtension(String headerValue) {
        return parseSingleDeflateFrame(headerValue, this.extensionName, "websocket extension runtime initialization failed: only one negotiated extension is supported and it must be " + this.extensionName + " without parameters.");
    }

    @Override
    public WebSocketExtensionRuntime createRuntimeExtension(WebSocketExtensionResult negotiatedExtension) {
        if (negotiatedExtension == null) {
            return null;
        }
        if (!StringUtils.equalsIgnoreCase(this.extensionName, negotiatedExtension.name())) {
            throw new IllegalArgumentException("unsupported websocket extension: " + negotiatedExtension.name());
        }
        if (!negotiatedExtension.parameters().isEmpty()) {
            throw new IllegalArgumentException("deflate-frame runtime currently does not support extension parameters.");
        }

        return new DeflateFrameRuntimeExtension(negotiatedExtension);
    }

    private static void verifyRfc6455(WebSocketVersion version, String message) {
        if (version == null || !version.isRfc6455Framing()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static WebSocketExtensionResult parseSingleDeflateFrame(String headerValue, String extensionName, String invalidMessage) {
        List<WebSocketExtensionResult> results = WebSocketExtensionResult.parse(headerValue);
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() != 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        WebSocketExtensionResult result = results.get(0);
        if (!StringUtils.equalsIgnoreCase(extensionName, result.name()) || !result.parameters().isEmpty()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        return new WebSocketExtensionResult(extensionName);
    }

    private static boolean isDataOpcode(WebSocketOpcode opcode) {
        return opcode == WebSocketOpcode.TEXT || opcode == WebSocketOpcode.BINARY || opcode == WebSocketOpcode.CONTINUATION;
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
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "deflate-frame payload requires a dictionary, which is not supported.");
                }
                if (count == 0) {
                    break;
                }
            }

            if (!inflater.finished()) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "deflate-frame payload is truncated or invalid.");
            }

            return output.toByteArray();
        } catch (DataFormatException e) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "deflate-frame payload is invalid.", e);
        } finally {
            inflater.end();
        }
    }

    private static final class DeflateFrameRuntimeExtension implements WebSocketExtensionRuntime {
        private final WebSocketExtensionResult negotiatedExtension;

        private DeflateFrameRuntimeExtension(WebSocketExtensionResult negotiatedExtension) {
            this.negotiatedExtension = negotiatedExtension;
        }

        @Override
        public WebSocketExtensionResult negotiatedExtension() {
            return this.negotiatedExtension;
        }

        @Override
        public boolean handlesInboundFrame(WebSocketFrame frame) {
            return frame != null && frame.isRsv1() && !frame.isRsv2() && !frame.isRsv3();
        }

        @Override
        public boolean handlesOutboundFrame(WebSocketFrame frame) {
            if (frame == null || frame.isRsv2() || frame.isRsv3()) {
                return false;
            }
            return isDataOpcode(frame.opcode());
        }

        @Override
        public WebSocketFrame decodeFrame(ProtoContext context, WebSocketFrame frame) {
            WebSocketOpcode opcode = frame.opcode();
            if (opcode == null) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "websocket frame opcode must not be null.");
            }
            if (!isDataOpcode(opcode)) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "deflate-frame does not apply to websocket control frames.");
            }

            ByteBuf content = wrapPayload(context, inflate(readPayload(frame)));
            WebSocketFrame decodedFrame = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), false, false, false, frame.isMasked(), cloneMaskKey(frame), content, content.readableBytes());
            decodedFrame.streamId(frame.streamId());
            return decodedFrame;
        }

        @Override
        public WebSocketFrame encodeFrame(ProtoContext context, WebSocketFrame frame) {
            WebSocketOpcode opcode = frame.opcode();
            if (!isDataOpcode(opcode) || frame.isRsv1() || frame.isRsv2() || frame.isRsv3()) {
                return frame;
            }

            ByteBuf content = wrapPayload(context, deflate(readPayload(frame)));
            WebSocketFrame encodedFrame = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), true, false, false, frame.isMasked(), cloneMaskKey(frame), content, content.readableBytes());
            encodedFrame.streamId(frame.streamId());
            return encodedFrame;
        }
    }
}