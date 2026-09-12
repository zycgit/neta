/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.cookie;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Encodes a single {@link Cookie} as the value of one HTTP response-side {@code Set-Cookie} header.
 * <p>The current implementation emits {@code name=value} and appends {@code Domain}, {@code Path},
 * {@code Max-Age}, {@code Expires}, {@code Secure}, {@code HttpOnly}, and {@code SameSite} when
 * the corresponding attributes have been set and satisfy the current output rules.
 * <p>This type is responsible only for generating one {@code Set-Cookie} header value and does not
 * merge multiple cookies into a single response header.
 * If multiple cookies need to be returned, whether they share the same name or not, the caller
 * should encode them separately and write them as multiple header entries.
 * In other words, a response with multiple cookies should emit one {@code Set-Cookie} header per cookie.
 * <h3>Usage Example</h3>
 * <pre>
 *   DefaultCookie c = new DefaultCookie("session", "abc123")
 *       .setPath("/")
 *       .setHttpOnly(true)
 *       .setMaxAge(3600);
 *   String headerValue = ServerCookieEncoder.encode(c);
 *   // "session=abc123; Path=/; Max-Age=3600; HttpOnly"
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class ServerCookieEncoder {
    // Precomputed attribute-prefix byte arrays.
    private static final byte[] PFX_DOMAIN   = "; Domain=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_PATH     = "; Path=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_MAXAGE   = "; Max-Age=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_EXPIRES  = "; Expires=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_SECURE   = "; Secure".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_HTTPONLY = "; HttpOnly".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PFX_SAMESITE = "; SameSite=".getBytes(StandardCharsets.US_ASCII);

    /**
     * Encodes a single cookie directly into a {@link ByteBuf} as the value of a {@code Set-Cookie} header.
     * Blank string attributes are not written to the result.
     * @param dst the destination buffer, which must not be {@code null}
     * @param cookie the cookie to encode, which must not be {@code null}
     * @throws IllegalArgumentException if {@code cookie} is {@code null}
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
     * Encodes a single cookie as a {@code Set-Cookie} header value.
     * Blank string attributes are not written to the result.
     * @param cookie the cookie to encode, which must not be {@code null}
     * @return the encoded {@code Set-Cookie} header value
     * @throws IllegalArgumentException if {@code cookie} is {@code null}
     */
    public static String encode(Cookie cookie) {
        if (cookie == null) {
            throw new IllegalArgumentException("cookie must not be null");
        }

        // Precompute the exact buffer size to avoid reallocation.
        String name = cookie.name();
        String value = cookie.value();
        String domain = cookie.domain();
        String path = cookie.path();
        long maxAge = cookie.maxAge();
        String expires = cookie.expires();
        boolean secure = cookie.isSecure();
        boolean httpOnly = cookie.isHttpOnly();
        String sameSite = cookie.sameSite();

        // Predefined attribute-prefix strings that can be constant-folded by the JIT.
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
