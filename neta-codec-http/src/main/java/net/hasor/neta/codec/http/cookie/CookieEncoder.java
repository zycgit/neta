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
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import net.hasor.neta.bytebuf.ByteBuf;

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
    private static final byte[] SEPARATOR = { ';', ' ' };

    /**
     * Encodes one or more cookies directly into a {@link ByteBuf}.
     * @param dst the destination buffer to write to; must not be {@code null}
     * @param cookies the cookies to encode; must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code cookies} is null or empty
     */
    public static void encode(ByteBuf dst, Cookie... cookies) {
        if (cookies == null || cookies.length == 0) {
            throw new IllegalArgumentException("at least one cookie is required");
        }
        for (int i = 0; i < cookies.length; i++) {
            if (i > 0) {
                dst.writeBytes(SEPARATOR);
            }
            dst.writeString(cookies[i].name(), StandardCharsets.US_ASCII);
            dst.writeByte((byte) '=');
            dst.writeString(cookies[i].value(), StandardCharsets.US_ASCII);
        }
    }

    /**
     * Encodes a collection of cookies directly into a {@link ByteBuf}.
     * @param dst the destination buffer to write to; must not be {@code null}
     * @param cookies the cookies to encode; must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code cookies} is null or empty
     */
    public static void encode(ByteBuf dst, Collection<? extends Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalArgumentException("at least one cookie is required");
        }
        boolean first = true;
        for (Cookie cookie : cookies) {
            if (first) {
                first = false;
            } else {
                dst.writeBytes(SEPARATOR);
            }
            dst.writeString(cookie.name(), StandardCharsets.US_ASCII);
            dst.writeByte((byte) '=');
            dst.writeString(cookie.value(), StandardCharsets.US_ASCII);
        }
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

        // Calculate exact length
        int totalLen = 0;
        for (int i = 0; i < cookies.length; i++) {
            Cookie c = cookies[i];
            totalLen += c.name().length() + 1 + c.value().length();
            if (i > 0)
                totalLen += 2; // "; "
        }

        char[] buf = new char[totalLen];
        int pos = 0;
        for (int i = 0; i < cookies.length; i++) {
            if (i > 0) {
                buf[pos++] = ';';
                buf[pos++] = ' ';
            }
            String name = cookies[i].name();
            name.getChars(0, name.length(), buf, pos);
            pos += name.length();
            buf[pos++] = '=';
            String value = cookies[i].value();
            value.getChars(0, value.length(), buf, pos);
            pos += value.length();
        }
        return new String(buf, 0, pos);
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
        StringBuilder sb = new StringBuilder(cookies.size() * 24);
        boolean first = true;
        for (Cookie cookie : cookies) {
            if (first) {
                first = false;
            } else {
                sb.append("; ");
            }
            sb.append(cookie.name()).append('=').append(cookie.value());
        }
        return sb.toString();
    }
}
