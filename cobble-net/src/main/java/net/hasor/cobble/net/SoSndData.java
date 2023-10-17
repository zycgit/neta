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
package net.hasor.cobble.net;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.bytebuf.ByteBufAllocator;
import net.hasor.cobble.concurrent.future.Future;

/**
 * data packet
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndData {
    public static final ByteBuf            EMPTY_DATA = ByteBufAllocator.DEFAULT.arrayBuffer(0);
    private final       long               dataSize;
    private final       ByteBuf            data;
    private final       Future<NetChannel> future;
    private final       NetChannel         result;

    public SoSndData(ByteBuf data, Future<NetChannel> future, NetChannel result) {
        this.dataSize = data.readableBytes();
        this.data = data;
        this.future = future;
        this.result = result;
    }

    /**
     * packet size.
     */
    public long getDataSize() {
        return this.dataSize;
    }

    /**
     * packet has any data.
     */
    public boolean hasReadable() {
        return this.data.hasReadable();
    }

    /**
     * copy packet data to {@link ByteBuf}
     */
    public int transferTo(ByteBuf dst) {
        int len = this.data.read(dst);
        this.data.markReader();
        dst.markWriter();
        return len;
    }

    /**
     * completed callback.
     */
    public void completed() {
        if (this.data != EMPTY_DATA) {
            this.data.free();
        }
        this.future.completed(this.result);
    }

    /**
     * failed callback.
     */
    public void failed(Throwable e) {
        if (this.data != EMPTY_DATA) {
            this.data.free();
        }
        this.future.failed(e);
    }

    @Override
    public String toString() {
        return "ChannelID " + this.result.getChannelID() + ", " + this.data.toString();
    }
}