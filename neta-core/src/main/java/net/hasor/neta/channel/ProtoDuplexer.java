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
 * {@link ProtoDuplexer} is a Duplexer handler, The data flow direction is identified by the isRcv parameter.
 * A protocol stack has four endpoints: RCV_UP, RCV_DOWN, SND_UP, and SND_DOWN, these endpoints can store some data.
 * Some of these endpoints come from Buffers, e.g, RCV_UP is located low on the stack.
 * SND_DOWN is the temporary storage used to receive the output of the ProtoStack.
 * When there are multiple {@link ProtoDuplexer} layers, the endpoints are linked, e.g, first {@link ProtoDuplexer} RCV_DOWN → next {@link ProtoDuplexer} RCV_UP
 * <pre>
 *        ┏━━━━━━━━━━━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
 * DATA → ┃ RCV_UP        RCV_DOWN ┃ → ┃ RCV_UP        RCV_DOWN ┃  → ...
 *        ┃                        ┃   ┃                        ┃
 *        ┃   Protocol Stack (1)   ┃   ┃   Protocol Stack (2)   ┃
 *        ┃                        ┃   ┃                        ┃
 *  ... ← ┃ SND_DOWN        SND_UP ┃ ← ┃ SND_DOWN        SND_UP ┃  ← DATA
 *        ┗━━━━━━━━━━━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
 * </pre>
 * <p>
 * This design means that during any rcv/snd, upstream and downstream of the ProtoStack can be operated.
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoHandler
 * @see ProtoConfig
 */
@FunctionalInterface
public interface ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    /**
     * Initialize the protocol stack.
     */
    default void onInit(ProtoContext context) throws Throwable {
    }

    /**
     * when the Connected.
     * <p>The rcvDown and sndDown queues allow handlers to produce data during activation.
     * Data written to sndDown will be collected and sent to the remote peer before any RCV processing.</p>
     * @param context the protocol context
     * @param rcvDown the RCV downstream queue (output towards next handler in RCV direction)
     * @param sndDown the SND downstream queue (output towards previous handler / socket in SND direction)
     */
    default void onActive(ProtoContext context, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
    }

    default boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        return true;
    }

    /**
     * process data the protocol stack.
     * After the doLayer method returns, The {@link ProtoRcvQueue#rcvSubmit()}/{@link ProtoSndQueue#sndSubmit()} method of (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) will be called.
     * <ul>
     *  <li>When the method throws, (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) keep state, and call {@link #onError(ProtoContext, boolean, Throwable, ProtoExceptionHolder)}.</li>
     * </ul>
     */
    ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable;

    /**
     * Gets called if a Throwable was thrown. If an exception occurs, ProtoStack executes in the following way.
     * After the doError method returns, The {@link ProtoRcvQueue#rcvReset()}/{@link ProtoSndQueue#sndReset()} method of (RCV_UP, RCV_DOWN, SND_UP, SND_DOWN) will be called
     * <pre>
     *  ... -> onMessage -> onMessage -> Exception
     *                                       |
     *                                    onError -> onError -> onError...
     * </pre>
     * <p>You can clear the exception flag with the {@link ProtoExceptionHolder#clear()} method, and ProtoStack execution will continue normally</p>
     * <pre>
     *  ... -> onMessage -> onError -> onError(invoker clear) -> onMessage -> ...
     * </pre>
     */
    default ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * release protocol stack, connection close.
     */
    default void onClose(ProtoContext context) {
    }
}