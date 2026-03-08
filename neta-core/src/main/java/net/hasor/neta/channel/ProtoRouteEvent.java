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
import java.util.Objects;

/**
 * User event fired instead of re-triggering {@link ProtoDuplexer#onActive(ProtoContext)}
 * when a route switches back to a branch that has already been activated before.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public class ProtoRouteEvent {
    private final String fromRoute;
    private final String toRoute;

    public ProtoRouteEvent(String fromRoute, String toRoute) {
        this.fromRoute = fromRoute;
        this.toRoute = Objects.requireNonNull(toRoute, "toRoute is null.");
    }

    public String getFromRoute() {
        return fromRoute;
    }

    public String getToRoute() {
        return toRoute;
    }
}