/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.h3;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.quic.QuicStreamChannel;
import net.hasor.neta.codec.http.HttpObject;
/**
 * HTTP/3 message-layer duplex codec that combines frame-to-object decoding and object-to-frame encoding.
 * <p>
 * Pipeline view:
 * <pre>
 * inbound: Http3Frame -> Http3ObjectDuplexe -> HttpObject
 * outbound: HttpObject -> Http3ObjectDuplexe -> Http3Frame
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 * Http3Settings settings = Http3Settings.defaultLocalSettings(true);
 * ctx.addLast("h3-frame", new Http3FrameDuplexe(true, settings));
 * ctx.addLast("h3-object", new Http3ObjectDuplexe(true, settings));
 * ctx.addLast("aggregator", new HttpServerDuplexeAggregator(1048576));
 * </pre>
 */
public class Http3ObjectDuplexe implements ProtoDuplexer<Http3Frame, HttpObject, HttpObject, Http3Frame> {
    private static final Logger           logger = Logger.getLogger(Http3ObjectDuplexe.class);
    private final boolean                 serverMode;
    private final Http3FrameToHttpDecoder decoder;
    private final Http3HttpToFrameEncoder encoder;

    public Http3ObjectDuplexe(boolean serverMode) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode));
    }

    public Http3ObjectDuplexe(boolean serverMode, int maxTableSize, int maxHeaderListSize) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode, maxTableSize, maxHeaderListSize, Http3Settings.DEFAULT_LOCAL_QPACK_BLOCKED_STREAMS));
    }

    public Http3ObjectDuplexe(boolean serverMode, Http3Settings localSettings) {
        this.serverMode = serverMode;
        Http3Settings settings = localSettings != null ? new Http3Settings(localSettings) : Http3Settings.defaultLocalSettings(serverMode);
        this.decoder = new Http3FrameToHttpDecoder(serverMode, settings);
        this.encoder = new Http3HttpToFrameEncoder(serverMode, settings);
    }

    private static long resolveH3ErrorCode(long code) {
        if (code == Http3ResetEvent.CANCEL) {
            return Http3ErrorCode.H3_REQUEST_CANCELLED;
        } else if (code == Http3ResetEvent.INTERNAL_ERROR) {
            return Http3ErrorCode.H3_INTERNAL_ERROR;
        } else if (code == Http3ResetEvent.REFUSED) {
            return Http3ErrorCode.H3_REQUEST_REJECTED;
        } else if (code < 0) {
            return Http3ErrorCode.H3_INTERNAL_ERROR;
        }
        return code;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
        Http3DecoderContent decoderContent = context.context(Http3DecoderContent.class);
        context.context(Http3Context.class, new Http3ContextImpl(this.serverMode, decoderContent));
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (event.getEventType() == Http3ResetEvent.class) {
            Http3ResetEvent reset = (Http3ResetEvent) event.getData();
            long streamId = reset.streamId();
            long errorCode = resolveH3ErrorCode(reset.errorCode());
            Http3DecoderContent decoderState = context.context(Http3DecoderContent.class);
            if (decoderState != null) {
                decoderState.closeStream(streamId);
                decoderState.removeFromResponseQueue(streamId);
            }
            SoChannel<?> channel = context.getChannel();
            if (channel instanceof QuicStreamChannel) {
                ((QuicStreamChannel) channel).sendReset(errorCode, 0L);
            }
            if (context.getConfig().isPrintLog() && channel != null) {
                logger.info("[H3-SND] ch=" + channel.getChannelId() + " RESET_STREAM errorCode=0x" + Long.toHexString(errorCode) + " (via Event)");
            }
            return false;
        }
        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,             //
            ProtoRcvQueue<Http3Frame> rcvUp, ProtoSndQueue<HttpObject> rcvDown,   //
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<Http3Frame> sndDown) throws Throwable {
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
        }

        SoChannel<?> channel = context.getChannel();
        if (channel instanceof QuicStreamChannel) {
            try {
                Http3DecoderContent decoderState = context.context(Http3DecoderContent.class);
                Http3EncoderContent encoderState = context.context(Http3EncoderContent.class);
                long streamId = encoderState != null ? encoderState.currentStreamId() : -1L;
                if (streamId >= 0 && decoderState != null) {
                    decoderState.closeStream(streamId);
                    decoderState.removeFromResponseQueue(streamId);
                }
                ((QuicStreamChannel) channel).sendReset(Http3ErrorCode.H3_INTERNAL_ERROR, 0L);
                logger.warn("[H3-SND] ch=" + channel.getChannelId() + " encoding error, sent RESET_STREAM(H3_INTERNAL_ERROR): " + e.getMessage());
                return ProtoStatus.Next;
            } catch (Throwable ignore) {
            }
        }
        return this.encoder.onError(context, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}