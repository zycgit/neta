/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Represents the scheme component of an HTTP URI, as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-2.7">RFC 7230, Section 2.7</a>.
 * Also covers WebSocket schemes that reuse HTTP-style URI authority semantics.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class HttpScheme {
    /** Scheme used for non-secure HTTP connections. The default port is 80. */
    public static final HttpScheme HTTP  = new HttpScheme(80, "http");
    /** Scheme used for secure HTTP connections. The default port is 443. */
    public static final HttpScheme HTTPS = new HttpScheme(443, "https");
    /** Scheme used for non-secure WebSocket connections. The default port is 80. */
    public static final HttpScheme WS    = new HttpScheme(80, "ws");
    /** Scheme used for secure WebSocket connections. The default port is 443. */
    public static final HttpScheme WSS   = new HttpScheme(443, "wss");

    private final int    port;
    private final String name;

    private HttpScheme(int port, String name) {
        this.port = port;
        this.name = name;
    }

    /**
     * Returns the scheme name, such as "http", "https", "ws", or "wss".
     * @return scheme name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the default port for this scheme, such as 80 for HTTP or 443 for HTTPS.
     * @return default port
     */
    public int port() {
        return port;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return port * 31 + name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpScheme)) {
            return false;
        }
        HttpScheme that = (HttpScheme) o;
        return port == that.port && name.equals(that.name);
    }
}
