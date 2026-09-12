/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

/**
 * Describes how a request is being dispatched to the servlet.
 * @author 赵永春 (zyc@hasor.net)
 */
public enum DispatcherType {
    /** A normal (non-dispatched) request. */
    NORMAL,
    /** A request dispatched via {@link AsyncContext#dispatch(String)}. */
    ASYNC,
    /** A request dispatched via a forward mechanism. */
    FORWARD,
    /** A request dispatched via an include mechanism. */
    INCLUDE
}
