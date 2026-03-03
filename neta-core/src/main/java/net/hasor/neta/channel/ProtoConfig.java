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
package net.hasor.neta.channel;

/**
 * Per-layer capacity configuration for a protocol stack layer.
 * <ul>
 *  <li>{@code rcvSlotSize} — max items in the RCV endpoint queue</li>
 *  <li>{@code sndSlotSize} — max items in the SND endpoint queue</li>
 *  <li>Default {@code -1} means unlimited capacity</li>
 * </ul>
 * <p>Each {@link ProtoDuplexer} layer can have its own {@code ProtoConfig}.
 * Use {@link #DEFAULT} for the immutable unlimited-capacity singleton.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoDuplexer
 */
public class ProtoConfig {
    /** Immutable default config with unlimited capacity. */
    public static final ProtoConfig DEFAULT = new ProtoConfig() {
        @Override
        public void setRcvSlotSize(int rcvSlotSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSndSlotSize(int sndSlotSize) {
            throw new UnsupportedOperationException();
        }
    };

    private int rcvSlotSize = -1;
    private int sndSlotSize = -1;

    /** Returns the receive-side queue capacity ({@code -1} for unlimited). */
    public int getRcvSlotSize() {
        return this.rcvSlotSize;
    }

    /** Set the receive-side queue capacity ({@code -1} for unlimited). */
    public void setRcvSlotSize(int rcvSlotSize) {
        this.rcvSlotSize = rcvSlotSize;
    }

    /** Returns the send-side queue capacity ({@code -1} for unlimited). */
    public int getSndSlotSize() {
        return this.sndSlotSize;
    }

    /** Set the send-side queue capacity ({@code -1} for unlimited). */
    public void setSndSlotSize(int sndSlotSize) {
        this.sndSlotSize = sndSlotSize;
    }
}
