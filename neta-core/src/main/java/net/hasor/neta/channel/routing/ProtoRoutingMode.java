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

import net.hasor.neta.channel.ProtoRoutingDuplexer;

/**
 * Routing mode of {@link ProtoRoutingDuplexer}.
 * <p>It decides whether a route selection is reused after the first match or recalculated for each
 * inbound message.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public enum ProtoRoutingMode {
    /** Cache the first successfully selected branch and keep reusing it until explicitly switched through {@link ProtoRoutingControl#switchRoute(String)}. */
    STATIC,
    /** Re-run the data selector whenever a new inbound message arrives. */
    REALTIME
}