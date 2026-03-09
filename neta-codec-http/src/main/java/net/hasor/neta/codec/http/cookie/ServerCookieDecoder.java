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
import net.hasor.neta.bytebuf.StringView;

/**
 * Decodes the value of an HTTP <b>response</b> {@code Set-Cookie} header into a
 * {@link DefaultCookie} instance.
 * <p>A {@code Set-Cookie} header carries a single cookie definition with optional
 * attributes, per <a href="https://tools.ietf.org/html/rfc6265#section-4.1">RFC 6265 §4.1</a>:
 * <pre>
 *   Set-Cookie: name=value; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly; SameSite=Lax
 * </pre>
 * <h3>Usage</h3>
 * <pre>
 *   String header = "session=abc; Path=/api; HttpOnly; Secure; Max-Age=3600";
 *   DefaultCookie cookie = ServerCookieDecoder.decode(header);
 * </pre>
 */
public final class ServerCookieDecoder {
    // pre-computed lowercase attribute names for byte-level comparison
    private static final byte[] SECURE   = "secure".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTPONLY = "httponly".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN   = "domain".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PATH     = "path".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAX_AGE  = "max-age".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXPIRES  = "expires".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SAMESITE = "samesite".getBytes(StandardCharsets.US_ASCII);

    /**
     * Decodes one {@code Set-Cookie} response header value from a {@link ByteBuf}.
     * The buffer's readerIndex is not modified.
     * @param buf the buffer containing the raw {@code Set-Cookie} header value; may be {@code null}
     * @return the decoded {@link DefaultCookie}, or {@code null} if the input is null or empty
     * @throws IllegalArgumentException if the header does not contain a valid {@code name=value} pair
     */
    public static DefaultCookie decode(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0) {
            return null;
        }

        final int length = buf.readableBytes();

        // find first ';' to isolate name=value pair
        int firstSemi = CookieUtils.indexOf(buf, 0, length, (byte) ';');
        int firstEnd = firstSemi < 0 ? length : firstSemi;

        // find '='
        int eqIdx = CookieUtils.indexOf(buf, 0, firstEnd, (byte) '=');
        if (eqIdx <= 0) {
            throw new IllegalArgumentException("Invalid Set-Cookie header: missing '=' in name=value pair: " + buf.getString(0, firstEnd, StandardCharsets.US_ASCII));
        }

        // trim name
        int nameStart = 0;
        while (nameStart < eqIdx && buf.getByte(nameStart) <= ' ') {
            nameStart++;
        }
        int nameEnd = eqIdx;
        while (nameEnd > nameStart && buf.getByte(nameEnd - 1) <= ' ') {
            nameEnd--;
        }

        // trim value
        int valStart = eqIdx + 1;
        while (valStart < firstEnd && buf.getByte(valStart) <= ' ') {
            valStart++;
        }
        int valEnd = firstEnd;
        while (valEnd > valStart && buf.getByte(valEnd - 1) <= ' ') {
            valEnd--;
        }

        // unquote
        if (valEnd - valStart >= 2 && buf.getByte(valStart) == '"' && buf.getByte(valEnd - 1) == '"') {
            valStart++;
            valEnd--;
        }

        DefaultCookie cookie = new DefaultCookie(                          //
                new StringView(buf, nameStart, nameEnd - nameStart),//
                new StringView(buf, valStart, valEnd - valStart));

