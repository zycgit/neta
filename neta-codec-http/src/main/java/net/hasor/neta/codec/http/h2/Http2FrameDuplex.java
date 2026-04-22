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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * HTTP/2 frame-layer duplex codec that combines binary frame decoding and frame encoding.
 * <p>
 * This class packages {@link Http2FrameDecoder} and {@link Http2FrameEncoder} into a single
 * bidirectional pipeline node and serves as the standard entry point for HTTP/2 binary framing traffic.
 * <p>
 * It is also the first point where connection bytes are lifted into an {@link Http2Frame} stream.
 * An "Http2Frame stream" means the transport byte stream is split into an ordered sequence of
 * complete HTTP/2 frames that still preserve frame-layer semantics. Preface handling, the fixed
 * 9-byte frame header, payload extraction, and basic frame-size validation all happen here, while
 * higher-level HTTP message reconstruction is left to {@link Http2ObjectDuplex}.
 * <p>
 * Pipeline view:
 * <pre>
 *   inbound:  socket bytes -> Http2FrameDuplex -> Http2Frame
 *   outbound: Http2Frame   -> Http2FrameDuplex -> socket bytes
 * </pre>
 * <p>
 * Recommended usage falls into two scenarios depending on the processing goal:
 * <p>
 * Scenario 1: work at raw frame level.
 * <pre>
 *   ctx.addLast("h2-frame", new Http2FrameDuplex(true));
 *   ctx.addLast("handler", frameHandler);
 * </pre>
 * This mode suits protocol inspection, low-level testing, custom frame handling, or debugging work
 * where the application needs direct access to {@link Http2Frame} objects.
 * <p>
 * Scenario 2: continue into the HTTP/2 message layer.
 * <pre>
 *   ctx.addLast("h2-frame", new Http2FrameDuplex(true));
 *   ctx.addLast("h2-object", new Http2ObjectDuplex(true));
 *   ctx.addLast("handler", httpHandler);
 * </pre>
 * Use this mode for normal request and response processing, where frame streams should be lifted
 * into {@link net.hasor.neta.codec.http.HttpObject} messages and HTTP/2 events.
 * <p>
 * The inbound side validates connection preface handling according to endpoint role, while the
 * outbound side serializes complete {@link Http2Frame} objects back to the wire format.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-21
 */
public class Http2FrameDuplex implements ProtoDuplex<ByteBuf, Http2Frame, Http2Frame, ByteBuf> {
    private final Http2FrameDecoder decoder;
    private final Http2FrameEncoder encoder;

    /**
     * Creates a client-side frame codec.
     */
    public Http2FrameDuplex() {
        this(false);
    }

    /**
     * Creates a frame codec for the given endpoint role.
     * @param serverMode when {@code true}, server mode is used and the inbound side expects the client preface
     */
    public Http2FrameDuplex(boolean serverMode) {
        this(serverMode, new Http2Settings());
    }

    /**
     * Creates a frame codec for the given endpoint role with explicit local settings.
     * @param serverMode whether the codec runs in server mode
     * @param settings local HTTP/2 settings used by the frame decoder
     */
    public Http2FrameDuplex(boolean serverMode, Http2Settings settings) {
        this.decoder = new Http2FrameDecoder(serverMode, settings);
        this.encoder = new Http2FrameEncoder();
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
     * Dispatches events by direction.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    /**
     * Processes messages according to direction.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,          //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Http2Frame> rcvDown,   //
            ProtoRcvQueue<Http2Frame> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
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
}
