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
package net.hasor.neta.codec.http3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.quic.QuicFrameDecoder;
import net.hasor.neta.codec.quic.QuicFrameEncoder;

/**
 * A server-side HTTP/3 codec that combines {@link QuicFrameDecoder},
 * {@link Http3FrameDecoder}, {@link Http3FrameEncoder}, and {@link QuicFrameEncoder}
 * into a single bidirectional handler.
 * <p>
 * RCV path: ByteBuf → QuicFrameDecoder → ByteBuf(per-stream) → Http3FrameDecoder → HttpObject
 * SND path: HttpObject → Http3FrameEncoder → ByteBuf(per-stream) → QuicFrameEncoder → ByteBuf
 * <p>
 * Internal bridge queues connect the QUIC and HTTP/3 layers within a single duplexer,
 * so the application sees only ByteBuf ↔ HttpObject at the pipeline boundary.
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * and HTTP/2 codecs, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h3", new Http3ServerDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http3ServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {

    private final QuicFrameDecoder  quicDecoder;
    private final Http3FrameDecoder h3Decoder;
    private final Http3FrameEncoder h3Encoder;
    private final QuicFrameEncoder  quicEncoder;

    /** Creates a server-side HTTP/3 codec. */
    public Http3ServerDuplexe() {
        this.quicDecoder = new QuicFrameDecoder(true);
        this.h3Decoder = new Http3FrameDecoder(true);
        this.h3Encoder = new Http3FrameEncoder(true);
        this.quicEncoder = new QuicFrameEncoder(true);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.quicDecoder.onInit(context);
        this.h3Decoder.onInit(context);
        this.h3Encoder.onInit(context);
        this.quicEncoder.onInit(context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.quicDecoder.onActive(context);
        this.h3Decoder.onActive(context);
        this.h3Encoder.onActive(context);
        this.quicEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV: ByteBuf → QuicFrameDecoder → bridge(ByteBuf) → Http3FrameDecoder → HttpObject
            BridgeQueue<ByteBuf> bridge = new BridgeQueue<>();
            ProtoStatus status = this.quicDecoder.onMessage(context, rcvUp, bridge);
            if (status == ProtoStatus.Stop && !bridge.hasMore()) {
                return ProtoStatus.Stop;
            }
            bridge.sndSubmit();
            return this.h3Decoder.onMessage(context, bridge, rcvDown);
        } else {
            // SND: HttpObject → Http3FrameEncoder → bridge(ByteBuf) → QuicFrameEncoder → ByteBuf
            BridgeQueue<ByteBuf> bridge = new BridgeQueue<>();
            ProtoStatus status = this.h3Encoder.onMessage(context, sndUp, bridge);
            if (status == ProtoStatus.Stop && !bridge.hasMore()) {
                return ProtoStatus.Stop;
            }
            bridge.sndSubmit();
            return this.quicEncoder.onMessage(context, bridge, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.h3Decoder.onError(context, e, eh);
        } else {
            return this.h3Encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.quicDecoder.onClose(context);
        this.h3Decoder.onClose(context);
        this.h3Encoder.onClose(context);
        this.quicEncoder.onClose(context);
    }

    /**
     * Internal bridge queue that implements both {@link ProtoSndQueue} and
     * {@link ProtoRcvQueue} to connect two handler stages within a duplexer.
     * <p>
     * Data is written via the {@link ProtoSndQueue} interface by the upstream
     * handler, then read via the {@link ProtoRcvQueue} interface by the
     * downstream handler.
     */
    private static class BridgeQueue<T> implements ProtoSndQueue<T>, ProtoRcvQueue<T> {
        private final List<T> buffer    = new ArrayList<>();
        private       int     readIndex = 0;

        // ---- ProtoSndQueue ----
        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int slotSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean hasCommit() {
            return readIndex < buffer.size();
        }

        @Override
        public ProtoSndQueue<T> sndSubmit() {
            return this;
        }

        @Override
        public ProtoSndQueue<T> sndReset() {
            buffer.subList(readIndex, buffer.size()).clear();
            return this;
        }

        @Override
        public int offerMessage(T[] offerList) {
            if (offerList == null)
                return 0;
            int count = 0;
            for (T item : offerList) {
                if (item != null) {
                    buffer.add(item);
                    count++;
                }
            }
            return count;
        }

        @Override
        public int offerMessage(List<T> offerList) {
            if (offerList == null)
                return 0;
            int count = 0;
            for (T item : offerList) {
                if (item != null) {
                    buffer.add(item);
                    count++;
                }
            }
            return count;
        }

        @Override
        public int offerMessage(ProtoRcvQueue<T> offerList) {
            if (offerList == null)
                return 0;
            int count = 0;
            while (offerList.hasMore()) {
                T item = offerList.takeMessage();
                if (item != null) {
                    buffer.add(item);
                    count++;
                }
            }
            return count;
        }

        // ---- ProtoRcvQueue ----
        @Override
        public int queueSize() {
            return buffer.size() - readIndex;
        }

        @Override
        public ProtoRcvQueue<T> rcvSubmit() {
            if (readIndex > 0) {
                buffer.subList(0, readIndex).clear();
                readIndex = 0;
            }
            return this;
        }

        @Override
        public ProtoRcvQueue<T> rcvReset() {
            readIndex = 0;
            return this;
        }

        @Override
        public List<T> takeMessage(int cnt) {
            if (cnt <= 0 || readIndex >= buffer.size())
                return Collections.emptyList();
            int available = Math.min(cnt, buffer.size() - readIndex);
            List<T> result = new ArrayList<>(buffer.subList(readIndex, readIndex + available));
            readIndex += available;
            return result;
        }

        @Override
        public List<T> peekMessage(int cnt) {
            if (cnt <= 0 || readIndex >= buffer.size())
                return Collections.emptyList();
            int available = Math.min(cnt, buffer.size() - readIndex);
            return new ArrayList<>(buffer.subList(readIndex, readIndex + available));
        }

        @Override
        public void skipMessage(int cnt) {
            if (cnt > 0) {
                readIndex = Math.min(readIndex + cnt, buffer.size());
            }
        }
    }
}
