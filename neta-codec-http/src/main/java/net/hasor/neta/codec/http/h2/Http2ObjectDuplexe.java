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
 * HTTP/2 message-layer duplex codec that combines frame-to-object decoding and object-to-frame encoding.
 * <p>
 * This class packages {@link Http2ObjectDecoder} and {@link Http2ObjectEncoder} into a single
 * bidirectional pipeline node and serves as the standard entry point for HTTP/2 semantic traffic.
 * <p>
 * It is also the boundary where HTTP/2 frame streams are lifted into an {@link HttpObject} stream.
 * An "HttpObject stream" means a single stream is exposed as an ordered sequence that follows HTTP
 * message semantics rather than staying at raw frame level. HEADERS, CONTINUATION, and DATA are
 * reorganized into request or response objects, header blocks, content chunks, optional trailing
 * headers, and final end markers. Control frames are handled at this layer as protocol actions or
 * are surfaced as HTTP/2 events.
 * <p>
 * Pipeline view:
 * <pre>
 *   inbound:  Http2Frame  -> Http2ObjectDuplexe -> HttpObject
 *   outbound: HttpObject  -> Http2ObjectDuplexe -> Http2Frame
 * </pre>
 * <p>
 * Recommended usage falls into two scenarios depending on the processing goal:
 * <p>
 * Scenario 1: stream the HTTP/2 message parts.
 * <pre>
 *   ctx.addLast("h2-frame", new Http2FrameDuplexe(true));
 *   ctx.addLast("h2-object", new Http2ObjectDuplexe(true));
 *   ctx.addLast("handler", streamPartHandler);
 * </pre>
 * This mode works directly with the {@link HttpObject} stream and suits proxy forwarding,
 * incremental processing, large bodies, server push handling, or any case where you do not want
 * to aggregate a full stream payload first.
 * <p>
 * Scenario 2: aggregate the full HTTP/2 message.
 * <pre>
 *   ctx.addLast("h2-frame", new Http2FrameDuplexe(true));
 *   ctx.addLast("h2-object", new Http2ObjectDuplexe(true));
 *   ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1048576));
 *   ctx.addLast("handler", fullMessageHandler);
 * </pre>
 * Add an aggregator only when upper-layer logic explicitly needs a {@code FullHttpRequest} or
 * {@code FullHttpResponse}, such as unified signature verification, direct object mapping, or
 * processing that naturally depends on the full message body.
 * <p>
 * When a {@link ProtoRoutingControl} is supplied, the inbound side also checks whether the current
 * HTTP/2 branch owns an upgraded route seed. If present, that seed is emitted as an {@link HttpObject}
 * before normal frame decoding continues, so the h2c-upgraded stream 1 request can still enter the
 * standard HTTP/2 branch and stream partition flow.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2ObjectDuplexe implements ProtoDuplexer<Http2Frame, HttpObject, HttpObject, Http2Frame> {
    private final Http2ObjectDecoder  decoder;
    private final Http2ObjectEncoder  encoder;
    private final ProtoRoutingControl routingControl;

    /**
     * Creates a message-layer duplex codec for the given endpoint role using default local settings.
     */
    public Http2ObjectDuplexe(boolean serverMode) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode), null);
    }

    /**
     * Creates a message-layer duplex codec with route-seed support for an upgraded HTTP/2 branch.
     */
    public Http2ObjectDuplexe(boolean serverMode, ProtoRoutingControl routingControl) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode), routingControl);
    }

    /**
     * Creates a message-layer duplex codec for the given endpoint role and local settings.
     */
    public Http2ObjectDuplexe(boolean serverMode, Http2Settings localSettings) {
        this(serverMode, localSettings, null);
    }

    /**
     * Creates a message-layer duplex codec for the given endpoint role with explicit settings and routing control.
     */
    public Http2ObjectDuplexe(boolean serverMode, Http2Settings localSettings, ProtoRoutingControl routingControl) {
        localSettings = localSettings != null ? new Http2Settings(localSettings) : Http2Settings.defaultLocalSettings(serverMode);
        this.decoder = new Http2ObjectDecoder(serverMode, localSettings);
        this.encoder = new Http2ObjectEncoder(serverMode, localSettings);
        this.routingControl = routingControl;
    }

    /**
     * Initializes the codecs on both inbound and outbound sides.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
    }

    /**
     * Activates the codecs on both inbound and outbound sides.
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    /**
     * Dispatches events by direction and routes HTTP/2 control events to the outbound encoder.
     */
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

    /**
     * Processes messages according to direction and flushes pending control frames after inbound decoding.
     */
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

    /**
     * Processes exceptions according to direction.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    /**
     * Closes and releases codec state on both inbound and outbound sides.
     */
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
