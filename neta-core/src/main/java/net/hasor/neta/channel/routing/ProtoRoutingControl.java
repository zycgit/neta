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

import net.hasor.neta.channel.ProtoStatus;

/**
 * Control handle owned by the current routing duplexer.
 * <p>Unlike general-purpose context objects, this control is expected to be obtained from
 * {@link ProtoRoutingBuilder#control()} during branch assembly and then explicitly passed into the
 * handlers or duplexers that are allowed to trigger route changes.</p>
 * <p>The same control instance is shared by all branches of the same routing owner on the current
 * connection, but route-seed visibility is still scoped to the currently selected branch after the
 * switch is applied.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public interface ProtoRoutingControl {
    /** Return the working mode of the current routing duplexer. */
    ProtoRoutingMode mode();

    /** Return the currently selected branch name, or {@code null} if routing has not been decided yet. */
    String current();

    /**
     * Request a switch to the specified branch.
     * <p>The request is recorded as a pending switch. The routing duplexer decides when the switch
     * becomes visible according to the current branch return status and the old branch flush/recovery state.</p>
     * <p>Calling this method only affects the current routing duplexer instance on the current
     * connection. The actual cut-over is decided by the routing owner when the current branch round
     * returns:</p>
     * <ul>
     *   <li>returning {@link ProtoStatus#Next} keeps running the current branch until the round ends, then applies the pending switch;</li>
     *   <li>returning {@link ProtoStatus#Stop} ends the current branch immediately and allows the routing owner to switch the selected route in the same outer round; later router entries then run on the new branch;</li>
     *   <li>returning {@link ProtoStatus#Abort} suppresses route switching for the current round; the pending switch is retried on the next routing entry.</li>
     * </ul>
     * <p>The actual cut-over still waits for pending output and recovery state of the old branch to clear.</p>
     * @param target target branch name
     */
    void switchRoute(String target);

    /**
     * Request a switch to the specified branch and deliver a one-shot seed object to the target branch.
     * <p>The seed becomes visible only after the routing owner actually applies the pending switch.
     * It is then exposed through {@link #hasSeed()}, {@link #peekSeed()}, and {@link #takeSeed()} on
     * the same control handle while the target branch is current.</p>
     * <p>At most one route seed may be pending or visible at a time for the current routing owner.</p>
     * @param target target branch name
     * @param seed one-shot handoff object for the target branch
     */
    void switchRoute(String target, Object seed);

    /** Return whether the current selected branch has a pending one-shot seed object to consume. */
    boolean hasSeed();

    /** Peek the current selected branch seed without consuming it. */
    Object peekSeed();

    /** Take and clear the current selected branch seed. */
    Object takeSeed();

    /** Clear any current or pending route seed owned by the routing duplexer. */
    void removeSeed();
}