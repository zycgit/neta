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
 *         ┏━━━━━━━━━━━━━━━━━━━━━━━━━┓    ┏━━━━━━━━━━━━━━━━━━━━━━━━━┓
 * DATA -> ┃ RCV_UP         RCV_DOWN ┃ -> ┃ RCV_UP         RCV_DOWN ┃  -> ...
 *         ┃                         ┃    ┃                         ┃
 *         ┃      PipeLayer (1)      ┃    ┃      PipeLayer (2)      ┃
 *         ┃                         ┃    ┃                         ┃
 *  ... <- ┃ SND_DOWN         SND_UP ┃ <- ┃ SND_DOWN         SND_UP ┃  <- DATA
 *         ┗━━━━━━━━━━━━━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━━━━━━━━━━━━━┛
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
     * Initialize the protocol stack, when the Connected.
     */
    default void init(PipeContext context) throws Throwable {
    }

    /**
     * process data the protocol stack.
     *
     * After the doLayer method returns, The {@link PipeRcvQueue#rcvSubmit()}/{@link PipeSndQueue#sndSubmit()} method of (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) will be called.
     *
     * <ul>
     *  <li>When the method throws, (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) keep state, and call {@link #doError(PipeContext, boolean, Throwable, PipeExceptionHolder)}.</li>
     * </ul>
     */
    PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeSndQueue<RCV_DOWN> rcvDown, PipeRcvQueue<SND_UP> sndUp, PipeSndQueue<SND_DOWN> sndDown) throws Throwable;

    /**
     * Gets called if a Throwable was thrown. If an exception occurs, piple executes in the following way.
     *
     * After the doError method returns, The {@link PipeRcvQueue#rcvReset()}/{@link PipeSndQueue#sndReset()} method of (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) will be called
     *
     * <pre>
     *  ... -> doLayer -> doLayer -> Exception
     *                                   |
     *                                doError -> doError -> doError...
     * </pre>
     *
     * <p>You can clear the exception flag with the {@link PipeExceptionHolder#clear()} method, and piple execution will continue normally</p>
     *
     * <pre>
     *  ... -> doLayer -> doError -> doError(invoker clear) -> doLayer -> ...
     * </pre>
     */
    default PipeStatus doError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHolder eh) throws Throwable {
        return PipeStatus.Next;
    }

    /**
     * release protocol stack, connection close.
     */
    default void release(PipeContext context) {
    }
}