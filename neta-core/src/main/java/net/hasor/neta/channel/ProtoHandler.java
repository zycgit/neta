/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Unidirectional handler interface, typically used as a decoder or encoder.
 * <p>A unidirectional handler consumes messages from the receive queue and emits zero or more
 * messages to the next-stage send queue in the same direction. Use {@link ProtoDuplex} instead
 * when one type needs to coordinate both directions.</p>
 * <p><b>Ownership rule:</b> when a handler removes a message from {@code src} and does not hand it
 * off to {@code dst} or another queue, the handler becomes responsible for managing and releasing
 * that object. In practice, once data leaves both {@code src} and {@code dst}, its references and
 * lifetime are the handler's responsibility.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-21
 * @see ProtoDuplex
 */
@FunctionalInterface
public interface ProtoHandler<IN, OUT> {
    /**
     * Initialize the handler when it is installed into the protocol pipeline.
     * <p>This callback is typically used to read configuration, create internal state, or prepare
     * resources needed by later processing. {@code poolSize} represents the queue capacity
     * configured for the current direction.</p>
     * @param name handler name within the protocol stack
     * @param poolSize queue capacity of the current direction
     * @param context current protocol context
     * @throws Throwable any exception thrown during initialization
     */
    default void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
    }

    /**
     * Called after the underlying channel becomes active.
     * <p>This callback runs before the protocol stack starts processing data. It is suitable for
     * firing handshake pre-events, initializing connection-level state, or warming up handler
     * resources based on {@link ProtoContext}.</p>
     * @param context current protocol context
     * @throws Throwable any exception thrown during activation
     */
    default void onActive(ProtoContext context) throws Throwable {
    }

    /**
     * Called when a network event propagates to the current handler.
     * <p>Return {@code true} to continue propagating the event to the next stage, or {@code false}
     * to consume it at the current handler so downstream handlers will not receive it.</p>
     * @param context current protocol context
     * @param event currently received network event
     * @return whether the event should continue propagating
     * @throws Throwable any exception thrown while handling the event
     */
    default boolean onEvent(ProtoContext context, SoEvent event) throws Throwable {
        return true;
    }

    /**
     * Process one batch of messages flowing through the current handler.
     * <p>The implementation reads consumable input messages from {@code src} and writes processed
     * results into {@code dst}. It may emit zero, one, or many messages. The return value tells the
     * framework whether processing should continue.</p>
     * <p>If a message is removed from {@code src} and not handed to {@code dst} or any other queue,
     * the current handler becomes responsible for the lifetime of that message.</p>
     * @param context current protocol context
     * @param src input queue of the current direction
     * @param dst output queue of the current direction
     * @return progress status after the current processing round
     * @throws Throwable any exception thrown while processing messages
     */
    ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<IN> src, ProtoSndQueue<OUT> dst) throws Throwable;

    /**
     * Handle an exception raised by the current handler or a downstream handler.
     * <p>On the outbound side, if {@link ProtoContext#sendData(Object)} triggers this callback while
     * the channel is still open, outbound messages buffered at the current stage and owned by the
     * queue are not discarded by the framework. They remain available for later send or recovery
     * flows unless the handler explicitly consumes them or the channel close path begins.</p>
     * <p>Implementations can observe or adjust exception handling state through
     * {@link ProtoExceptionHolder}. The return value decides whether the current flow continues after
     * exception handling.</p>
     * @param context current protocol context
     * @param e current exception
     * @param eh exception-state controller
     * @return progress status after exception handling
     * @throws Throwable any exception thrown again while handling the error
     */
    default ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * Clean up when the channel closes and the current handler is about to leave the protocol stack.
     * <p>This is the right place to release external resources, clear internal caches, or end any
     * state tied to the current connection.</p>
     * @param context current protocol context
     */
    default void onClose(ProtoContext context) {
    }
}