        // parse attributes
        int pos = firstSemi < 0 ? length : firstSemi + 1;
        while (pos < length) {
            int nextSemi = CookieUtils.indexOf(buf, pos, length, (byte) ';');
            int end = nextSemi < 0 ? length : nextSemi;

            int start = pos;
            while (start < end && buf.getByte(start) <= ' ') {
                start++;
            }
            int attrEnd = end;
            while (attrEnd > start && buf.getByte(attrEnd - 1) <= ' ') {
                attrEnd--;
            }

            if (start < attrEnd) {
                int attrEq = CookieUtils.indexOf(buf, start, attrEnd, (byte) '=');
                if (attrEq < 0 || attrEq >= attrEnd) {
                    int attrLen = attrEnd - start;
                    if (attrLen == 6 && CookieUtils.equalsIgnoreCase(buf, start, SECURE)) {
                        cookie.setSecure(true);
                    } else if (attrLen == 8 && CookieUtils.equalsIgnoreCase(buf, start, HTTPONLY)) {
                        cookie.setHttpOnly(true);
                    }
                } else {
                    int keyLen = attrEq - start;
                    int aValStart = attrEq + 1;
                    while (aValStart < attrEnd && buf.getByte(aValStart) <= ' ') {
                        aValStart++;
                    }

                    if (keyLen == 6 && CookieUtils.equalsIgnoreCase(buf, start, DOMAIN)) {
                        cookie.setLazyDomain(new StringView(buf, aValStart, attrEnd - aValStart));
                    } else if (keyLen == 4 && CookieUtils.equalsIgnoreCase(buf, start, PATH)) {
                        cookie.setLazyPath(new StringView(buf, aValStart, attrEnd - aValStart));
                    } else if (keyLen == 7 && CookieUtils.equalsIgnoreCase(buf, start, MAX_AGE)) {
                        long maxAge = 0;
                        boolean negative = false;
                        int mi = aValStart;
                        if (mi < attrEnd && buf.getByte(mi) == '-') {
                            negative = true;
                            mi++;
                        }
                        boolean valid = false;
                        for (; mi < attrEnd; mi++) {
                            byte mc = buf.getByte(mi);
                            if (mc < '0' || mc > '9') {
                                break;
                            }
                            maxAge = maxAge * 10 + (mc - '0');
                            valid = true;
                        }
                        if (valid) {
                            cookie.setMaxAge(negative ? -maxAge : maxAge);
                        }
                    } else if (keyLen == 7 && CookieUtils.equalsIgnoreCase(buf, start, EXPIRES)) {
                        cookie.setLazyExpires(new StringView(buf, aValStart, attrEnd - aValStart));
                    } else if (keyLen == 8 && CookieUtils.equalsIgnoreCase(buf, start, SAMESITE)) {
                        cookie.setLazySameSite(new StringView(buf, aValStart, attrEnd - aValStart));
                    }
                }
            }

            pos = nextSemi < 0 ? length : nextSemi + 1;
        }

