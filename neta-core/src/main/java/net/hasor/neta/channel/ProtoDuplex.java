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
 * Bidirectional protocol node used inside {@link ProtoStackChain}.
 * <p>A duplexer can observe traffic in both directions. During receive processing
 * ({@code isRcv == true}), it consumes messages from {@code rcvUp} and writes output to
 * {@code rcvDown}. During send processing, it consumes messages from {@code sndUp} and writes
 * output to {@code sndDown}. Adjacent duplexers are connected through these queues to form the
 * complete pipeline.</p>
 * <p>This model allows one node to coordinate both decoding and encoding behavior, maintain shared
 * bidirectional state, and participate in lifecycle, network event, and error callbacks.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoHandler
 * @see ProtoConfig
 */
@FunctionalInterface
public interface ProtoDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    /**
     * Initialize this protocol node.
     */
    default void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
    }

    /**
     * Called when the channel becomes active.
     */
    default void onActive(ProtoContext context) throws Throwable {
    }

    /**
     * Called when a network event arrives. Return {@code true} to continue propagation or
     * {@code false} to consume the event.
     */
    default boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        return true;
    }

    /**
     * Process one receive or send flow.
     */
    ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable;

    /**
     * Handle an exception raised by the current node or a downstream node.
     * <p>On the outbound side, if {@link ProtoContext#sendData(Object)} fails while the channel is
     * still usable, outbound messages currently buffered at this stage and owned by the queue are
     * retained instead of being discarded. They may continue to be processed during later send or
     * recovery flows, and are only reclaimed automatically when the channel closes.</p>
     */
    default ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * Release resources when the channel closes.
     */
    default void onClose(ProtoContext context) {
    }
}
