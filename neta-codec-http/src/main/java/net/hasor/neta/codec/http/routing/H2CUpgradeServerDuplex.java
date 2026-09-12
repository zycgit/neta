/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.routing;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoDuplex;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.Http2ContextImpl;
import net.hasor.neta.codec.http.h2.Http2Settings;
/**
 * Server-side bridge handling the RFC 7540 h2c upgrade flow over HTTP/1.1.
 * <p>
 * This handler is designed to sit on the h2c branch after the regular HTTP/1.1 request codec. It
 * is responsible only for the upgrade transaction itself: buffering staged {@link HttpObject}
 * request parts, deciding whether the current request is a valid {@code Upgrade: h2c} exchange,
 * sending the raw HTTP/1.1 {@code 101 Switching Protocols} response, preparing stream 1 as a
 * one-shot route seed for the {@code h2} branch, and then handing control over to the regular
 * HTTP/2 pipeline. When this bridge calls the default-immediate {@link ProtoRoutingControl#switchRoute(String, Object)},
 * the {@code h2} branch runs one empty-input receive round right away so it can emit the server
 * SETTINGS preface and consume the promoted stream-1 request in the same outer routing invocation.
 * The route seed is used only for the already-consumed upgrade request. Once this handler accepts
 * the HTTP/1.1 upgrade request, it explicitly hands it off as a synthetic stream-1 request. The
 * client HTTP/2 connection preface, client SETTINGS, and SETTINGS ACK sent afterwards flow to the
 * {@code h2} branch as normal HTTP/2 traffic after the switch.
 * <p>
 * Sequence outline:
 * <pre>
 *   client                h2c branch                         h2 branch
 *     |                       |                                  |
 *     | HTTP/1.1 upgrade req  |                                  |
 *     |---------------------->| buffer staged request parts      |
 *     |                       | validate Upgrade + Settings      |
 *     |                       | if not upgrade: passthrough      |
 *     |&lt;----------------------| continue on current h2c branch tail
 *     |                       |                                  |
 *     |                       | if valid upgrade request         |
 *     |                       | send "101 Switching Protocols"   |
 *     |                       | convert consumed upgrade request |
 *     |                       | to synthetic stream-1 seed       |
 *     |                       | switchRoute(BRANCH_H2, seed) ---->| apply route cut-over
 *     |                       |                                  | emit server SETTINGS preface
 *     |                       |                                  | Http2ObjectDuplex consumes seed immediately
 *     |                       |                                  | stream 1 enters normal h2 pipeline
 *     |&lt;---------------------------------------------------------| upgraded response on stream 1
 *     | client preface        |                                  |
 *     | + client SETTINGS     |                                  |
 *     |---------------------->| routed as normal h2 traffic      |
 *     |                       |--------------------------------->| decoded by Http2Frame/Object path
 *     | SETTINGS ACK          |                                  |
 *     |---------------------->| routed as normal h2 traffic      |
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class H2CUpgradeServerDuplex implements ProtoDuplex<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final Logger       logger              = Logger.getLogger(H2CUpgradeServerDuplex.class);
    private static final String       UPGRADE_REQUEST_KEY = H2CUpgradeServerDuplex.class.getName() + ".upgradeRequest";
    private final ProtoRoutingControl control;
    private final Http2Settings       http2Settings;
    private boolean                   upgraded;

    /**
     * Creates an h2c upgrade bridge with the given routing controller.
     */
    public H2CUpgradeServerDuplex(ProtoRoutingControl control) {
        this(control, Http2Settings.defaultLocalSettings(true));
    }

    private H2CUpgradeServerDuplex(ProtoRoutingControl control, Http2Settings http2Settings) {
        this.control = Objects.requireNonNull(control, "control is null");
        this.http2Settings = http2Settings != null ? new Http2Settings(http2Settings) : Http2Settings.defaultLocalSettings(true);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        Http2ContextImpl.ensureInitialized(context, true, this.http2Settings);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,          //
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleUpgradeRequest(context, rcvUp, rcvDown);
        } else {
            if (this.upgraded) {
                if (sndUp != null && sndUp.hasMore()) {
                    throw new HttpProtocolStateException("HTTP/2: h2c upgrade branch should switch to the h2 route before receiving post-upgrade outbound payload");
                }
                return ProtoStatus.Next;
            }

            return this.forwardSendPassthrough(sndUp, sndDown);
        }
    }

    //

    private ProtoStatus handleUpgradeRequest(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        if (this.tryFinalizeBufferedRequest(context, rcvUp, rcvDown)) {
            return ProtoStatus.Next;
        }

        while (rcvUp.hasMore()) {
            HttpObject message = rcvUp.peekMessage();
            if (message == null) {
                rcvUp.takeMessage();
                continue;
            }

            if (this.upgraded) {
                throw new HttpProtocolStateException("HTTP/2: h2c upgrade branch should switch to the h2 route before receiving post-upgrade inbound payload");
            }

            ProtoRcvQueueView<HttpObject> requestView = this.bufferedRequestView(rcvUp);
            boolean bufferingRequest = requestView != null && requestView.hasMore();
            if (!bufferingRequest && !(message instanceof HttpRequest)) {
                if (!this.forwardSingle(rcvUp, rcvDown)) {
                    return ProtoStatus.Next;
                }
                continue;
            }

            rcvUp.drainToQueue(UPGRADE_REQUEST_KEY, 1);
            if (isRequestComplete(message) && this.tryFinalizeBufferedRequest(context, rcvUp, rcvDown)) {
                if (this.upgraded) {
                    return ProtoStatus.Next;
                }
            } else if (!isRequestComplete(message)) {
                continue;
            }
        }

        return ProtoStatus.Next;
    }

    private boolean tryFinalizeBufferedRequest(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        ProtoRcvQueueView<HttpObject> requestView = this.bufferedRequestView(rcvUp);
        if (requestView == null || !requestView.hasMore()) {
            return false;
        }

        List<HttpObject> requestParts = requestView.peekMessage(-1);
        if (requestParts.isEmpty()) {
            requestView.discard();
            return false;
        }

        HttpObject lastPart = requestParts.get(requestParts.size() - 1);
        if (!isRequestComplete(lastPart)) {
            return false;
        }

        if (isValidUpgradeRequest(requestParts)) {
            FullHttpRequest request = this.buildFullHttpRequest(requestParts, context.byteBufAllocator());
            try {
                this.performUpgrade(context, request);
            } finally {
                requestView.discard();
            }
            return true;
        }

        if (rcvDown == null || rcvDown.slotSize() < requestView.queueSize()) {
            return false;
        }

        List<HttpObject> forwardedRequest = requestView.takeMessage(-1);
        if (!forwardedRequest.isEmpty() && !rcvDown.offerMessage(forwardedRequest)) {
            throw new HttpProtocolStateException("HTTP/2: failed to forward buffered h2c request after capacity pre-check");
        }
        return true;
    }

    private boolean isValidUpgradeRequest(List<HttpObject> requestParts) {
        HttpRequest requestLine = this.findRequestLine(requestParts);
        if (requestLine == null || !HttpVersion.HTTP_1_1.equals(requestLine.protocolVersion())) {
            return false;
        }

        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.H2C, this.getHeader(requestParts, HttpHeaderNames.UPGRADE))) {
            return false;
        }

        List<String> headers = this.getHeaders(requestParts, HttpHeaderNames.HTTP2_SETTINGS);
        if (headers.size() != 1 || StringUtils.isBlank(headers.get(0))) {
            return false;
        }

        String c = this.getHeader(requestParts, HttpHeaderNames.CONNECTION);
        return containsConnectionToken(c, HttpHeaderValues.UPGRADE) && containsConnectionToken(c, HttpHeaderNames.HTTP2_SETTINGS);
    }

    private ProtoRcvQueueView<HttpObject> bufferedRequestView(ProtoRcvQueue<HttpObject> rcvUp) {
        if (rcvUp == null || !rcvUp.hasQueue(UPGRADE_REQUEST_KEY)) {
            return null;
        }
        return rcvUp.queueView(UPGRADE_REQUEST_KEY);
    }

    private FullHttpRequest buildFullHttpRequest(List<HttpObject> requestParts, ByteBufAllocator alloc) {
        HttpRequest requestLine = this.findRequestLine(requestParts);
        if (requestLine == null) {
            throw new HttpProtocolStateException("HTTP/2: incomplete h2c request state");
        }

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        CompositeByteBuf content = null;
        for (HttpObject part : requestParts) {
            if (part instanceof HttpHeaders) {
                headers.appendHeaders((HttpHeaders) part);
            }
            if (part instanceof HttpContent) {
                ByteBuf bodyPart = ((HttpContent) part).transferContent();
                if (bodyPart != null && bodyPart.readableBytes() > 0) {
                    if (content == null) {
                        content = ByteBufUtils.compositeBuffer(alloc);
                    }
                    content.addComponent(bodyPart);
                }
            }
        }

        FullHttpRequest request = new DefaultFullHttpRequest(new DefaultHttpRequest(requestLine.protocolVersion(), requestLine.method(), requestLine.uri()), new HttpHeaders[] { headers }, content == null ? ByteBuf.EMPTY : content);
        request.streamId(requestLine.streamId());
        return request;
    }

    private void performUpgrade(ProtoContext context, FullHttpRequest request) throws Throwable {
        byte[] settingsPayload = decodeSettingsPayload(request.getString(HttpHeaderNames.HTTP2_SETTINGS));
        Http2ContextImpl h2Context = Http2ContextImpl.require(context);
        h2Context.applyH2cUpgradeSettings(settingsPayload);
        h2Context.openH2cUpgradeStream(1);

        sendSwitchingProtocols(context);
        context.fireEventSnd(HttpThroughEvent.class, new HttpThroughEvent(true, request.streamId()));
        this.upgraded = true;

        promoteRequestToHttp2(context, request);
        this.control.switchRoute(HttpRouteKey.BRANCH_H2, request);
        if (context.getConfig().isPrintLog()) {
            logger.info("[H2C-UPGRADE] channel=" + context.getChannel().getChannelId() + " upgraded request to stream=1 uri=" + request.uri());
        }
    }

    private byte[] decodeSettingsPayload(String encodedSettings) {
        String value = encodedSettings.trim();
        int padding = value.length() % 4;
        if (padding != 0) {
            value = value + "====".substring(padding);
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value);
            if (payload.length % 6 != 0) {
                throw new HttpBadRequestException("HTTP/2: invalid HTTP2-Settings payload length " + payload.length);
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw new HttpBadRequestException("HTTP/2: invalid HTTP2-Settings header", e);
        }
    }

    private void sendSwitchingProtocols(ProtoContext context) throws Throwable {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.H2C);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
        context.sendData(response).get();
    }

    private void promoteRequestToHttp2(ProtoContext context, FullHttpRequest request) {
        FullHttpRequest fullRequest = request;
        fullRequest.removeHeader(HttpHeaderNames.CONNECTION);
        fullRequest.removeHeader(HttpHeaderNames.UPGRADE);
        fullRequest.removeHeader(HttpHeaderNames.HTTP2_SETTINGS);
        if (StringUtils.isBlank(fullRequest.getString(HttpHeaderNames.X_FORWARDED_PROTO))) {
            fullRequest.setHeader(HttpHeaderNames.X_FORWARDED_PROTO, "http");
        }

        fullRequest.protocolVersion(HttpVersion.HTTP_2_0);
        fullRequest.streamId(1);
        Http2ContextImpl.require(context).markH2cUpgradedRequestEmitted(1);
    }

    private ProtoStatus forwardSendPassthrough(ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) {
        while (sndUp.hasMore()) {
            if (sndDown == null || !sndDown.hasSlot()) {
                return ProtoStatus.Next;
            }

            HttpObject message = sndUp.peekMessage();
            if (message == null) {
                sndUp.takeMessage();
                continue;
            }

            if (!sndDown.offerMessage(message)) {
                return ProtoStatus.Next;
            }
            sndUp.takeMessage();
        }
        return ProtoStatus.Next;
    }

    private boolean forwardSingle(ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        if (dst == null || !dst.hasSlot()) {
            return false;
        }

        HttpObject message = src.takeMessage();
        if (message != null) {
            dst.offerMessage(message);
        }

        return true;
    }

    // Utils

    private static boolean isRequestComplete(HttpObject msg) {
        return msg instanceof LastHttpContent || (msg instanceof HttpRequest && msg instanceof HttpContent);
    }

    private boolean containsConnectionToken(String headerValue, String token) {
        if (headerValue == null || token == null) {
            return false;
        }

        String[] tokens = headerValue.split(",");
        for (String item : tokens) {
            if (token.equalsIgnoreCase(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private HttpRequest findRequestLine(List<HttpObject> requestParts) {
        for (HttpObject part : requestParts) {
            if (part instanceof HttpRequest) {
                return (HttpRequest) part;
            }
        }
        return null;
    }

    private String getHeader(List<HttpObject> requestParts, String name) {
        for (HttpObject part : requestParts) {
            if (part instanceof HttpHeaders) {
                String value = ((HttpHeaders) part).getString(name);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private List<String> getHeaders(List<HttpObject> requestParts, String name) {
        ArrayList<String> values = new ArrayList<>();
        for (HttpObject part : requestParts) {
            if (part instanceof HttpHeaders) {
                values.addAll(((HttpHeaders) part).getValues(name));
            }
        }
        return values;
    }

}