        return cookie;
    }

    /**
     * Decodes one {@code Set-Cookie} response header value.
     * @param setCookieHeader the raw {@code Set-Cookie} header value; may be {@code null}
     * @return the decoded {@link DefaultCookie}, or {@code null} if the input is null or blank
     * @throws IllegalArgumentException if the header does not contain a valid {@code name=value} pair
     */
    public static DefaultCookie decode(String setCookieHeader) {
        if (StringUtils.isBlank(setCookieHeader)) {
            return null;
        }

        int length = setCookieHeader.length();

        // Find first semicolon to isolate name=value pair
        int firstSemi = setCookieHeader.indexOf(';');
        int firstEnd = firstSemi < 0 ? length : firstSemi;

        // Parse name=value (first token)
        int eqIdx = setCookieHeader.indexOf('=');
        if (eqIdx <= 0 || eqIdx >= firstEnd) {
            throw new IllegalArgumentException("Invalid Set-Cookie header: missing '=' in name=value pair: " + setCookieHeader.substring(0, firstEnd));
        }

        // Trim name
        int nameStart = 0;
        while (nameStart < eqIdx && setCookieHeader.charAt(nameStart) <= ' ') {
            nameStart++;
        }
        int nameEnd = eqIdx;
        while (nameEnd > nameStart && setCookieHeader.charAt(nameEnd - 1) <= ' ') {
            nameEnd--;
        }
        String name = setCookieHeader.substring(nameStart, nameEnd);

        // Trim value
        int valStart = eqIdx + 1;
        while (valStart < firstEnd && setCookieHeader.charAt(valStart) <= ' ') {
            valStart++;
        }
        int valEnd = firstEnd;
        while (valEnd > valStart && setCookieHeader.charAt(valEnd - 1) <= ' ') {
            valEnd--;
        }
        String value = setCookieHeader.substring(valStart, valEnd);

        // Unquote value if quoted
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }

        DefaultCookie cookie = new DefaultCookie(name, value);

        // Parse attributes using indexOf-based iteration (avoids split() and array allocation)
        int pos = firstSemi < 0 ? length : firstSemi + 1;
        while (pos < length) {
            int nextSemi = setCookieHeader.indexOf(';', pos);
            int end = nextSemi < 0 ? length : nextSemi;

            // Trim attribute
            int start = pos;
            while (start < end && setCookieHeader.charAt(start) <= ' ') {
                start++;
            }
            int attrEnd = end;
            while (attrEnd > start && setCookieHeader.charAt(attrEnd - 1) <= ' ') {
                attrEnd--;
            }

            if (start < attrEnd) {
                int attrEq = setCookieHeader.indexOf('=', start);
                if (attrEq < 0 || attrEq >= attrEnd) {
                    // Flag attribute (no value) - use regionMatches for case-insensitive comparison
                    int attrLen = attrEnd - start;
                    if (attrLen == 6 && setCookieHeader.regionMatches(true, start, "secure", 0, 6)) {
                        cookie.setSecure(true);
                    } else if (attrLen == 8 && setCookieHeader.regionMatches(true, start, "httponly", 0, 8)) {
                        cookie.setHttpOnly(true);
                    }
                } else {
                    // key=value attribute - use regionMatches instead of toLowerCase().startsWith()
                    int keyLen = attrEq - start;
                    // Trim value boundaries (defer substring creation to matching branch)
                    int aValStart = attrEq + 1;
                    while (aValStart < attrEnd && setCookieHeader.charAt(aValStart) <= ' ') {
                        aValStart++;
                    }

                    if (keyLen == 6 && setCookieHeader.regionMatches(true, start, "domain", 0, 6)) {
                        cookie.setDomain(setCookieHeader.substring(aValStart, attrEnd));
                    } else if (keyLen == 4 && setCookieHeader.regionMatches(true, start, "path", 0, 4)) {
                        cookie.setPath(setCookieHeader.substring(aValStart, attrEnd));
                    } else if (keyLen == 7 && setCookieHeader.regionMatches(true, start, "max-age", 0, 7)) {
                        // Parse long directly without substring allocation
                        long maxAge = 0;
                        boolean negative = false;
                        int mi = aValStart;
                        if (mi < attrEnd && setCookieHeader.charAt(mi) == '-') {
                            negative = true;
                            mi++;
                        }
                        boolean valid = false;
                        for (; mi < attrEnd; mi++) {
                            char mc = setCookieHeader.charAt(mi);
                            if (mc < '0' || mc > '9') {
                                break;
                            }
                            maxAge = maxAge * 10 + (mc - '0');
                            valid = true;
                        }
                        if (valid) {
                            cookie.setMaxAge(negative ? -maxAge : maxAge);
                        }
                    } else if (keyLen == 7 && setCookieHeader.regionMatches(true, start, "expires", 0, 7)) {
                        cookie.setExpires(setCookieHeader.substring(aValStart, attrEnd));
                    } else if (keyLen == 8 && setCookieHeader.regionMatches(true, start, "samesite", 0, 8)) {
                        cookie.setSameSite(setCookieHeader.substring(aValStart, attrEnd));
                    }
                    // Unknown attributes are silently ignored per RFC 6265
                }
            }

            pos = nextSemi < 0 ? length : nextSemi + 1;
        }

        return cookie;
    }
}