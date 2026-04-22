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
package net.hasor.neta.channel.routing;
import java.util.function.Consumer;
import net.hasor.neta.channel.*;
/**
 * Builder for routing branches.
 * <p>It registers multiple named branches into the same {@link ProtoRoutingDuplex}. Each branch
 * forms an independent sub-pipeline. The branches share the same {@link ProtoContext}, and the
 * routing selector decides at runtime which branch should receive the current data or event.</p>
 * <p>This builder only handles branch definitions. Any further fluent composition on the parent
 * protocol stack is still completed by the outer {@link ProtoBuilder}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRoutingDataSelector
 * @see ProtoRoutingDuplex
 */
public interface ProtoRoutingBuilder<RCV_UP, SND_DOWN> {
    /** Return the control handle owned by the current routing builder. */
    ProtoRoutingControl control();

    /**
     * Register a named branch using a {@link ProtoInitializer}.
     * @param name branch name used to match runtime routing results
     * @param initializer initializer used to build the branch sub-pipeline
     * @return current routing builder
     */
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branchByInitializer(String name, ProtoInitializer initializer);

    /**
     * Register a named branch using a branch-local {@link ProtoBuilder} DSL.
     * @param name branch name used to match runtime routing results
     * @param branchBuilder branch-internal sub-pipeline construction logic
     * @return current routing builder
     */
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branch(String name, Consumer<ProtoBuilder<RCV_UP, SND_DOWN>> branchBuilder);

    /**
     * Build the routing duplexer defined by the current builder.
     * @return {@link ProtoDuplex} ready to be inserted into the protocol stack
     */
    ProtoDuplex<RCV_UP, ?, ?, SND_DOWN> build();
}