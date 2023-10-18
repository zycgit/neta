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
import net.hasor.cobble.net.bytebuf.ByteBufAllocator;

/**
 * 管道
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeChainRoot {

    private ByteBuf    sndByteBuf;//= this.rm.newSndDownBuffer();
    private NetChannel channel;
    //        new PipeQueue<>(), new PipeQueue<>();

    public ByteBuf[] rcvLayer(PipeContextImpl pipeContext, ByteBuf rcvByteBuf) {
        String line = rcvByteBuf.readLine();
        rcvByteBuf.markReader();

        if (line != null) {
            ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + line + "\n").getBytes());
            buf.markWriter();
            System.out.println("rcvChannel " + pipeContext.channel().getChannelID() + ", data=" + line);

            pipeContext.channel().sendData("hello");

            return new ByteBuf[] { buf };
        }
        return new ByteBuf[0];
    }

    public ByteBuf[] sndLayer(PipeContextImpl pipeContext, Object writeData) {
        pipeContext.clearFlash();

        ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + writeData + "\n").getBytes());
        buf.markWriter();
        return new ByteBuf[] { buf };
        //
        //        PipeStatus status;
        //        do {
        //            status =
        //        } while (status == PipeStatus.Again);
    }
}