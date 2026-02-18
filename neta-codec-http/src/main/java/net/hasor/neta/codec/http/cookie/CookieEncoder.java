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
import java.util.Arrays;
import java.util.Collection;

/**
 * Encodes one or more {@link Cookie} objects into the value of an HTTP <b>request</b>
 * {@code Cookie} header.
 * <p>Request cookies contain only {@code name=value} pairs separated by {@code "; "},
 * per <a href="https://tools.ietf.org/html/rfc6265#section-4.2">RFC 6265 §4.2</a>:
 * <pre>
 *   Cookie: name1=value1; name2=value2; name3=value3
 * </pre>
 * <h3>Usage</h3>
 * <pre>
 *   Cookie c1 = new DefaultCookie("session", "abc");
 *   Cookie c2 = new DefaultCookie("lang", "en");
 *   String headerValue = CookieEncoder.encode(c1, c2);
 *   // "session=abc; lang=en"
 * </pre>
 */
public final class CookieEncoder {

    private CookieEncoder() {
    }

    /**
     * Encodes one or more cookies into a single {@code Cookie} header value.
     * @param cookies the cookies to encode; must not be {@code null} or empty
     * @return the encoded header value, e.g. {@code "session=abc; lang=en"}
     * @throws IllegalArgumentException if {@code cookies} is null or empty
     */
    public static String encode(Cookie... cookies) {
        if (cookies == null || cookies.length == 0) {
            throw new IllegalArgumentException("at least one cookie is required");
        }
        return encode(Arrays.asList(cookies));
    }

    /**
     * Encodes a collection of cookies into a single {@code Cookie} header value.
     * @param cookies the cookies to encode; must not be {@code null} or empty
     * @return the encoded header value
     * @throws IllegalArgumentException if {@code cookies} is null or empty
     */
    public static String encode(Collection<? extends Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalArgumentException("at least one cookie is required");
        }
        StringBuilder sb = new StringBuilder();
        for (Cookie cookie : cookies) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(cookie.name()).append('=').append(cookie.value());
        }
        return sb.toString();
    }
}
