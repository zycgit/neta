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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Build-phase view of {@link ProtoContext}.
 * <p>This interface owns structural registration APIs such as {@code addLast},
 * {@code addFirst}, routing, and partition assembly. It is intended to be used only during
 * initialization through {@link ProtoInitializer} and branch/partition initializers.
 * <p>Runtime handlers should work with {@link ProtoContext} and express protocol evolution through
 * state transitions, route switching, and partition switching rather than ad-hoc pipeline mutation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-29
 */
public interface ProtoBuildContext extends ProtoContext {
    /**
     * using decoder and encoder to combined for duplex.
     * <p>All {@code add*} methods on this interface are structural registration APIs intended for
     * the initialization phase driven by {@link ProtoInitializer#config(ProtoBuildContext)}.
     * Calling them from runtime message or event callbacks is legacy behavior and is discouraged.
     * Runtime protocol evolution should be modeled with state transitions, routing changes, and
     * partition changes instead of ad-hoc pipeline mutation.</p>
     * <ul>
     *  <li>RCV_UP is {@link ByteBuf} or Message</li>
     *  <li>RCV_DOWN is {@link ByteBuf} or Message</li>
     *  <li>SND_UP is {@link ByteBuf} or Message</li>
     *  <li>SND_DOWN is {@link ByteBuf} or Message</li>
     * </ul>
     * @param decoder RCV_UP to RCV_DOWN
     * @param encoder SND_UP to SND_DOWN
     * @throws NullPointerException if the decoder or encoder is {@code null}
     */
    void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    default void addFirst(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirst(name, decoder, encoder);
    }

    void addFirst(ProtoDuplexer<?, ?, ?, ?> duplexer);

    void addFirst(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

    default void addFirst(String name, ProtoConfig protoConf, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirst(name, duplexer);
    }

    void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    default void addLast(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLast(name, decoder, encoder);
    }

    void addLast(ProtoDuplexer<?, ?, ?, ?> duplexer);

    void addLast(String name, ProtoDuplexer<?, ?, ?, ?> duplexer);

    default void addLast(String name, ProtoConfig protoConf, ProtoDuplexer<?, ?, ?, ?> duplexer) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLast(name, duplexer);
    }

    void addFirstEncoder(ProtoHandler<?, ?> encoder);

    void addFirstEncoder(String name, ProtoHandler<?, ?> encoder);

    default void addFirstEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirstEncoder(name, encoder);
    }

    void addLastEncoder(ProtoHandler<?, ?> encoder);

    void addLastEncoder(String name, ProtoHandler<?, ?> encoder);

    default void addLastEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLastEncoder(name, encoder);
    }

    void addFirstDecoder(ProtoHandler<?, ?> decoder);

    void addFirstDecoder(String name, ProtoHandler<?, ?> decoder);

    default void addFirstDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirstDecoder(name, decoder);
    }

    void addLastDecoder(ProtoHandler<?, ?> decoder);

    void addLastDecoder(String name, ProtoHandler<?, ?> decoder);

    default void addLastDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLastDecoder(name, decoder);
    }
}