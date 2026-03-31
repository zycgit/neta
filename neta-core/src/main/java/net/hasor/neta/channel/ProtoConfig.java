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
 * Per-layer capacity configuration for the protocol stack.
 * <ul>
 *  <li>{@code rcvSlotSize} - maximum number of elements in the RCV endpoint queue</li>
 *  <li>{@code sndSlotSize} - maximum number of elements in the SND endpoint queue</li>
 *  <li>the default value {@code -1} means unbounded capacity</li>
 * </ul>
 * <p>Each {@link ProtoDuplexer} layer may have its own {@code ProtoConfig}. Use {@link #DEFAULT}
 * when an immutable unbounded singleton is needed.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoDuplexer
 */
public class ProtoConfig {
    /** Immutable default configuration with unbounded capacity. */
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

    /** Return the inbound queue capacity, where {@code -1} means unbounded. */
    public int getRcvSlotSize() {
        return this.rcvSlotSize;
    }

    /** Set the inbound queue capacity, where {@code -1} means unbounded. */
    public void setRcvSlotSize(int rcvSlotSize) {
        this.rcvSlotSize = rcvSlotSize;
    }

    /** Return the outbound queue capacity, where {@code -1} means unbounded. */
    public int getSndSlotSize() {
        return this.sndSlotSize;
    }

    /** Set the outbound queue capacity, where {@code -1} means unbounded. */
    public void setSndSlotSize(int sndSlotSize) {
        this.sndSlotSize = sndSlotSize;
    }
}
