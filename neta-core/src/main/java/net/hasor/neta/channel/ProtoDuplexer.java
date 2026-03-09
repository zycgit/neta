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
 * Bidirectional protocol node used inside a {@link ProtoStackChain}.
 * <p>A duplexer sees both directions of traffic. During a receive pass
 * ({@code isRcv == true}) it consumes messages from {@code rcvUp} and emits to
 * {@code rcvDown}; during a send pass it consumes from {@code sndUp} and emits
 * to {@code sndDown}. Adjacent duplexers are linked by these queues to form the
 * full pipeline.
 * <p>This model allows one node to coordinate decoder and encoder behaviour,
 * maintain shared state across both directions, and participate in lifecycle,
 * user-event, and error callbacks.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoHandler
 * @see ProtoConfig
 */
@FunctionalInterface
public interface ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    /**
     * Initializes this protocol node.
     */
    default void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
    }

    /**
     * Called when the channel becomes active.
     */
    default void onActive(ProtoContext context) throws Throwable {
    }

    /** Called on user-defined event. Return {@code true} to propagate, {@code false} to consume. */
    default boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        return true;
    }

    /**
     * Processes one receive or send pass.
     */
    ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable;

    /**
     * Handles an exception raised by this node or a downstream node.
     */
    default ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * Releases resources when the channel closes.
     */
    default void onClose(ProtoContext context) {
    }
}