/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Callback used to populate handlers into a {@link ProtoBuildContext}.
 * <p>Whenever the framework needs to build a protocol chain, it calls
 * {@link #config(ProtoBuildContext)}. That includes not only the root pipeline for a newly created
 * channel, but also branch pipelines and partition pipelines. In other words, this callback
 * configures a context, not just a single top-level connect or accept event.</p>
 * <p>Implementations typically add decoders, encoders, and application handlers to the
 * {@link ProtoBuildContext} pipeline:</p>
 * <pre>
 * manager.bind(address, ctx -&gt; {               // &lt;-- ProtoInitializer
 *     ctx.addLastDecoder("frame",  new LineBasedFrameHandler(4096, false));
 *     ctx.addLastDecoder("string", new StringDecoder(StandardCharsets.UTF_8));
 *     ctx.addLast("app",   new MyAppHandler());
 * }, SoConfig.TCP());
 * </pre>
 * <p><b>Design principle:</b> this callback is the boundary where pipeline structure is declared.
 * Handlers, routing, and partitions should all be defined here so the connection structure is fixed
 * before data processing begins. Runtime logic may switch state, route, or partition, but should
 * not depend on arbitrary structural mutation.</p>
 * <p>The callback executes synchronously on the pipeline-construction thread, so it should remain
 * deterministic and non-blocking.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoBuildContext
 * @see ProtoHelper
 */
@FunctionalInterface
public interface ProtoInitializer {
    /** Called once for each new channel or branch context to define the pipeline structure on {@code ctx}. */
    void config(ProtoBuildContext ctx);
}
