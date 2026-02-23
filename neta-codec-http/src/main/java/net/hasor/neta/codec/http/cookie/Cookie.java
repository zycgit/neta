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
package net.hasor.neta.codec.http.cookie;
/**
 * An HTTP cookie, as defined by
 * <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a>.
 * <h3>Request cookies (Cookie header)</h3>
 * Only {@link #name()} and {@link #value()} are transmitted.
 * <h3>Response cookies (Set-Cookie header)</h3>
 * All attributes ({@link #domain()}, {@link #path()}, {@link #maxAge()},
 * {@link #isSecure()}, {@link #isHttpOnly()}, {@link #sameSite()},
 * {@link #expires()}) take effect.
 */
public interface Cookie {

    /**
     * Returns the name of this cookie.
     */
    String name();

    /**
     * Returns the value of this cookie.
     */
    String value();

    /**
     * Returns the domain of this cookie, or {@code null} if not set.
     */
    String domain();

    /**
     * Returns the path of this cookie, or {@code null} if not set.
     */
    String path();

    /**
     * Returns the maximum age of this cookie in seconds.
     * <ul>
     *   <li>{@code Long.MIN_VALUE} means the {@code Max-Age} attribute is not set.</li>
     *   <li>{@code 0} means the cookie should be deleted immediately.</li>
     *   <li>A positive value means the cookie expires after that many seconds.</li>
     * </ul>
     */
    long maxAge();

    /**
     * Returns the {@code Expires} attribute of this cookie as a date string
     * in RFC 1123 format (e.g. {@code "Thu, 01 Jan 1970 00:00:00 GMT"}),
     * or {@code null} if not set.
     */
    String expires();

    /**
     * Returns {@code true} if the {@code Secure} attribute is set.
     * A secure cookie is only sent over HTTPS connections.
     */
    boolean isSecure();

    /**
     * Returns {@code true} if the {@code HttpOnly} attribute is set.
     * An HttpOnly cookie is inaccessible to JavaScript via {@code document.cookie}.
     */
    boolean isHttpOnly();

    /**
     * Returns the {@code SameSite} attribute of this cookie, or {@code null} if not set.
     * Common values: {@code "Strict"}, {@code "Lax"}, {@code "None"}.
     */
    String sameSite();
}
