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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;

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
    // pre-computed attribute prefix byte arrays
    private static final byte[] PFX_DOMAIN   = "; Domain=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_PATH     = "; Path=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_MAXAGE   = "; Max-Age=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_EXPIRES  = "; Expires=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_SECURE   = "; Secure".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_HTTPONLY = "; HttpOnly".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_SAMESITE = "; SameSite=".getBytes(StandardCharsets.US_ASCII);

    /**
     * Encodes a single cookie directly into a {@link ByteBuf} as a {@code Set-Cookie} header value.
     * @param dst the destination buffer to write to; must not be {@code null}
     * @param cookie the cookie to encode; must not be {@code null}
     * @throws IllegalArgumentException if {@code cookie} is null
     */
    public static void encode(ByteBuf dst, Cookie cookie) {
        if (cookie == null) {
            throw new IllegalArgumentException("cookie must not be null");
        }

        dst.writeString(cookie.name(), StandardCharsets.US_ASCII);
        dst.writeByte((byte) '=');
        dst.writeString(cookie.value(), StandardCharsets.US_ASCII);

        String domain = cookie.domain();
        if (StringUtils.isNotBlank(domain)) {
            dst.writeBytes(PFX_DOMAIN);
            dst.writeString(domain, StandardCharsets.US_ASCII);
        }

        String path = cookie.path();
        if (StringUtils.isNotBlank(path)) {
            dst.writeBytes(PFX_PATH);
            dst.writeString(path, StandardCharsets.US_ASCII);
        }

        long maxAge = cookie.maxAge();
        if (maxAge != DefaultCookie.UNDEFINED_MAX_AGE) {
            dst.writeBytes(PFX_MAXAGE);
            CookieUtils.writeLong(dst, maxAge);
        }

        String expires = cookie.expires();
        if (StringUtils.isNotBlank(expires)) {
            dst.writeBytes(PFX_EXPIRES);
            dst.writeString(expires, StandardCharsets.US_ASCII);
        }

        if (cookie.isSecure()) {
            dst.writeBytes(PFX_SECURE);
        }

        if (cookie.isHttpOnly()) {
            dst.writeBytes(PFX_HTTPONLY);
        }

        String sameSite = cookie.sameSite();
        if (StringUtils.isNotBlank(sameSite)) {
            dst.writeBytes(PFX_SAMESITE);
            dst.writeString(sameSite, StandardCharsets.US_ASCII);
        }
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

        // Pre-calculate exact buffer size to avoid any reallocation
        String name = cookie.name();
        String value = cookie.value();
        String domain = cookie.domain();
        String path = cookie.path();
        long maxAge = cookie.maxAge();
        String expires = cookie.expires();
        boolean secure = cookie.isSecure();
        boolean httpOnly = cookie.isHttpOnly();
        String sameSite = cookie.sameSite();

        // Pre-allocated attribute prefix strings (constant folded by JIT)
        final String PFX_DOMAIN = "; Domain=";
        final String PFX_PATH = "; Path=";
        final String PFX_MAXAGE = "; Max-Age=";
        final String PFX_EXPIRES = "; Expires=";
        final String PFX_SECURE = "; Secure";
        final String PFX_HTTPONLY = "; HttpOnly";
        final String PFX_SAMESITE = "; SameSite=";

        int len = name.length() + 1 + value.length();
        boolean hasDomain = StringUtils.isNotBlank(domain);
        boolean hasPath = StringUtils.isNotBlank(path);
        boolean hasMaxAge = maxAge != DefaultCookie.UNDEFINED_MAX_AGE;
        boolean hasExpires = StringUtils.isNotBlank(expires);
        boolean hasSameSite = StringUtils.isNotBlank(sameSite);
        String maxAgeStr = null;

        if (hasDomain) {
            len += PFX_DOMAIN.length() + domain.length();
        }
        if (hasPath) {
            len += PFX_PATH.length() + path.length();
        }
        if (hasMaxAge) {
            maxAgeStr = Long.toString(maxAge);
            len += PFX_MAXAGE.length() + maxAgeStr.length();
        }
        if (hasExpires) {
            len += PFX_EXPIRES.length() + expires.length();
        }
        if (secure) {
            len += PFX_SECURE.length();
        }
        if (httpOnly) {
            len += PFX_HTTPONLY.length();
        }
        if (hasSameSite) {
            len += PFX_SAMESITE.length() + sameSite.length();
        }

        char[] buf = new char[len];
        int pos = 0;

        name.getChars(0, name.length(), buf, pos);
        pos += name.length();
        buf[pos++] = '=';
        value.getChars(0, value.length(), buf, pos);
        pos += value.length();

        if (hasDomain) {
            PFX_DOMAIN.getChars(0, PFX_DOMAIN.length(), buf, pos);
            pos += PFX_DOMAIN.length();
            domain.getChars(0, domain.length(), buf, pos);
            pos += domain.length();
        }
        if (hasPath) {
            PFX_PATH.getChars(0, PFX_PATH.length(), buf, pos);
            pos += PFX_PATH.length();
            path.getChars(0, path.length(), buf, pos);
            pos += path.length();
        }
        if (hasMaxAge) {
            PFX_MAXAGE.getChars(0, PFX_MAXAGE.length(), buf, pos);
            pos += PFX_MAXAGE.length();
            maxAgeStr.getChars(0, maxAgeStr.length(), buf, pos);
            pos += maxAgeStr.length();
        }
        if (hasExpires) {
            PFX_EXPIRES.getChars(0, PFX_EXPIRES.length(), buf, pos);
            pos += PFX_EXPIRES.length();
            expires.getChars(0, expires.length(), buf, pos);
            pos += expires.length();
        }
        if (secure) {
            PFX_SECURE.getChars(0, PFX_SECURE.length(), buf, pos);
            pos += PFX_SECURE.length();
        }
        if (httpOnly) {
            PFX_HTTPONLY.getChars(0, PFX_HTTPONLY.length(), buf, pos);
            pos += PFX_HTTPONLY.length();
        }
        if (hasSameSite) {
            PFX_SAMESITE.getChars(0, PFX_SAMESITE.length(), buf, pos);
            pos += PFX_SAMESITE.length();
            sameSite.getChars(0, sameSite.length(), buf, pos);
            pos += sameSite.length();
        }

        return new String(buf, 0, pos);
    }
}