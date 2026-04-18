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
import net.hasor.neta.codec.http.AbstractHttpObject;

/**
 * Default pooled implementation of {@link WebSocketFrame}.
 * <p>
 * This implementation stores the wire-level FIN flag, opcode, masking flag,
 * masking key, and payload fields used by frame encoders and decoders.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
final class DefaultWebSocketFrame extends AbstractHttpObject<WebSocketFrame> implements WebSocketFrame {
    private static final int                               RECYCLE_INDEX   = RecycleObjectPool.registerType();
    private static final ObjHandler<DefaultWebSocketFrame> RECYCLE_HANDLER = //
            new ObjHandler<DefaultWebSocketFrame>() {
                @Override
                public DefaultWebSocketFrame create() {
                    return new DefaultWebSocketFrame();
                }

                @Override
                public void free(DefaultWebSocketFrame tar) {
                    RecycleObjectPool.free(RECYCLE_INDEX, tar);
                }
            };

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

    /**
     * Return the frame opcode.
     */
    @Override
    public WebSocketOpcode opcode() {
        return this.opcode;
    }

    /**
     * Return the current object itself as the concrete fluent self type.
     */
    @Override
    protected WebSocketFrame self() {
        return this;
    }

    /**
     * Return whether the FIN bit is set.
     */
    @Override
    public boolean isFinalFragment() {
        return this.finalFragment;
    }

    /**
     * Return whether the RSV1 bit is set.
     */
    @Override
    public boolean isRsv1() {
        return this.rsv1;
    }

    /**
     * Return whether the RSV2 bit is set.
     */
    @Override
    public boolean isRsv2() {
        return this.rsv2;
    }

    /**
     * Return whether the RSV3 bit is set.
     */
    @Override
    public boolean isRsv3() {
        return this.rsv3;
    }

    /**
     * Return whether the current frame is masked.
     */
    @Override
    public boolean isMasked() {
        return this.masked;
    }

    /**
     * Return the frame masking key.
     */
    @Override
    public byte[] maskingKey() {
        return this.maskingKey;
    }

    /**
     * Return the frame payload content.
     */
    @Override
    public ByteBuf content() {
        return this.content;
    }

    @Override
    public ByteBuf transferContent() {
        ByteBuf current = this.content;
        this.content = null;
        return current;
    }

    /**
     * Return the frame payload length.
     */
    @Override
    public int payloadLength() {
        return this.payloadLength;
    }

    /**
     * Release the payload and reset the pooled frame state.
     */
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

        this.resetHttpObjectState();
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

    /**
     * Return a compact summary string for the frame.
     */
    @Override
    public String toString() {
        return "WebSocketFrame{opcode=" + this.opcode + ", fin=" + this.finalFragment + ", rsv1=" + this.rsv1 + ", rsv2=" + this.rsv2 + ", rsv3=" + this.rsv3 + ", masked=" + this.masked + ", payloadLen=" + this.payloadLength + '}';
    }

    /**
     * Create or reuse a frame instance and initialize all wire-level fields.
     * @param opcode frame opcode
     * @param finalFragment whether FIN is set
     * @param rsv1 whether RSV1 is set
     * @param rsv2 whether RSV2 is set
     * @param rsv3 whether RSV3 is set
     * @param masked whether MASK is set
     * @param maskingKey masking key, used only when masked
     * @param content payload content
     * @param payloadLength payload length represented by the current frame object
     * @return initialized frame instance
     */
    static WebSocketFrame newFrame(WebSocketOpcode opcode, boolean finalFragment, boolean rsv1, boolean rsv2, boolean rsv3, boolean masked, byte[] maskingKey, ByteBuf content, int payloadLength) {
        if (opcode == null) {
            throw new IllegalArgumentException("opcode must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        DefaultWebSocketFrame frame = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        frame.resetHttpObjectState();
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