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
package net.hasor.neta.handler;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * {@link PipeConfig} represents the amount of data stored on these endpoints
 * <li>A {@link ByteBuf} endpoint indicating the number of bytes to store</li>
 * <li>The {@link PipeRcvQueue}/{@link PipeSndQueue} endpoint, which indicates the number of stored objects</li>
 * <li>default value is -1, when set to -1, It means infinite</li>
 * <p>
 * A PipeConfig is used for only one protocol layer.
 * An application may consist of multiple protocol layers stacked together, so Each protocol layer can be configured separately
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see PipeDuplex
 */
public class PipeConfig {
    public static final PipeConfig DEFAULT = new PipeConfig() {
        @Override
        public void setPipeRcvDownStackSize(int pipeRcvDownStackSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPipeSndUpStackSize(int pipeSndUpStackSize) {
            throw new UnsupportedOperationException();
        }
    };

    private int pipeRcvDownStackSize = -1;
    private int pipeSndUpStackSize   = -1;

    public int getPipeRcvDownStackSize() {
        return this.pipeRcvDownStackSize;
    }

    public void setPipeRcvDownStackSize(int pipeRcvDownStackSize) {
        this.pipeRcvDownStackSize = pipeRcvDownStackSize;
    }

    public int getPipeSndUpStackSize() {
        return this.pipeSndUpStackSize;
    }

    public void setPipeSndUpStackSize(int pipeSndUpStackSize) {
        this.pipeSndUpStackSize = pipeSndUpStackSize;
    }
}
