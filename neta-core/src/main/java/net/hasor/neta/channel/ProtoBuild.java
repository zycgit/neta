/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Base entry point for protocol pipeline construction.
 * <p>This interface abstracts the final step of organizing a set of protocol handlers into an
 * executable pipeline.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see ProtoHelper
 */
public interface ProtoBuild {
    /**
     * Build a reusable protocol initializer from the steps declared so far.
     * <p>The result can be used to install a {@link ProtoStackChain} and apply the previously
     * defined duplexers, unidirectional handlers, routing, and partition structures to concrete
     * connections.</p>
     * @return built protocol initializer
     */
    ProtoInitializer build();

    /**
     * Apply the built protocol initializer to the provided build context.
     * <p>This is a convenience entry point for directly materializing the current build result
     * into the target {@link ProtoBuildContext}.</p>
     * @param ctx target build context
     */
    default void config(ProtoBuildContext ctx) {
        this.build().config(ctx);
    }
}
