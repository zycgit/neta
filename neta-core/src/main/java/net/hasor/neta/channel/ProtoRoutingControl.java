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
 * Control handle exposed by the current routing duplexer to branch handlers.
 * <p>When a handler runs inside a routing branch, it can obtain this object through the current
 * {@link ProtoContext} using {@link ProtoContext#context(Class)}:</p>
 * <pre>
 * ProtoRoutingControl routing = context.context(ProtoRoutingControl.class);
 * routing.switchRoute("websocket");
 * </pre>
 * <p>The object is inserted into context storage by the corresponding
 * {@link ProtoRoutingDuplexer} when it creates the branch context. Handlers in the current branch
 * and any nested child branches can read it.</p>
 * <p>Calling {@link #switchRoute(String)} only affects the current routing duplexer instance on the
 * current connection. The new route takes effect after the current processing round finishes and
 * pending output and recovery state have been cleared.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public interface ProtoRoutingControl {
    /** Return the working mode of the current routing duplexer. */
    ProtoRoutingMode getMode();

    /** Return the currently selected branch name, or {@code null} if routing has not been decided yet. */
    String current();

    /**
     * Request a switch to the specified branch.
     * <p>The request is recorded as a pending switch and applied by the routing duplexer after the
     * current processing round completes.</p>
     * @param target target branch name
     */
    void switchRoute(String target);
}