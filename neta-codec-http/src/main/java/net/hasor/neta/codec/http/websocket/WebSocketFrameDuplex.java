/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.HttpObject;
/**
 * Duplex frame codec for websocket traffic after the handshake.
 * <p>
 * Combines {@link WebSocketFrameDecoder} and {@link WebSocketFrameEncoder} so
 * the frame-level pipeline can be installed as a single duplex node.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-11-01
 */
public class WebSocketFrameDuplex implements ProtoDuplex<HttpObject, WebSocketFrame, WebSocketFrame, HttpObject> {
    private final WebSocketFrameDecoder decoder;
    private final WebSocketFrameEncoder encoder;

    /**
     * Create a frame duplexer fixed to V13.
     */
    public WebSocketFrameDuplex() {
        this(WebSocketVersion.V13);
    }

    /**
     * Create a frame duplexer for the specified version.
     * @param version websocket version
     */
    public WebSocketFrameDuplex(WebSocketVersion version) {
        this(version, Integer.MAX_VALUE);
    }

    /**
     * Create a frame duplexer for the specified version and maximum payload chunk length.
     * @param version websocket version
     * @param maxPayloadChunkLength maximum payload chunk length
     */
    public WebSocketFrameDuplex(WebSocketVersion version, int maxPayloadChunkLength) {
        this.decoder = new WebSocketFrameDecoder(version, maxPayloadChunkLength);
        this.encoder = new WebSocketFrameEncoder(version);
    }

    /**
     * Initialize the internal inbound and outbound codecs.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
    }

    /**
     * Activate the internal inbound and outbound codecs.
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    /**
     * Forward the event to the codec corresponding to the current direction.
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
     * Decode on the inbound side and encode on the outbound side.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<WebSocketFrame> rcvDown,//
            ProtoRcvQueue<WebSocketFrame> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    /**
     * Forward the error to the codec corresponding to the current direction.
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
     * Close the internal inbound and outbound codecs.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}
