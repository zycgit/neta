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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.Http2ResetEvent;
/**
 * Package-private websocket helpers used only by the local codec implementation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-09
 */
final class InternalUtils {
    private static final Logger logger                = Logger.getLogger(InternalUtils.class);
    private static final String WS_CLOSE_SENT_KEY     = "neta.websocket.close.sent";
    private static final String WS_CLOSE_RECEIVED_KEY = "neta.websocket.close.received";

    private InternalUtils() {
    }

    static ByteBuf decodeMaskedRange(ByteBuf source, int offset, int length, byte[] maskKey, long maskOffset) {
        if (maskKey == null || maskKey.length != 4) {
            throw new IllegalArgumentException("maskKey must contain exactly 4 bytes.");
        }
        if (maskOffset < 0) {
            throw new IllegalArgumentException("maskOffset must not be negative.");
        }
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }

        byte[] decoded = new byte[length];
        source.getBytes(offset, decoded, 0, length);
        int keyIndex = (int) (maskOffset & 3L);
        int i = 0;
        for (; i + 4 <= length; i += 4) {
            decoded[i] ^= maskKey[keyIndex];
            decoded[i + 1] ^= maskKey[(keyIndex + 1) & 3];
            decoded[i + 2] ^= maskKey[(keyIndex + 2) & 3];
            decoded[i + 3] ^= maskKey[(keyIndex + 3) & 3];
        }
        for (; i < length; i++) {
            decoded[i] ^= maskKey[(keyIndex + i) & 3];
        }
        return ByteBuf.wrap(decoded);
    }

    static List<WebSocketExtensionResult> parseExtensions(String extensions) {
        if (StringUtils.isBlank(extensions)) {
            return Collections.emptyList();
        }

        return WebSocketExtensionResult.parse(extensions);
    }

    static List<WebSocketExtensionResult> parseExtensions(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Collections.emptyList();
        }

        List<WebSocketExtensionResult> results = new ArrayList<>(extensions.size());
        for (String extension : extensions) {
            if (StringUtils.isBlank(extension)) {
                continue;
            }

            results.addAll(WebSocketExtensionResult.parse(extension));
        }

        return results.isEmpty() ? Collections.emptyList() : results;
    }

    static List<WebSocketExtensionRuntime> resolveRuntimeExtensions(List<WebSocketExtensionResult> extensionResults, WebSocketSettings settings) {
        if (settings == null || extensionResults == null || extensionResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<WebSocketExtensionRuntime> runtimeExtensions = new ArrayList<>(extensionResults.size());
        for (WebSocketExtensionResult result : extensionResults) {
            WebSocketExtensionRuntime runtimeExtension = null;
            for (WebSocketExtension support : settings.extensionSupports()) {
                if (support == null || !StringUtils.equalsIgnoreCase(support.extensionName(), result.name())) {
                    continue;
                }

                WebSocketExtensionResult negotiated = support.parseNegotiatedExtension(result.asHeaderValue());
                if (negotiated == null) {
                    break;
                }

                runtimeExtension = support.createRuntimeExtension(negotiated);
                break;
            }

            if (runtimeExtension != null) {
                runtimeExtensions.add(runtimeExtension);
            }
        }

        return runtimeExtensions.isEmpty() ? Collections.emptyList() : runtimeExtensions;
    }

    static void validateControlPayload(WebSocketOpcode opcode, ByteBuf content, boolean senderIsClient) {
        if (content != null && content.readableBytes() > 125) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control frame payload must not exceed 125 bytes.");
        }

        if (opcode == WebSocketOpcode.CLOSE) {
            validateClosePayload(content, senderIsClient);
        }
    }

    static void validateClosePayload(ByteBuf content, boolean senderIsClient) {
        if (content == null || content.readableBytes() == 0) {
            return;
        }
        if (content.readableBytes() == 1) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame payload must be either empty or at least 2 bytes.");
        }

        int statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
        validateCloseStatusCode(statusCode, senderIsClient);

        int reasonLen = content.readableBytes() - 2;
        if (reasonLen <= 0) {
            return;
        }

        byte[] reasonBytes = new byte[reasonLen];
        content.getBytes(2, reasonBytes, 0, reasonLen);
        decodeUtf8(reasonBytes, "close frame reason must be valid UTF-8.");
    }

    static void validateCloseStatusCode(int statusCode, boolean senderIsClient) {
        if (statusCode < WebSocketCode.NORMAL_CLOSURE || statusCode >= 5000) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
        if (statusCode == WebSocketCode.NO_STATUS || statusCode == WebSocketCode.ABNORMAL_CLOSURE) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }

        if (statusCode == WebSocketCode.MANDATORY_EXTENSION) {
            if (!senderIsClient) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
            }
            return;
        }

        if (statusCode == WebSocketCode.RESERVED || statusCode == 1012 || statusCode == 1013 || statusCode == 1014 || statusCode == WebSocketCode.TLS_HANDSHAKE) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }

        if (!(statusCode < 1016 || statusCode >= 3000)) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
    }

    static String decodeUtf8(byte[] bytes, String errorMessage) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, errorMessage, e);
        }
    }

    static boolean hasCloseSent(ProtoContext context) {
        return hasChannelFlag(context, WS_CLOSE_SENT_KEY);
    }

    static void markCloseSent(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_SENT_KEY, true);
    }

    static boolean hasCloseReceived(ProtoContext context) {
        return hasChannelFlag(context, WS_CLOSE_RECEIVED_KEY);
    }

    static void markCloseReceived(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_RECEIVED_KEY, true);
    }

    static void clearCloseState(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_SENT_KEY, false);
        setChannelFlag(context, WS_CLOSE_RECEIVED_KEY, false);
    }

    static void executeCloseAction(ProtoContext context, WebSocketCloseType actionType) {
        executeCloseAction(context, actionType, null);
    }

    static void executeCloseAction(ProtoContext context, WebSocketCloseType actionType, Future<?> future) {
        HttpVersion version = resolveHttpVersion(context);
        HttpScope scope = resolveHttpScope(context);
        if (version.majorVersion() == 1 && scope == HttpScope.CONNECTION) {
            executeHttp1ConnectionAction(context, actionType, future);
        } else if (version.majorVersion() == 2 && scope == HttpScope.STREAM) {
            executeHttp2StreamAction(context, actionType, future);
        } else {
            unsupportedCloseAction(context, actionType, version, scope);
        }
    }

    static HttpVersion resolveHttpVersion(ProtoContext context) {
        HttpVersion version = context != null ? context.context(HttpVersion.class) : null;
        return version != null ? version : HttpVersion.HTTP_1_1;
    }

    static HttpScope resolveHttpScope(ProtoContext context) {
        HttpScope scope = context != null ? context.context(HttpScope.class) : null;
        return scope != null ? scope : HttpScope.CONNECTION;
    }

    static boolean isStandardHttp2WebSocketRequest(HttpMessageParts request) {
        if (request == null || request.protocolVersion() == null || request.protocolVersion().majorVersion() != 2) {
            return false;
        }
        if (!HttpMethod.CONNECT.equals(request.method())) {
            return false;
        }
        return StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, request.header(HttpHeaderNames.PSEUDO_PROTOCOL));
    }

    static boolean isSuccessfulHttp2WebSocketResponse(HttpMessageParts response) {
        if (response == null || response.protocolVersion() == null || response.protocolVersion().majorVersion() != 2 || response.status() == null) {
            return false;
        }
        int code = response.status().code();
        return code >= 200 && code < 300;
    }

    private static void executeHttp1ConnectionAction(ProtoContext context, WebSocketCloseType actionType, Future<?> future) {
        if (context == null || context.getChannel() == null) {
            return;
        }

        switch (actionType) {
            case SEND_CLOSE_AND_TERMINATE:
                if (future == null) {
                    context.getChannel().close();
                } else {
                    future.onFinal(f -> context.getChannel().close());
                }
                return;
            case TERMINATE:
                context.getChannel().close();
                return;
            default:
                unsupportedCloseAction(context, actionType, HttpVersion.HTTP_1_1, HttpScope.CONNECTION);
        }
    }

    private static void executeHttp2StreamAction(ProtoContext context, WebSocketCloseType actionType, Future<?> future) {
        if (context == null) {
            return;
        }

        long streamId = resolveStreamId(context);
        if (streamId <= 0) {
            unsupportedCloseAction(context, actionType, HttpVersion.HTTP_2_0, HttpScope.STREAM);
            return;
        }

        Runnable closeStream = () -> {
            try {
                Http2ResetEvent resetEvent = new Http2ResetEvent(streamId, Http2ResetEvent.CANCEL).remote(false);
                context.fireEventSnd(Http2ResetEvent.class, resetEvent);
                context.fireEventRcv(Http2ResetEvent.class, new Http2ResetEvent(streamId, Http2ResetEvent.CANCEL).remote(false));
            } catch (Throwable e) {
                throw new IllegalStateException("failed to publish http2 reset event for websocket close. streamId=" + streamId, e);
            }
        };

        switch (actionType) {
            case SEND_CLOSE_AND_TERMINATE:
                if (future == null) {
                    closeStream.run();
                } else {
                    future.onFinal(f -> closeStream.run());
                }
                return;
            case TERMINATE:
                closeStream.run();
                return;
            default:
                unsupportedCloseAction(context, actionType, HttpVersion.HTTP_2_0, HttpScope.STREAM);
        }
    }

    private static void unsupportedCloseAction(ProtoContext context, WebSocketCloseType actionType, HttpVersion version, HttpScope scope) {
        String message = "unsupported websocket close strategy: actionType=" + actionType + ", httpVersion=" + version + ", httpScope=" + scope;
        logger.error(message);
        if (context != null && context.getChannel() != null) {
            context.getChannel().close();
        }

        throw new IllegalStateException(message);
    }

    private static long resolveStreamId(ProtoContext context) {
        PartitionKey partitionKey = context != null ? PartitionKey.findKey(context) : null;
        if (partitionKey == null || PartitionKey.defaultKey().equals(partitionKey)) {
            return 0;
        }

        try {
            return Long.parseLong(partitionKey.getKey());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasChannelFlag(ProtoContext context, String key) {
        if (context == null || context.getChannel() == null) {
            return false;
        }

        return Boolean.TRUE.equals(context.getChannel().getAttribute(scopedFlagKey(context, key)));
    }

    private static void setChannelFlag(ProtoContext context, String key, boolean value) {
        if (context == null || context.getChannel() == null) {
            return;
        }

        context.getChannel().setAttribute(scopedFlagKey(context, key), value ? Boolean.TRUE : null);
    }

    private static String scopedFlagKey(ProtoContext context, String key) {
        HttpVersion version = resolveHttpVersion(context);
        HttpScope scope = resolveHttpScope(context);
        if (version.majorVersion() == 2 && scope == HttpScope.STREAM) {
            long streamId = resolveStreamId(context);
            if (streamId > 0) {
                return key + '.' + streamId;
            }
        }
        return key;
    }
}
