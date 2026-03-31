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
 * Event-routing selector.
 * <p>When no branch has been selected yet, {@link ProtoRoutingDuplexer} can call this selector to
 * decide which branch the current network event should enter.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
@FunctionalInterface
public interface ProtoRoutingEventSelector {
    /**
     * Compute the branch name that should receive the current network event.
     * @param context pipeline context
     * @param event network event currently being propagated
     * @param isRcv {@code true} when the event propagates in the RCV direction, {@code false} for SND
     * @return registered branch name, or {@code null} when the current event is still insufficient to decide routing
     */
    String route(ProtoContext context, SoEvent event, boolean isRcv);
}