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
import net.hasor.cobble.function.Release;
/**
 * HTTP cookie abstraction defined according to
 * <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a>.
 * <h3>Request Cookies ({@code Cookie} header)</h3>
 * The request-header encoding path uses only {@link #name()} and {@link #value()}.
 * When a request contains multiple cookies, multiple {@code name=value} fragments must be encoded
 * into a single {@code Cookie} header value.
 * <h3>Response Cookies ({@code Set-Cookie} header)</h3>
 * The response-header encoding path decides whether to emit {@link #domain()}, {@link #path()},
 * {@link #maxAge()}, {@link #isSecure()}, {@link #isHttpOnly()}, {@link #sameSite()}, and
 * {@link #expires()} according to whether those attributes have been set.
 * When a response contains multiple cookies, each cookie must be encoded as an independent
 * {@code Set-Cookie} header value.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface Cookie extends Release {
    /**
     * Returns {@code true} when every string stored by this cookie is already in a directly accessible state.
     */
    boolean isResolved();

    /**
     * Ensures that the string values of this cookie are in a directly accessible state and returns {@code this}.
     */
    Cookie resolve();

    /**
     * Returns the current cookie name.
     */
    String name();

    /**
     * Returns the current cookie value.
     */
    String value();

    /**
     * Returns the domain attribute of this cookie, or {@code null} if it is not set.
     */
    String domain();

    /**
     * Returns the path attribute of this cookie, or {@code null} if it is not set.
     */
    String path();

    /**
     * Returns the maximum lifetime stored by this cookie, in seconds.
     * <ul>
     *   <li>{@code Long.MIN_VALUE} means the {@code Max-Age} attribute is not set.</li>
     *   <li>{@code 0} means the cookie should be removed immediately.</li>
     *   <li>Any other value is returned as the number of seconds stored by this object.</li>
     * </ul>
     */
    long maxAge();

    /**
     * Returns the {@code Expires} attribute of this cookie as an RFC 1123 date string
     * (for example {@code "Thu, 01 Jan 1970 00:00:00 GMT"}), or {@code null} if it is not set.
     */
    String expires();

    /**
     * Returns {@code true} if the {@code Secure} attribute is set.
     * Secure cookies are sent only over HTTPS connections.
     */
    boolean isSecure();

    /**
     * Returns {@code true} if the {@code HttpOnly} attribute is set.
     * HttpOnly cookies are not accessible through JavaScript {@code document.cookie}.
     */
    boolean isHttpOnly();

    /**
     * Returns the {@code SameSite} attribute of this cookie, or {@code null} if it is not set.
     * Common values include {@code "Strict"}, {@code "Lax"}, and {@code "None"}.
     */
    String sameSite();
}
