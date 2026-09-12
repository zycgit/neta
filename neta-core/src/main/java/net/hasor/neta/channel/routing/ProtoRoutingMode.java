/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import net.hasor.neta.channel.ProtoRoutingDuplex;

/**
 * Routing mode of {@link ProtoRoutingDuplex}.
 * <p>It decides whether a route selection is reused after the first match or recalculated for each
 * inbound message.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public enum ProtoRoutingMode {
    /** Cache the first successfully selected branch and keep reusing it until explicitly switched through {@link ProtoRoutingControl#switchRoute(String)} or {@link ProtoRoutingControl#switchRouteNextTick(String)}. */
    STATIC,
    /** Re-run the data selector whenever a new inbound message arrives. */
    REALTIME
}
