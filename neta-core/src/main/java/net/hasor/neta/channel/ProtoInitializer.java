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
 * Callback used to populate a {@link ProtoContext} with handlers.
 * <p>The framework invokes {@link #config(ProtoContext)} whenever it needs to build a protocol chain:
 * for a freshly created channel's root pipeline, and also for branch pipelines owned by
 * {@link ProtoRoutingDuplexer}. The callback therefore configures a context; it is not limited to
 * a single top-level connect or accept event.
 * <p><b>Design principle:</b> this callback is the structural boundary of a pipeline. Handlers,
 * routes, and partitions should be declared here so the connection structure is fixed before data
 * processing starts. Runtime processing may switch state, route, or partition, but should not rely
 * on arbitrary structural mutation.
 * <p>Implementations add decoders, encoders, and business-logic handlers to the
 * {@link ProtoContext} pipeline:
 * <pre>
 * manager.bind(address, ctx -&gt; {               // &lt;-- ProtoInitializer
 *     ctx.addLastDecoder("frame",  new LineBasedFrameHandler(4096, false));
 *     ctx.addLastDecoder("string", new StringDecoder(StandardCharsets.UTF_8));
 *     ctx.addLast("app",   new MyAppHandler());
 * }, SoConfig.TCP());
 * </pre>
 * <p>The callback runs synchronously on whichever thread is constructing the pipeline, so it should
 * stay deterministic and non-blocking.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoContext
 * @see ProtoHelper
 */
@FunctionalInterface
public interface ProtoInitializer {
    /** Called once per new channel or branch context; determine pipeline structure on {@code ctx}. */
    void config(ProtoContext ctx);
}