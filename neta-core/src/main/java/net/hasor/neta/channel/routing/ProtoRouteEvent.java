/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import java.util.Objects;

import net.hasor.neta.channel.ProtoRoutingDuplex;

/**
 * Route-switch event fired when {@link ProtoRoutingDuplex} changes the active route.
 * <p>This event only describes the route-switch result. It does not carry branch activation
 * lifecycle responsibilities.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public class ProtoRouteEvent {
    private final String fromRoute;
    private final String toRoute;

    /**
     * Create a route-switch event.
     * @param fromRoute branch name before the switch; may be {@code null} when a branch is selected for the first time
     * @param toRoute branch name after the switch
     */
    public ProtoRouteEvent(String fromRoute, String toRoute) {
        this.fromRoute = fromRoute;
        this.toRoute = Objects.requireNonNull(toRoute, "toRoute is null.");
    }

    /** Return the branch name before the switch. */
    public String getFromRoute() {
        return fromRoute;
    }

    /** Return the branch name after the switch. */
    public String getToRoute() {
        return toRoute;
    }
}
