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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * {@link ProtoConfig} represents the amount of data stored on these endpoints
 * <li>A {@link ByteBuf} endpoint indicating the number of bytes to store</li>
 * <li>The {@link ProtoRcvQueue}/{@link ProtoSndQueue} endpoint, which indicates the number of stored objects</li>
 * <li>default value is -1, when set to -1, It means infinite</li>
 * <p>
 * A ProtoConfig is used for only one protocol layer.
 * An application may consist of multiple protocol layers stacked together, so Each protocol layer can be configured separately
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoDuplexer
 */
public class ProtoConfig {
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

    public int getRcvSlotSize() {
        return this.rcvSlotSize;
    }

    public void setRcvSlotSize(int rcvSlotSize) {
        this.rcvSlotSize = rcvSlotSize;
    }

    public int getSndSlotSize() {
        return this.sndSlotSize;
    }

    public void setSndSlotSize(int sndSlotSize) {
        this.sndSlotSize = sndSlotSize;
    }
}
