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

/**
 * 管道
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class PipeChainRoot {

    private ByteBuf    sndByteBuf;//= this.rm.newSndDownBuffer();
    private NetChannel channel;
    //        new PipeQueue<>(), new PipeQueue<>();

    public ByteBuf[] rcvLayer(PipeContext pipeContext, ByteBuf rcvByteBuf) {
        pipeContext.clearFlash();
        //        do {
        //            status = this.pipeline.rcvLayer(this.pipeContext, rcvByteBuf);
        //        } while (status == PipeStatus.Again);
        return new ByteBuf[0];
    }

    public ByteBuf[] sndLayer(PipeContext pipeContext, Object writeData) {
        pipeContext.clearFlash();
        //
        //        PipeStatus status;
        //        do {
        //            status =
        //        } while (status == PipeStatus.Again);
        return new ByteBuf[0];
    }
}