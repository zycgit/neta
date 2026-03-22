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
 * Routing predicate used by {@link ProtoRoutingDuplexer} to choose a branch from a user event.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
@FunctionalInterface
public interface ProtoRoutingEventSelector {
    /**
     * Evaluate routing condition from a user event and return the selected branch key.
     * @param context the pipeline context
     * @param event the user event currently being propagated
     * @param isRcv {@code true} when the event is moving in RCV direction, {@code false} for SND direction
     * @return the branch key matching a registered branch name, or {@code null} if the event does not determine routing
     */
    String route(ProtoContext context, SoUserEvent event, boolean isRcv);
}