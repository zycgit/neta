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
import java.util.List;
import net.hasor.neta.channel.*;

/**
 * Duplex layer that applies negotiated runtime websocket extensions.
 * <p>
 * Inbound frames are decoded by runtime extensions in order, while outbound
 * frames are encoded before RSV usage is validated against the negotiated
 * extension set.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class WebSocketExtensionDuplexer implements ProtoDuplexer<WebSocketFrame, WebSocketFrame, WebSocketFrame, WebSocketFrame> {
    /**
     * Leave events untouched and let the surrounding pipeline handle them.
     * @param context protocol context for the current channel
     * @param event propagated event
     * @param isRcv whether the event travels on the receive side
     * @return always {@code true}
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
        return true;
    }

    /**
     * Apply inbound or outbound runtime extensions to the current frame flow.
     * @param context protocol context for the current channel
     * @param isRcv whether the current direction is inbound
     * @param rcvUp inbound source queue
     * @param rcvDown inbound destination queue
     * @param sndUp outbound source queue
     * @param sndDown outbound destination queue
     * @return processing status
     * @throws Throwable any extension error
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,                        //
            ProtoRcvQueue<WebSocketFrame> rcvUp, ProtoSndQueue<WebSocketFrame> rcvDown,     //
            ProtoRcvQueue<WebSocketFrame> sndUp, ProtoSndQueue<WebSocketFrame> sndDown) throws Throwable {
        if (isRcv) {
            return this.processInbound(context, rcvUp, rcvDown);
        } else {
            return this.processOutbound(context, sndUp, sndDown);
        }
    }

    /**
     * Reset runtime extension state after an error.
     * @param context protocol context for the current channel
     * @param isRcv whether the failure happened on the receive side
     * @param e failure cause
     * @param eh exception holder passed by the pipeline
     * @return always {@link ProtoStatus#Next}
     * @throws Throwable never emitted by this implementation directly
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.resetRuntimeExtensions(context, false);
        return ProtoStatus.Next;
    }

    /**
     * Close all runtime extensions and release their state.
     * @param context protocol context for the current channel
     */
    @Override
    public void onClose(ProtoContext context) {
        this.resetRuntimeExtensions(context, true);
    }

    private ProtoStatus processInbound(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<WebSocketFrame> dst) {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            WebSocketFrame current = frame;
            boolean offered = false;
            try {
                current = this.applyInboundExtensions(context, frame);
                dst.offerMessage(current);
                offered = true;
            } finally {
                if (!offered) {
                    if (current != frame) {
                        frame.release();
                    }
                    current.release();
                }
            }
        }
        return ProtoStatus.Next;
    }

    private ProtoStatus processOutbound(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<WebSocketFrame> dst) {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            WebSocketFrame current = frame;
            boolean offered = false;
            try {
                current = this.applyOutboundExtensions(context, frame);
                this.validateNegotiatedRsv(current, WebSocketUtils.runtimeExtensions(context));
                dst.offerMessage(current);
                offered = true;
            } finally {
                if (!offered) {
                    if (current != frame) {
                        frame.release();
                    }
                    current.release();
                }
            }
        }

        return ProtoStatus.Next;
    }

    private WebSocketFrame applyInboundExtensions(ProtoContext context, WebSocketFrame frame) {
        WebSocketFrame current = frame;
        List<WebSocketExtensionRuntime> runtimeExtensions = WebSocketUtils.runtimeExtensions(context);
        for (WebSocketExtensionRuntime runtimeExtension : runtimeExtensions) {
            if (!runtimeExtension.handlesInboundFrame(current)) {
                continue;
            }

            WebSocketFrame decoded = runtimeExtension.decodeFrame(context, current);
            if (decoded != current) {
                current.release();
                current = decoded;
            }
        }

        if (!current.isRsv1() && !current.isRsv2() && !current.isRsv3()) {
            return current;
        }

        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "RSV bits require a negotiated websocket extension.");
    }

    private WebSocketFrame applyOutboundExtensions(ProtoContext context, WebSocketFrame frame) {
        WebSocketFrame current = frame;
        for (WebSocketExtensionRuntime runtimeExtension : WebSocketUtils.runtimeExtensions(context)) {
            WebSocketFrame encoded = runtimeExtension.encodeFrame(context, current);
            if (encoded != current) {
                current.release();
                current = encoded;
            }
        }

        return current;
    }

    private void validateNegotiatedRsv(WebSocketFrame frame, List<WebSocketExtensionRuntime> runtimeExtensions) {
        if (!frame.isRsv1() && !frame.isRsv2() && !frame.isRsv3()) {
            return;
        }

        for (WebSocketExtensionRuntime runtimeExtension : runtimeExtensions) {
            if (runtimeExtension.handlesOutboundFrame(frame)) {
                return;
            }
        }

        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "RSV bits require a negotiated websocket extension.");
    }

    private void resetRuntimeExtensions(ProtoContext context, boolean close) {
        for (WebSocketExtensionRuntime runtimeExtension : WebSocketUtils.runtimeExtensions(context)) {
            if (close) {
                runtimeExtension.close();
            } else {
                runtimeExtension.reset();
            }
        }
    }
}