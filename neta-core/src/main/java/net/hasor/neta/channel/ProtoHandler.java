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
 * Unidirectional protocol node used as a decoder, encoder, or other one-way
 * transformation stage.
 * <p>The handler consumes messages from a receive queue and produces zero or
 * more messages into a send queue for the next stage in the same direction.
 * When both directions need to be coordinated in one type, use
 * {@link ProtoDuplexer} instead.
 * <p><b>Ownership rule:</b> when a handler takes a message from {@code src} and
 * fully consumes it without forwarding the same instance, the handler becomes
 * responsible for any {@link net.hasor.neta.bytebuf.ReferenceHolder}
 * lifecycle it has absorbed. In practice this means transformed or repackaged
 * reference-counted inputs must be released by the consuming handler once
 * ownership has been transferred to replacement output objects.
 * @see ProtoDuplexer
 */
@FunctionalInterface
public interface ProtoHandler<IN, OUT> {
    /**
     * Initializes this protocol node.
     */
    default void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
    }

    /**
     * Called when the channel becomes active.
     */
    default void onActive(ProtoContext context) throws Throwable {
    }

    /** Called on user-defined event. Return {@code true} to propagate, {@code false} to consume. */
    default boolean onUserEvent(ProtoContext context, SoUserEvent event) throws Throwable {
        return true;
    }

    /**
     * Processes one pass through this handler.
     */
    ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<IN> src, ProtoSndQueue<OUT> dst) throws Throwable;

    /**
     * Handles an exception raised by this handler or a downstream handler.
     * <p>On the outbound side, if {@link ProtoContext#sendData(Object)} triggers this callback and
     * the channel remains open, queue-owned outbound messages buffered at the current stage are not
     * discarded by the framework. They remain available for a later send/recovery pass unless the
     * handler consumes them explicitly or the channel close path runs.</p>
     */
    default ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * Releases resources when the channel closes.
     */
    default void onClose(ProtoContext context) {
    }
}