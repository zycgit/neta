/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRoutingDuplex;
import net.hasor.neta.channel.SoEvent;
/**
 * Event-routing selector.
 * <p>When no branch has been selected yet, {@link ProtoRoutingDuplex} can call this selector to
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
