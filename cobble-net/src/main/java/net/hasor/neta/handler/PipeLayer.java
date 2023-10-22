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
import net.hasor.neta.channel.PipeContext;

import java.io.IOException;

/**
 * PipeLayer is a Duplexer, The data flow direction is identified by the isRcv parameter.
 *
 * A protocol stack has four endpoints: RCV_UP, RCV_DOWN, SND_UP, and SND_DOWN, these endpoints can store some data.
 *
 * Some of these endpoints come from Buffers, e.g, RCV_UP is located low on the stack.
 *
 * SND_DOWN is the temporary storage used to receive the output of the pipeline.
 *
 *  When there are multiple PipeLayer layers, the endpoints are linked, e.g, first {@link PipeLayer} RCV_DOWN -> next {@link PipeLayer} RCV_UP
 *
 * <pre>
 *        /-------------------------\      /-------------------------\
 *     -> | RCV_UP         RCV_DOWN |  ->  | RCV_UP         RCV_DOWN |  ->
 *        |                         |      |                         |
 * Net    |      PipeLayer (1)      |      |      PipeLayer (2)      |     APP
 *        |                         |      |                         |
 *     <- | SND_DOWN         SND_UP |  <-  | SND_DOWN         SND_UP |  <-
 *        \-------------------------/      \-------------------------/
 * </pre>
 *
 *  <p>
 *      This design means that during any rcv/snd, upstream and downstream of the pipeline can be operated.
 *  </p>
 *
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.neta.handler.PipeHandler
 * @see net.hasor.neta.handler.PipeConfig
 */
@FunctionalInterface
public interface PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {

    /**
     * Initialize the protocol stack
     */
    default void initLayer(PipeContext pipeContext) throws Exception {
    }

    /**
     * process data the protocol stack, param isRcv = true is RCV_UP to RCV_DOWN
     */
    PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeSndQueue<RCV_DOWN> rcvDown, PipeRcvQueue<SND_UP> sndUp, PipeSndQueue<SND_DOWN> sndDown) throws IOException;

    /**
     * release protocol stack
     */
    default void releaseLayer(PipeContext pipeContext) {
    }
}