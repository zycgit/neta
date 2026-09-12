/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Protocol-composition API exposed by {@link ProtoContext} during the build phase.
 * <p>This interface is used to keep inserting duplexers or one-way handlers at the head or tail
 * of an already created build context.</p>
 * <p>Common usage patterns include:</p>
 * <ul>
 *   <li>Using {@code addFirst(...)} to add preprocessing handlers in front of the existing protocol stack.</li>
 *   <li>Using {@code addLast(...)} to add postprocessing handlers behind the existing protocol stack.</li>
 *   <li>Giving handlers stable names so they can be located by name later.</li>
 *   <li>Declaring the {@link ProtoConfig} used at the insertion point together with the handler.</li>
 * </ul>
 * <p>If you want to declare an entire new pipeline continuously from the perspective of type flow,
 * {@link ProtoBuilder} is more suitable. If you already have a build context and only want to add
 * handlers before or after the current position, this interface is more direct.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-29
 */
public interface ProtoBuildContext extends ProtoContext {
    /**
     * Add a duplex pair to the pipeline head in encoder/decoder form.
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    void addFirst(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * Add a duplex pair to the pipeline head in encoder/decoder form with a name.
     * @param name duplexer name
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    void addFirst(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * Add a duplex pair to the pipeline head in encoder/decoder form with a name and config.
     * @param name duplexer name
     * @param protoConf protocol configuration used at this duplexer position
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    default void addFirst(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirst(name, decoder, encoder);
    }

    /**
     * Add a duplexer to the pipeline head.
     * @param duplexer duplexer that should take effect first
     */
    void addFirst(ProtoDuplex<?, ?, ?, ?> duplexer);

    /**
     * Add a duplexer to the pipeline head with a name.
     * @param name duplexer name
     * @param duplexer duplexer that should take effect first
     */
    void addFirst(String name, ProtoDuplex<?, ?, ?, ?> duplexer);

    /**
     * Add a duplexer to the pipeline head with a name and config.
     * @param name duplexer name
     * @param protoConf protocol configuration used at this duplexer position
     * @param duplexer duplexer that should take effect first
     */
    default void addFirst(String name, ProtoConfig protoConf, ProtoDuplex<?, ?, ?, ?> duplexer) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirst(name, duplexer);
    }

    /**
     * Add a duplex pair to the pipeline tail in encoder/decoder form.
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    void addLast(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * Add a duplex pair to the pipeline tail in encoder/decoder form with a name.
     * @param name duplexer name
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    void addLast(String name, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder);

    /**
     * Add a duplex pair to the pipeline tail in encoder/decoder form with a name and config.
     * @param name duplexer name
     * @param protoConf protocol configuration used at this duplexer position
     * @param decoder one-way decoder for inbound data
     * @param encoder one-way encoder for outbound data
     */
    default void addLast(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLast(name, decoder, encoder);
    }

    /**
     * Add a duplexer to the pipeline tail.
     * @param duplexer duplexer to be used as postprocessing
     */
    void addLast(ProtoDuplex<?, ?, ?, ?> duplexer);

    /**
     * Add a duplexer to the pipeline tail with a name.
     * @param name duplexer name
     * @param duplexer duplexer to be used as postprocessing
     */
    void addLast(String name, ProtoDuplex<?, ?, ?, ?> duplexer);

    /**
     * Add a duplexer to the pipeline tail with a name and config.
     * @param name duplexer name
     * @param protoConf protocol configuration used at this duplexer position
     * @param duplexer duplexer to be used as postprocessing
     */
    default void addLast(String name, ProtoConfig protoConf, ProtoDuplex<?, ?, ?, ?> duplexer) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLast(name, duplexer);
    }

    /**
     * Add an encoder to the pipeline head.
     * @param encoder one-way encoder for outbound data
     */
    void addFirstEncoder(ProtoHandler<?, ?> encoder);

    /**
     * Add an encoder to the pipeline head with a name.
     * @param name handler name
     * @param encoder one-way encoder for outbound data
     */
    void addFirstEncoder(String name, ProtoHandler<?, ?> encoder);

    /**
     * Add an encoder to the pipeline head with a name and config.
     * @param name handler name
     * @param protoConf protocol configuration used at this handler position
     * @param encoder one-way encoder for outbound data
     */
    default void addFirstEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirstEncoder(name, encoder);
    }

    /**
     * Add an encoder to the pipeline tail.
     * @param encoder one-way encoder for outbound data
     */
    void addLastEncoder(ProtoHandler<?, ?> encoder);

    /**
     * Add an encoder to the pipeline tail with a name.
     * @param name handler name
     * @param encoder one-way encoder for outbound data
     */
    void addLastEncoder(String name, ProtoHandler<?, ?> encoder);

    /**
     * Add an encoder to the pipeline tail with a name and config.
     * @param name handler name
     * @param protoConf protocol configuration used at this handler position
     * @param encoder one-way encoder for outbound data
     */
    default void addLastEncoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> encoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLastEncoder(name, encoder);
    }

    /**
     * Add a decoder to the pipeline head.
     * @param decoder one-way decoder for inbound data
     */
    void addFirstDecoder(ProtoHandler<?, ?> decoder);

    /**
     * Add a decoder to the pipeline head with a name.
     * @param name handler name
     * @param decoder one-way decoder for inbound data
     */
    void addFirstDecoder(String name, ProtoHandler<?, ?> decoder);

    /**
     * Add a decoder to the pipeline head with a name and config.
     * @param name handler name
     * @param protoConf protocol configuration used at this handler position
     * @param decoder one-way decoder for inbound data
     */
    default void addFirstDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addFirstDecoder(name, decoder);
    }

    /**
     * Add a decoder to the pipeline tail.
     * @param decoder one-way decoder for inbound data
     */
    void addLastDecoder(ProtoHandler<?, ?> decoder);

    /**
     * Add a decoder to the pipeline tail with a name.
     * @param name handler name
     * @param decoder one-way decoder for inbound data
     */
    void addLastDecoder(String name, ProtoHandler<?, ?> decoder);

    /**
     * Add a decoder to the pipeline tail with a name and config.
     * @param name handler name
     * @param protoConf protocol configuration used at this handler position
     * @param decoder one-way decoder for inbound data
     */
    default void addLastDecoder(String name, ProtoConfig protoConf, ProtoHandler<?, ?> decoder) {
        if (protoConf == null) {
            throw new NullPointerException("protoConf is null.");
        }
        this.addLastDecoder(name, decoder);
    }
}
