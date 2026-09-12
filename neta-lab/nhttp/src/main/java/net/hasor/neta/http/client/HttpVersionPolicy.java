/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http.client;

/**
 * HTTP version policy used by the client transport.
 * <p>
 * For cleartext endpoints, {@link #AUTO} falls back to HTTP/1.1.
 * For TLS endpoints, {@link #AUTO} negotiates HTTP/2 or HTTP/1.1 by ALPN.
 * @author 赵永春 (zyc@hasor.net)
 */
public enum HttpVersionPolicy {
    AUTO,
    HTTP_1_1,
    HTTP_2
}
