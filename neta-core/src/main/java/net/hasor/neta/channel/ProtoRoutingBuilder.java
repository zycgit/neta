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
 * Network protocol layer input endpoint data queue
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndQueue
 */
public interface ProtoRoutingBuilder<RCV_UP, SND_DOWN> {
    ProtoRoutingBuilder<RCV_UP, SND_DOWN> branch(String name, ProtoInitializer initializer);

    ProtoDuplexer<RCV_UP, ?, ?, SND_DOWN> build();
}