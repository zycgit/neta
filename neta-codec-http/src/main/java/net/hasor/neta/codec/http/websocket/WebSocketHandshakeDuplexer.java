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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Public websocket opening-handshake entry for both client and server pipelines.
 * <p>
 * External code should add this duplexer to the pipeline instead of referencing
 * the package-private role-specific implementations directly. Internally, this
 * class dispatches to the server-side or client-side handshake implementation
 * according to {@code isServer}.
 * <p>
 * Typical usage:
 * <pre>{@code
 * ctx.addLast("ws-handshake", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
 * ctx.addLast("ws-handshake", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
 * }</pre>
 * <p>
 * Behavior summary:
 * <ul>
 *   <li>Server mode: before the opening handshake completes, inbound traffic is restricted to HTTP request parts and outbound traffic is restricted to HTTP response parts.</li>
 *   <li>Client mode: outbound traffic is not a generic HTTP passthrough. Request parts are intercepted first, and only requests recognized as WebSocket opening-handshake requests are remembered and released downstream.</li>
 *   <li>Client mode: inbound HTTP response parts are intercepted and aggregated while a handshake request is pending; only a valid HTTP 101 WebSocket upgrade response completes the handshake.</li>
 *   <li>After the handshake completes, both directions switch to pass-through behavior and the HTTP codec is instructed to enter transparent mode via {@code HttpThroughEvent.enable()}.</li>
 * </ul>
 * <p>
 * When {@code isServer=true}, the constructor accepting a
 * {@link WebSocketHandshakeAuthorizer} can be used to participate in the
 * authorization phase before the opening handshake is accepted.
 */
public class WebSocketHandshakeDuplexer implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> target;

    public WebSocketHandshakeDuplexer(boolean isServer, WebSocketVersion codecVersion) {
        this(isServer, codecVersion, (event, c) -> c.accept());
    }

    public WebSocketHandshakeDuplexer(boolean isServer, WebSocketVersion codecVersion, WebSocketHandshakeAuthorizer authorizer) {
        if (isServer) {
            this.target = new WebSocketHandshake4Server(codecVersion, authorizer);
        } else {
            this.target = new WebSocketHandshake4Client(codecVersion);
        }
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.target.onInit(name, rcvSize, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.target.onActive(context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        return this.target.onUserEvent(context, event, isRcv);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        return this.target.onMessage(context, isRcv, rcvUp, rcvDown, sndUp, sndDown);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return this.target.onError(context, isRcv, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.target.onClose(context);
    }
}