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
import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.Inflater;
import com.jcraft.jzlib.JZlib;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.websocket.*;
/**
 * Built-in support for the {@code permessage-deflate} websocket extension.
 * <p>
 * The current implementation only accepts a single negotiated extension under
 * RFC 6455 framing and maps it to one runtime compressor/decompressor instance
 * per websocket connection.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class PerMessageDeflateSupport implements WebSocketExtension {
    public static final String                    EXTENSION_NAME             = "permessage-deflate";
    public static final String                    CLIENT_NO_CONTEXT_TAKEOVER = "client_no_context_takeover";
    public static final String                    SERVER_NO_CONTEXT_TAKEOVER = "server_no_context_takeover";
    public static final String                    CLIENT_MAX_WINDOW_BITS     = "client_max_window_bits";
    public static final String                    SERVER_MAX_WINDOW_BITS     = "server_max_window_bits";
    private static final int                      DEFAULT_WINDOW_BITS        = 15;
    private static final PerMessageDeflateSupport INSTANCE                   = new PerMessageDeflateSupport();
    private static final byte[]                   DEFLATE_TAIL               = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };

    /**
     * Return the singleton support instance.
     * @return singleton support instance
     */
    public static PerMessageDeflateSupport instance() {
        return INSTANCE;
    }

    /**
     * Return the websocket extension name handled by this extension.
     * @return extension name
     */
    @Override
    public String extensionName() {
        return EXTENSION_NAME;
    }

    /**
     * Select the server-side negotiated extension header.
     * @param request handshake request snapshot
     * @param proposedExtensions proposed extension header value
     * @return negotiated extension header, or {@code null} when the extension is not enabled
     */
    @Override
    public String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        verifyRfc6455(request.version(), "websocket handshake failed: built-in extension support is currently limited to RFC6455.");

        WebSocketExtensionResult requested = parsePerMessageDeflate(request.requestedExtensions(), true, "websocket handshake failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        WebSocketExtensionResult proposed = parsePerMessageDeflate(proposedExtensions, false, "websocket handshake failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (proposed == null) {
            return null;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsolicited websocket extension.");
        }

        validateNegotiatedSubset(requested, proposed, true);
        return proposed.asHeaderValue();
    }

    /**
     * Validate the extension result returned by the server to the client.
     * @param version websocket version
     * @param requestedExtensions extension header requested by the client
     * @param negotiatedExtensions extension header returned by the server
     */
    @Override
    public void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions) {
        verifyRfc6455(version, "websocket upgrade failed: built-in extension support is currently limited to RFC6455.");

        WebSocketExtensionResult requested = parsePerMessageDeflate(requestedExtensions, true, "websocket upgrade failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        WebSocketExtensionResult negotiated = parsePerMessageDeflate(negotiatedExtensions, false, "websocket upgrade failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (negotiated == null) {
            return;
        }

        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket extension.");
        }

        validateNegotiatedSubset(requested, negotiated, false);
    }

    /**
     * Parse a negotiated extension header into a structured result.
     * @param headerValue negotiated extension header value
     * @return negotiated extension result, or {@code null} when no supported extension is present
     */
    @Override
    public WebSocketExtensionResult parseNegotiatedExtension(String headerValue) {
        return parsePerMessageDeflate(headerValue, false, "websocket extension runtime initialization failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
    }

    /**
     * Create the runtime compressor/decompressor for the negotiated extension.
     * @param negotiatedExtension negotiated extension result
     * @return runtime instance
     */
    @Override
    public WebSocketExtensionRuntime createRuntimeExtension(WebSocketExtensionResult negotiatedExtension) {
        if (negotiatedExtension == null) {
            return null;
        }

        if (!StringUtils.equalsIgnoreCase(EXTENSION_NAME, negotiatedExtension.name())) {
            throw new IllegalArgumentException("unsupported websocket extension: " + negotiatedExtension.name());
        }

        return new PerMessageDeflateRuntimeExtension(negotiatedExtension);
    }

    private static void verifyRfc6455(WebSocketVersion version, String message) {
        if (version == null || !version.isRfc6455Framing()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static WebSocketExtensionResult parsePerMessageDeflate(String headerValue, boolean requestPhase, String invalidMessage) {
        List<WebSocketExtensionResult> results = WebSocketExtensionResult.parse(headerValue);
        if (results.isEmpty()) {
            return null;
        }

        if (results.size() != 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        WebSocketExtensionResult result = results.get(0);
        if (!StringUtils.equalsIgnoreCase(EXTENSION_NAME, result.name())) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        return normalizeParameters(result, requestPhase, invalidMessage);
    }

    private static WebSocketExtensionResult normalizeParameters(WebSocketExtensionResult result, boolean requestPhase, String invalidMessage) {
        java.util.LinkedHashMap<String, String> normalized = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : result.parameters().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (StringUtils.equalsIgnoreCase(CLIENT_NO_CONTEXT_TAKEOVER, key)) {
                requireUniqueParameter(normalized, CLIENT_NO_CONTEXT_TAKEOVER, invalidMessage);
                requireNoValue(value, CLIENT_NO_CONTEXT_TAKEOVER, invalidMessage);
                normalized.put(CLIENT_NO_CONTEXT_TAKEOVER, null);
            } else if (StringUtils.equalsIgnoreCase(SERVER_NO_CONTEXT_TAKEOVER, key)) {
                requireUniqueParameter(normalized, SERVER_NO_CONTEXT_TAKEOVER, invalidMessage);
                requireNoValue(value, SERVER_NO_CONTEXT_TAKEOVER, invalidMessage);
                normalized.put(SERVER_NO_CONTEXT_TAKEOVER, null);
            } else if (StringUtils.equalsIgnoreCase(CLIENT_MAX_WINDOW_BITS, key)) {
                requireUniqueParameter(normalized, CLIENT_MAX_WINDOW_BITS, invalidMessage);
                normalized.put(CLIENT_MAX_WINDOW_BITS, normalizeWindowBits(value, CLIENT_MAX_WINDOW_BITS, requestPhase, invalidMessage));
            } else if (StringUtils.equalsIgnoreCase(SERVER_MAX_WINDOW_BITS, key)) {
                requireUniqueParameter(normalized, SERVER_MAX_WINDOW_BITS, invalidMessage);
                normalized.put(SERVER_MAX_WINDOW_BITS, normalizeWindowBits(value, SERVER_MAX_WINDOW_BITS, requestPhase, invalidMessage));
            } else {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
            }
        }

        return new WebSocketExtensionResult(EXTENSION_NAME, normalized);
    }

    private static void requireUniqueParameter(java.util.Map<String, String> normalized, String key, String invalidMessage) {
        for (String existingKey : normalized.keySet()) {
            if (StringUtils.equalsIgnoreCase(existingKey, key)) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage + " duplicated parameter: " + key);
            }
        }
    }

    private static void requireNoValue(String value, String key, String invalidMessage) {
        if (StringUtils.isNotBlank(value)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage + " unsupported value for " + key + '.');
        }
    }

    private static String normalizeWindowBits(String value, String key, boolean requestPhase, String invalidMessage) {
        if (StringUtils.isBlank(value)) {
            if (!requestPhase || !StringUtils.equalsIgnoreCase(CLIENT_MAX_WINDOW_BITS, key)) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
            }
            return null;
        }

        if (!isAsciiDigits(value) || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        int bits;
        try {
            bits = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage, e);
        }
        if (bits < 8 || bits > 15) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        return String.valueOf(bits);
    }

    private static boolean isAsciiDigits(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static void validateNegotiatedSubset(WebSocketExtensionResult requested, WebSocketExtensionResult negotiated, boolean serverSide) {
        validateWindowBitsSubset(requested, negotiated, CLIENT_MAX_WINDOW_BITS, false);
        validateWindowBitsSubset(requested, negotiated, SERVER_MAX_WINDOW_BITS, true);
    }

    private static void validateWindowBitsSubset(WebSocketExtensionResult requested, WebSocketExtensionResult negotiated, String key, boolean allowUnsolicited) {
        if (!negotiated.hasParameter(key)) {
            return;
        }

        if (!allowUnsolicited && !requested.hasParameter(key)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: negotiated permessage-deflate parameter was not requested: " + key);
        }

        String negotiatedValue = negotiated.parameter(key);
        if (StringUtils.isBlank(negotiatedValue)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: negotiated permessage-deflate parameter requires a concrete value: " + key);
        }

        if (!requested.hasParameter(key)) {
            return;
        }

        String requestedValue = requested.parameter(key);
        int requestedBits = StringUtils.isBlank(requestedValue) ? 15 : Integer.parseInt(requestedValue);
        int negotiatedBits = Integer.parseInt(negotiatedValue);
        if (negotiatedBits > requestedBits) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: negotiated permessage-deflate parameter exceeds requested range: " + key);
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

    private static final class PerMessageDeflateRuntimeExtension implements WebSocketExtensionRuntime {
        private final WebSocketExtensionResult negotiatedExtension;
        private Inflater                       inboundInflater;
        private boolean                        inboundActive;
        private Deflater                       outboundDeflater;
        private boolean                        outboundActive;

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
                ensureInboundInflater(context);
                if (!frame.isFinalFragment()) {
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

            boolean noContextTakeover = this.inboundNoContextTakeover(context);
            byte[] payload = this.inflateChunk(readPayload(frame), frame.isFinalFragment(), noContextTakeover);
            ByteBuf content = wrapPayload(context, payload);
            WebSocketFrame decodedFrame = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), false, false, false, frame.isMasked(), cloneMaskKey(frame), content, content.readableBytes());
            decodedFrame.streamId(frame.streamId());
            if (frame.isFinalFragment()) {
                if (noContextTakeover) {
                    this.endInbound();
                } else {
                    this.inboundActive = false;
                }
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
            boolean noContextTakeover = this.outboundNoContextTakeover(context);
            byte[] payload;
            if (!frame.isFinalFragment()) {
                if (!this.outboundActive) {
                    ensureOutboundDeflater(context);
                    this.outboundActive = true;
                }
                payload = this.deflateChunk(readPayload(frame), false, true);
            } else if (this.outboundActive) {
                payload = this.deflateChunk(readPayload(frame), true, true);
                if (noContextTakeover) {
                    this.endOutbound();
                } else {
                    this.outboundActive = false;
                }
            } else {
                ensureOutboundDeflater(context);
                payload = this.deflateChunk(readPayload(frame), true, true);
                if (noContextTakeover) {
                    this.endOutbound();
                }
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

        private boolean inboundNoContextTakeover(ProtoContext context) {
            WebSocketContext wsContext = WebSocketUtils.readyContext(context);
            boolean isServer = wsContext != null && wsContext.isServer();
            return isServer ? this.negotiatedExtension.hasParameter(CLIENT_NO_CONTEXT_TAKEOVER) : this.negotiatedExtension.hasParameter(SERVER_NO_CONTEXT_TAKEOVER);
        }

        private boolean outboundNoContextTakeover(ProtoContext context) {
            WebSocketContext wsContext = WebSocketUtils.readyContext(context);
            boolean isServer = wsContext != null && wsContext.isServer();
            return isServer ? this.negotiatedExtension.hasParameter(SERVER_NO_CONTEXT_TAKEOVER) : this.negotiatedExtension.hasParameter(CLIENT_NO_CONTEXT_TAKEOVER);
        }

        private int inboundWindowBits(ProtoContext context) {
            WebSocketContext wsContext = WebSocketUtils.readyContext(context);
            boolean isServer = wsContext != null && wsContext.isServer();
            return negotiatedWindowBits(this.negotiatedExtension, isServer ? CLIENT_MAX_WINDOW_BITS : SERVER_MAX_WINDOW_BITS);
        }

        private int outboundWindowBits(ProtoContext context) {
            WebSocketContext wsContext = WebSocketUtils.readyContext(context);
            boolean isServer = wsContext != null && wsContext.isServer();
            return negotiatedWindowBits(this.negotiatedExtension, isServer ? SERVER_MAX_WINDOW_BITS : CLIENT_MAX_WINDOW_BITS);
        }

        private void ensureInboundInflater(ProtoContext context) {
            if (this.inboundInflater == null) {
                this.inboundInflater = newInflater(this.inboundWindowBits(context));
            }
        }

        private void ensureOutboundDeflater(ProtoContext context) {
            if (this.outboundDeflater == null) {
                this.outboundDeflater = newDeflater(this.outboundWindowBits(context));
            }
        }

        private byte[] inflateChunk(byte[] input, boolean finalFragment, boolean noContextTakeover) {
            Inflater inflater = this.inboundInflater;

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length * 2));
            if (input.length > 0) {
                inflater.setInput(input);
            }
            if (finalFragment) {
                inflater.setInput(DEFLATE_TAIL, true);
            }

            inflateAvailable(inflater, output);

            return output.toByteArray();
        }

        private byte[] deflateChunk(byte[] input, boolean finalFragment, boolean syncFlush) {
            Deflater deflater = this.outboundDeflater;

            if (input.length > 0) {
                deflater.setInput(input);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(16, input.length));
            byte[] buffer = new byte[256];
            while (true) {
                deflater.setOutput(buffer);
                int status = deflater.deflate(finalFragment ? (syncFlush ? JZlib.Z_SYNC_FLUSH : JZlib.Z_FINISH) : JZlib.Z_NO_FLUSH);
                int count = buffer.length - deflater.avail_out;
                if (count > 0) {
                    output.write(buffer, 0, count);
                }

                if (status != JZlib.Z_OK && status != JZlib.Z_BUF_ERROR && status != JZlib.Z_STREAM_END) {
                    throw new IllegalStateException("permessage-deflate compression failed with status " + status);
                }
                if (deflater.avail_in == 0 && deflater.avail_out > 0) {
                    break;
                }
                if (count == 0 && deflater.avail_in == 0) {
                    break;
                }
            }

            byte[] compressed = output.toByteArray();
            if (finalFragment && syncFlush && compressed.length >= 4 && compressed[compressed.length - 4] == 0x00 && compressed[compressed.length - 3] == 0x00 && compressed[compressed.length - 2] == (byte) 0xFF && compressed[compressed.length - 1] == (byte) 0xFF) {
                byte[] trimmed = new byte[compressed.length - 4];
                System.arraycopy(compressed, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return compressed;
        }

        private void inflateAvailable(Inflater inflater, ByteArrayOutputStream output) {
            byte[] buffer = new byte[256];
            while (true) {
                inflater.setOutput(buffer);
                int status = inflater.inflate(JZlib.Z_SYNC_FLUSH);
                int count = buffer.length - inflater.avail_out;
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
                if (status == JZlib.Z_NEED_DICT) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload requires a dictionary, which is not supported.");
                }
                if (status == JZlib.Z_DATA_ERROR) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is invalid.");
                }
                if (status != JZlib.Z_OK && status != JZlib.Z_BUF_ERROR && status != JZlib.Z_STREAM_END) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "permessage-deflate payload is invalid. status=" + status);
                }
                if (inflater.avail_in == 0 && inflater.avail_out > 0) {
                    break;
                }
                if (count == 0 && inflater.avail_in == 0) {
                    break;
                }
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

    private static int negotiatedWindowBits(WebSocketExtensionResult negotiatedExtension, String key) {
        String value = negotiatedExtension.parameter(key);
        return StringUtils.isBlank(value) ? DEFAULT_WINDOW_BITS : Integer.parseInt(value);
    }

    private static Inflater newInflater(int windowBits) {
        try {
            return new Inflater(runtimeWindowBits(windowBits), true);
        } catch (GZIPException e) {
            throw new IllegalStateException("failed to create permessage-deflate inflater with window_bits=" + windowBits, e);
        }
    }

    private static Deflater newDeflater(int windowBits) {
        try {
            return new Deflater(JZlib.Z_DEFAULT_COMPRESSION, runtimeWindowBits(windowBits), true);
        } catch (GZIPException e) {
            throw new IllegalStateException("failed to create permessage-deflate deflater with window_bits=" + windowBits, e);
        }
    }

    private static int runtimeWindowBits(int negotiatedWindowBits) {
        // zlib-family implementations commonly promote raw window_bits=8 to 9 internally.
        return negotiatedWindowBits <= 8 ? 9 : negotiatedWindowBits;
    }
}