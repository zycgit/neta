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
package net.hasor.cobble.net.channel;
import net.hasor.cobble.net.bytebuf.ByteBuf;

/**
 * A protocol stack has four endpoints: RCV_UP, RCV_DOWN, SND_UP, and SND_DOWN, these endpoints can store some data
 *
 * {@link PipeConfig} represents the amount of data stored on these endpoints
 *
 * <li>A {@link ByteBuf} endpoint indicating the number of bytes to store</li>
 * <li>The {@link PipeRcvQueue}/{@link PipeSndQueue} endpoint, which indicates the number of stored objects</li>
 * <li>default value is -1, when set to -1, It means infinite</li>
 *
 *  <p>
 *      A PipeConfig is used for only one protocol layer.
 *      An application may consist of multiple protocol layers stacked together, so Each protocol layer can be configured separately
 *  </p>
 *
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.cobble.net.channel.PipeLayer
 */
public class PipeConfig {
    private int rcvUpStackSize   = -1;
    private int rcvDownStackSize = -1;
    private int sndUpStackSize   = -1;
    private int sndDownStackSize = -1;

    public int getRcvUpStackSize() {
        return rcvUpStackSize;
    }

    public void setRcvUpStackSize(int rcvUpStackSize) {
        this.rcvUpStackSize = rcvUpStackSize;
    }

    public int getRcvDownStackSize() {
        return this.rcvDownStackSize;
    }

    public void setRcvDownStackSize(int rcvDownStackSize) {
        this.rcvDownStackSize = rcvDownStackSize;
    }

    public int getSndUpStackSize() {
        return this.sndUpStackSize;
    }

    public void setSndUpStackSize(int sndUpStackSize) {
        this.sndUpStackSize = sndUpStackSize;
    }

    public int getSndDownStackSize() {
        return this.sndDownStackSize;
    }

    public void setSndDownStackSize(int sndDownStackSize) {
        this.sndDownStackSize = sndDownStackSize;
    }
}
