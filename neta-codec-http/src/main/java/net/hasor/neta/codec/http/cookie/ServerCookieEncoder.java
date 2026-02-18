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
 * Encodes a {@link Cookie} into the value of an HTTP <b>response</b>
 * {@code Set-Cookie} header.
 * <p>The generated header value includes the cookie's {@code name=value} pair
 * followed by any configured attributes, per
 * <a href="https://tools.ietf.org/html/rfc6265#section-4.1">RFC 6265 §4.1</a>:
 * <pre>
 *   Set-Cookie: name=value; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly; SameSite=Lax
 * </pre>
 * <h3>Usage</h3>
 * <pre>
 *   DefaultCookie c = new DefaultCookie("session", "abc123")
 *       .setPath("/")
 *       .setHttpOnly(true)
 *       .setMaxAge(3600);
 *   String headerValue = ServerCookieEncoder.encode(c);
 *   // "session=abc123; Path=/; Max-Age=3600; HttpOnly"
 * </pre>
 */
public final class ServerCookieEncoder {

    private ServerCookieEncoder() {
    }

    /**
     * Encodes a single cookie into a {@code Set-Cookie} header value.
     * @param cookie the cookie to encode; must not be {@code null}
     * @return the complete {@code Set-Cookie} header value
     * @throws IllegalArgumentException if {@code cookie} is null
     */
    public static String encode(Cookie cookie) {
        if (cookie == null) {
            throw new IllegalArgumentException("cookie must not be null");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(cookie.name()).append('=').append(cookie.value());

        if (cookie.domain() != null && !cookie.domain().isEmpty()) {
            sb.append("; Domain=").append(cookie.domain());
        }
        if (cookie.path() != null && !cookie.path().isEmpty()) {
            sb.append("; Path=").append(cookie.path());
        }
        if (cookie.maxAge() != DefaultCookie.UNDEFINED_MAX_AGE) {
            sb.append("; Max-Age=").append(cookie.maxAge());
        }
        if (cookie.expires() != null && !cookie.expires().isEmpty()) {
            sb.append("; Expires=").append(cookie.expires());
        }
        if (cookie.isSecure()) {
            sb.append("; Secure");
        }
        if (cookie.isHttpOnly()) {
            sb.append("; HttpOnly");
        }
        if (cookie.sameSite() != null && !cookie.sameSite().isEmpty()) {
            sb.append("; SameSite=").append(cookie.sameSite());
        }

        return sb.toString();
    }
}
