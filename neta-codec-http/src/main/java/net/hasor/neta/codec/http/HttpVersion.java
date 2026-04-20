/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
/**
 * Represents an HTTP protocol version as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-2.6">RFC 7230, Section 2.6</a>.
 * <pre>
 * HTTP-version = HTTP-name "/" DIGIT "." DIGIT
 * HTTP-name = %x48.54.54.50 ; "HTTP", case-sensitive
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class HttpVersion {
    /** HTTP/1.0 closes the connection by default after each request or response. */
    public static final HttpVersion HTTP_1_0 = new HttpVersion("HTTP", 1, 0, false);
    /** HTTP/1.1 keeps connections alive by default. See RFC 7230. */
    public static final HttpVersion HTTP_1_1 = new HttpVersion("HTTP", 1, 1, true);
    /** HTTP/2.0 uses binary framing and multiplexed streams. See RFC 7540 / RFC 9113. */
    public static final HttpVersion HTTP_2_0 = new HttpVersion("HTTP", 2, 0, true);
    /** HTTP/3.0 runs over QUIC transport. See RFC 9114. */
    public static final HttpVersion HTTP_3_0 = new HttpVersion("HTTP", 3, 0, true);

    private final String  protocolName;
    private final int     majorVersion;
    private final int     minorVersion;
    private final String  text;
    private final byte[]  textBytes;
    private final boolean keepAliveDefault;

    /**
     * Creates an HttpVersion instance.
     * @param protocolName protocol name, such as "HTTP"
     * @param majorVersion major version number
     * @param minorVersion minor version number
     * @param keepAliveDefault whether keep-alive is enabled by default
     */
    public HttpVersion(String protocolName, int majorVersion, int minorVersion, boolean keepAliveDefault) {
        if (protocolName == null || protocolName.trim().isEmpty()) {
            throw new IllegalArgumentException("protocolName must not be empty");
        }
        if (majorVersion < 0) {
            throw new IllegalArgumentException("majorVersion must be >= 0");
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("minorVersion must be >= 0");
        }

        this.protocolName = protocolName.toUpperCase();
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.text = this.protocolName + "/" + majorVersion + "." + minorVersion;
        this.textBytes = this.text.getBytes(StandardCharsets.US_ASCII);
        this.keepAliveDefault = keepAliveDefault;
    }

    /**
     * Parses an HTTP version string such as "HTTP/1.1" and returns the matching {@link HttpVersion} instance.
     * Returns a cached constant when the input matches a known version.
     * @param text version string to parse
     * @return the matching HttpVersion
     * @throws IllegalArgumentException if the text does not follow the HTTP version format
     */
    public static HttpVersion valueOf(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        // Fast path: direct comparison for canonical HTTP/1.x text without surrounding whitespace.
        if ("HTTP/1.1".equals(text)) {
            return HTTP_1_1;
        }
        if ("HTTP/1.0".equals(text)) {
            return HTTP_1_0;
        }

        // Slow path: normalize first, then try again.
        text = text.trim().toUpperCase();
        if ("HTTP/1.1".equals(text)) {
            return HTTP_1_1;
        }
        if ("HTTP/1.0".equals(text)) {
            return HTTP_1_0;
        }

        // Parse a custom version in PROTOCOL/MAJOR.MINOR form.
        int slashIdx = text.indexOf('/');
        if (slashIdx < 0) {
            throw new IllegalArgumentException("invalid version format: " + text);
        }
        String protocol = text.substring(0, slashIdx);
        String versionPart = text.substring(slashIdx + 1);
        int dotIdx = versionPart.indexOf('.');
        if (dotIdx < 0) {
            throw new IllegalArgumentException("invalid version format: " + text);
        }

        try {
            int major = Integer.parseInt(versionPart.substring(0, dotIdx));
            int minor = Integer.parseInt(versionPart.substring(dotIdx + 1));
            return new HttpVersion(protocol, major, minor, major > 0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid version format: " + text, e);
        }
    }

    /**
     * Parses an HTTP version from a {@link CharSequence} to avoid creating a String too early.
     * @param text version text to parse
     * @return the matching HttpVersion
     */
    public static HttpVersion valueOf(CharSequence text) {
        if (text == null || text.length() == 0) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (text instanceof String) {
            return valueOf((String) text);
        }
        if (matches(text, "HTTP/1.1")) {
            return HTTP_1_1;
        }
        if (matches(text, "HTTP/1.0")) {
            return HTTP_1_0;
        }
        if (matches(text, "HTTP/2.0")) {
            return HTTP_2_0;
        }
        if (matches(text, "HTTP/3.0")) {
            return HTTP_3_0;
        }

        return valueOf(text.toString());
    }

    private static boolean matches(CharSequence left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < right.length(); i++) {
            char c1 = left.charAt(i);
            char c2 = right.charAt(i);
            if (c1 == c2) {
                continue;
            }
            if (c1 >= 'a' && c1 <= 'z') {
                c1 = (char) (c1 - 32);
            }
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the protocol name, such as "HTTP".
     * @return protocol name
     */
    public String protocolName() {
        return protocolName;
    }

    /**
     * Returns the major version number, such as 1 in HTTP/1.1.
     * @return major version number
     */
    public int majorVersion() {
        return majorVersion;
    }

    /**
     * Returns the minor version number, such as 1 in HTTP/1.1.
     * @return minor version number
     */
    public int minorVersion() {
        return minorVersion;
    }

    /**
     * Returns the full version text, such as "HTTP/1.1".
     * @return full version text
     */
    public String text() {
        return text;
    }

    /**
     * Returns the cached ASCII bytes of the version text, such as "HTTP/1.1".
     * @return version text bytes
     */
    public byte[] textBytes() {
        return textBytes;
    }

    /**
     * Returns the default persistence behavior for this version when evaluating connection keep-alive.
     * <p>
     * The current implementation returns false for HTTP/1.0 and true for HTTP/1.1, HTTP/2.0, and HTTP/3.0.
     * @return whether the connection is kept alive by default
     */
    public boolean isKeepAliveDefault() {
        return keepAliveDefault;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public int hashCode() {
        return (protocolName.hashCode() * 31 + majorVersion) * 31 + minorVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpVersion)) {
            return false;
        }
        HttpVersion that = (HttpVersion) o;
        return majorVersion == that.majorVersion && //
                minorVersion == that.minorVersion &&//
                protocolName.equals(that.protocolName);
    }
}
