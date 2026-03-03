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
 * Builder for defining routing branches in a protocol pipeline.
 * <p>Use {@link #branch(String, ProtoInitializer)} to register named sub-pipelines,
 * then call {@link #build()} to produce the composite {@link ProtoDuplexer}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRoutingSelector
 * @see ProtoRoutingDuplexer
 */
public interface ProtoRoutingBuilder<RCV_UP, SND_DOWN> {
    /** Register a named sub-pipeline branch. */
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branch(String name, ProtoInitializer initializer);

    /** Build all registered branches into a single composite {@link ProtoDuplexer}. */
    ProtoDuplexer<RCV_UP, ?, ?, SND_DOWN> build();
}