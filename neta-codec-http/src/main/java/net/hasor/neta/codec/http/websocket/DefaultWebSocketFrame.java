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
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.cobble.ref.RecycleObjectPool.ObjHandler;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Pooled default implementation of {@link WebSocketFrame}.
 * <p>
 * Holds the wire-level FIN, opcode, mask, masking key, and payload fields used by frame codecs.
 */
final class DefaultWebSocketFrame implements WebSocketFrame {
    private static final int                               RECYCLE_INDEX   = RecycleObjectPool.registerType();
    private static final ObjHandler<DefaultWebSocketFrame> RECYCLE_HANDLER = new ObjHandler<DefaultWebSocketFrame>() {
        @Override
        public DefaultWebSocketFrame create() {
            return new DefaultWebSocketFrame();
        }

        @Override
        public void free(DefaultWebSocketFrame tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };

    private int             streamId;
    private boolean         finalFragment;
    private boolean         rsv1;
    private boolean         rsv2;
    private boolean         rsv3;
    private boolean         masked;
    private boolean         active;
    private byte[]          maskingKey;
    private ByteBuf         content;
    private int             payloadLength;
    private WebSocketOpcode opcode;

    private DefaultWebSocketFrame() {
    }

    @Override
    public WebSocketOpcode opcode() {
        return this.opcode;
    }

    @Override
    public int streamId() {
        return this.streamId;
    }

    @Override
    public WebSocketFrame streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public boolean isFinalFragment() {
        return this.finalFragment;
    }

    @Override
    public boolean isRsv1() {
        return this.rsv1;
    }

    @Override
    public boolean isRsv2() {
        return this.rsv2;
    }

    @Override
    public boolean isRsv3() {
        return this.rsv3;
    }

    @Override
    public boolean isMasked() {
        return this.masked;
    }

    @Override
    public byte[] maskingKey() {
        return this.maskingKey;
    }

    @Override
    public ByteBuf content() {
        return this.content;
    }

    @Override
    public int payloadLength() {
        return this.payloadLength;
    }

    @Override
    public void release() {
        if (!this.active) {
            return;
        }
        this.active = false;
        ByteBuf current = this.content;
        if (current != null) {
            current.release();
            this.content = null;
        }
        this.streamId = 0;
        this.finalFragment = false;
        this.rsv1 = false;
        this.rsv2 = false;
        this.rsv3 = false;
        this.masked = false;
        this.maskingKey = null;
        this.payloadLength = 0;
        this.recycle();
    }

    private void recycle() {
        this.opcode = null;
        RECYCLE_HANDLER.free(this);
    }

    @Override
    public String toString() {
        return "WebSocketFrame{opcode=" + this.opcode + ", fin=" + this.finalFragment + ", rsv1=" + this.rsv1 + ", rsv2=" + this.rsv2 + ", rsv3=" + this.rsv3 + ", masked=" + this.masked + ", payloadLen=" + this.payloadLength + '}';
    }

    static WebSocketFrame newFrame(WebSocketOpcode opcode, boolean finalFragment, boolean rsv1, boolean rsv2, boolean rsv3, boolean masked, byte[] maskingKey, ByteBuf content, int payloadLength) {
        if (opcode == null) {
            throw new IllegalArgumentException("opcode must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        DefaultWebSocketFrame frame = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        frame.streamId = 0;
        frame.finalFragment = finalFragment;
        frame.rsv1 = rsv1;
        frame.rsv2 = rsv2;
        frame.rsv3 = rsv3;
        frame.masked = masked;
        frame.active = true;
        frame.maskingKey = masked ? maskingKey : null;
        frame.content = content;
        frame.payloadLength = payloadLength;
        frame.opcode = opcode;
        return frame;
    }
}