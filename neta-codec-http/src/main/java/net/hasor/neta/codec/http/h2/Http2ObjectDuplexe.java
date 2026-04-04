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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpProtocolStateException;

/**
 * Bidirectional semantic message-layer codec for HTTP/2 traffic.
 * <p>
 * Pairs {@link Http2ObjectDecoder} and {@link Http2ObjectEncoder} so higher layers
 * can work with downstream HTTP/2 payload messages instead of raw frames, while
 * control frames stay internal or surface as network events.
 * <p>
 * The message layer is the main protocol boundary in Neta's HTTP/2 stack. It owns
 * header-block reassembly, control-frame interpretation, automatic ACK and window
 * maintenance, and the split between public payload messages and internal protocol
 * actions.
 * <p>
 * When a {@link ProtoRoutingControl} is provided, inbound rounds also check whether the
 * current h2 branch received a promoted route seed. If present, the seed is emitted as an
 * {@link HttpObject} before normal decoded traffic so h2c-upgraded stream 1 requests can
 * continue through the standard HTTP/2 branch and stream partition flow.
 * <p>
 * This is the intended public entry point for the HTTP/2 message layer. The
 * standalone message encoder and decoder remain internal building blocks.
 */
public class Http2ObjectDuplexe implements ProtoDuplexer<Http2Frame, HttpObject, HttpObject, Http2Frame> {
    private final Http2ObjectDecoder  decoder;
    private final Http2ObjectEncoder  encoder;
    private final ProtoRoutingControl routingControl;

    /** Creates a message duplexe with default HPACK limits for the specified endpoint role. */
    public Http2ObjectDuplexe(boolean serverMode) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode), null);
    }

    /** Creates a message duplexe with route-seed support for upgraded h2 branches. */
    public Http2ObjectDuplexe(boolean serverMode, ProtoRoutingControl routingControl) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode), routingControl);
    }

    public Http2ObjectDuplexe(boolean serverMode, Http2Settings localSettings) {
        this(serverMode, localSettings, null);
    }

    public Http2ObjectDuplexe(boolean serverMode, Http2Settings localSettings, ProtoRoutingControl routingControl) {
        localSettings = localSettings != null ? new Http2Settings(localSettings) : Http2Settings.defaultLocalSettings(serverMode);
        this.decoder = new Http2ObjectDecoder(serverMode, localSettings);
        this.encoder = new Http2ObjectEncoder(serverMode, localSettings);
        this.routingControl = routingControl;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (event.getEventType() == Http2PingEvent.class || event.getEventType() == Http2GoawayEvent.class || event.getEventType() == Http2ResetEvent.class || event.getEventType() == Http2PriorityEvent.class || event.getEventType() == Http2PushPromiseEvent.class) {
            return this.encoder.onEvent(context, event);
        }
        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,           //
            ProtoRcvQueue<Http2Frame> rcvUp, ProtoSndQueue<HttpObject> rcvDown, //
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<Http2Frame> sndDown) throws Throwable {
        if (isRcv) {
            this.emitRoutingSeed(rcvDown);
            ProtoStatus decodeStatus = this.decoder.onMessage(context, rcvUp, rcvDown);
            ProtoStatus flushStatus = this.encoder.flushPendingFrames(context, sndDown);
            return decodeStatus == ProtoStatus.Next || flushStatus == ProtoStatus.Next ? ProtoStatus.Next : ProtoStatus.Stop;
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }

    private void emitRoutingSeed(ProtoSndQueue<HttpObject> rcvDown) {
        if (this.routingControl == null || !this.routingControl.hasSeed()) {
            return;
        }

        Object seed = this.routingControl.takeSeed();
        if (seed == null) {
            return;
        }

        if (!(seed instanceof HttpObject)) {
            SoUtils.release(seed);
            throw new HttpProtocolStateException("HTTP/2: route seed must be an HttpObject, but was " + seed.getClass().getName());
        }

        rcvDown.offerMessage((HttpObject) seed);
    }
}
