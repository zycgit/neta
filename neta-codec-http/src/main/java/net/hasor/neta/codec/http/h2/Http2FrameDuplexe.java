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

/**
 * Bidirectional frame-layer codec for HTTP/2 traffic.
 * <p>
 * Pairs {@link Http2FrameDecoder} and {@link Http2FrameEncoder} so frame pipelines
 * can be wired as a single duplex node.
 */
public class Http2FrameDuplexe implements ProtoDuplexer<ByteBuf, Http2Frame, Http2Frame, ByteBuf> {
    private final Http2FrameDecoder decoder;
    private final Http2FrameEncoder encoder;

    /** Creates a client-side frame duplexe. */
    public Http2FrameDuplexe() {
        this(false);
    }

    /**
     * Creates a frame duplexe for the specified endpoint role.
     * @param serverMode true when decoding server-side inbound traffic that expects the client preface
     */
    public Http2FrameDuplexe(boolean serverMode) {
        this(serverMode, new Http2Settings());
    }

    public Http2FrameDuplexe(boolean serverMode, Http2Settings settings) {
        this.decoder = new Http2FrameDecoder(serverMode, settings);
        this.encoder = new Http2FrameEncoder();
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
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onUserEvent(context, event);
        } else {
            return this.encoder.onUserEvent(context, event);
        }
    }

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
}
