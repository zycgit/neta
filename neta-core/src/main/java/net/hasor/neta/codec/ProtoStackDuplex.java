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
package net.hasor.neta.codec;
import net.hasor.neta.channel.ProtoDuplexer;
import net.hasor.neta.channel.ProtoHandler;

/**
 * Minimal abstract marker for duplex codec stages whose outbound type stays unchanged.
 * <p>
 * The generic signature fixes {@code SND_UP} and {@code SND_DOWN} to the same
 * type, which makes the class suitable for handlers that only need to transform
 * the receive path. Unlike the internal adapter wrappers in the channel package,
 * this type does not provide pass-through logic by itself; subclasses still need
 * to implement the {@link ProtoDuplexer} contract explicitly.
 * @param <RCV_UP> inbound message type received from the lower layer
 * @param <RCV_DOWN> inbound message type emitted to the upper layer
 * @param <SND> outbound message type used on both sides of the duplexer
 * @author 赵永春 (zyc@hasor.net)
 * @see ProtoDuplexer
 * @see ProtoHandler
 */
public abstract class ProtoStackDuplex<RCV_UP, RCV_DOWN, SND> implements ProtoDuplexer<RCV_UP, RCV_DOWN, SND, SND> {
    private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;

    ProtoStackDuplex(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        this.decoder = decoder;
    }

}