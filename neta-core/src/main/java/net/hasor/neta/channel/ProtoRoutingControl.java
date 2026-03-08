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
 * Imperative control handle exposed by {@link ProtoRoutingDuplexer} to its branch handlers.
 * <p>
 * Obtain it from {@link ProtoContext#context(Class)} inside a branch pipeline:
 * </p>
 * <pre>
 * ProtoRoutingControl routing = context.context(ProtoRoutingControl.class);
 * routing.switchRoute("websocket");
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public interface ProtoRoutingControl {
    /** Returns the router mode. */
    ProtoRoutingMode getMode();

    /** Returns the currently selected route, or {@code null} if routing is not yet decided. */
    String currentRoute();

    /** Schedule a route switch to the given branch. */
    void switchRoute(String newBranchName);
}