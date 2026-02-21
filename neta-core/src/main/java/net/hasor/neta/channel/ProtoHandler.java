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
 * Used to represent a unidirectional data processor, two {@link ProtoHandler}`s in opposite directions can to {@link ProtoDuplexer}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoDuplexer
 */
@FunctionalInterface
public interface ProtoHandler<IN, OUT> {
    /**
     * Initialize the protocol stack.
     */
    default void onInit(ProtoContext context) throws Throwable {
    }

    /**
     * when the Connected.
     * <p>The dst queue allows the handler to produce initial data during activation.
     * For a decoder, dst is the RCV downstream queue; for an encoder, dst is the SND downstream queue.</p>
     * @param context the protocol context
     * @param dst the output queue for producing initial data during activation
     */
    default void onActive(ProtoContext context, ProtoSndQueue<OUT> dst) throws Throwable {
    }

    default boolean onUserEvent(ProtoContext context, SoUserEvent event) throws Throwable {
        return true;
    }

    /**
     * process data the protocol stack.
     */
    ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<IN> src, ProtoSndQueue<OUT> dst) throws Throwable;

    /**
     * Gets called if a Throwable was thrown. If an exception occurs, piple executes in the following way.
     */
    default ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    /**
     * release protocol stack, connection close.
     */
    default void onClose(ProtoContext context) {
    }
}