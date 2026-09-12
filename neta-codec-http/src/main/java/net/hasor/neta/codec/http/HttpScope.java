/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Describes whether the current HTTP semantic flow is connection-scoped or stream-scoped.
 * <p>
 * HTTP/1.x is connection-scoped. HTTP/2 and HTTP/3 are stream-scoped by default.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-08
 */
public enum HttpScope {
    CONNECTION,
    STREAM
}
