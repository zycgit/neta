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
import java.util.function.Consumer;

/**
 * Builder for defining routing branches in a protocol pipeline.
 * <p>Use {@link #branchByInitializer(String, ProtoInitializer)} to register named sub-pipelines.</p>
 * <p>This builder is route-definition-focused and does not continue the parent fluent chain.
 * For nested branch-local fluent composition, use {@link #branch(String, Consumer)}.
 * For standalone router creation, start from {@link ProtoHelper#typedRoutingAsStatic(ProtoRoutingDataSelector)} or
 * {@link ProtoHelper#typedRoutingAsRealtime(ProtoRoutingDataSelector)}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRoutingDataSelector
 * @see ProtoRoutingDuplexer
 */
public interface ProtoRoutingBuilder<RCV_UP, SND_DOWN> {
    /** Registers a branch from an existing initializer. */
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branchByInitializer(String name, ProtoInitializer initializer);

    /** Registers a branch using the branch-local {@link ProtoBuilder} DSL. */
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branch(String name, Consumer<ProtoBuilder<RCV_UP, SND_DOWN>> branchBuilder);

    /** Builds the routing duplexer defined by this builder. */
    ProtoDuplexer<RCV_UP, ?, ?, SND_DOWN> build();
}