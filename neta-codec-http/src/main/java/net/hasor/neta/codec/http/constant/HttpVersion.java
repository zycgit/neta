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
package net.hasor.neta.codec.http.constant;
import java.nio.charset.StandardCharsets;

/**
 * Represents an HTTP protocol version as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-2.6">RFC 7230, Section 2.6</a>.
 * <pre>
 *   HTTP-version = HTTP-name "/" DIGIT "." DIGIT
 *   HTTP-name    = %x48.54.54.50 ; "HTTP", case-sensitive
 * </pre>
 */
public final class HttpVersion {

    /** HTTP/1.0 - connection is closed after each request/response by default. */
    public static final HttpVersion HTTP_1_0 = new HttpVersion("HTTP", 1, 0, false);

    /** HTTP/1.1 - connection is kept alive by default (RFC 7230). */
    public static final HttpVersion HTTP_1_1 = new HttpVersion("HTTP", 1, 1, true);

    /** HTTP/2.0 - binary framing, multiplexed streams (RFC 7540 / RFC 9113). */
    public static final HttpVersion HTTP_2_0 = new HttpVersion("HTTP", 2, 0, true);

    /** HTTP/3.0 - QUIC-based transport (RFC 9114). */
    public static final HttpVersion HTTP_3_0 = new HttpVersion("HTTP", 3, 0, true);

    private final String  protocolName;
    private final int     majorVersion;
    private final int     minorVersion;
    private final String  text;
    private final byte[]  textBytes;
    private final boolean keepAliveDefault;

    /**
     * Creates a new HttpVersion instance.
     * @param protocolName the protocol name (e.g., "HTTP")
     * @param majorVersion the major version number
     * @param minorVersion the minor version number
     * @param keepAliveDefault whether keep-alive is the default behavior
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
     * Parses an HTTP version string (e.g., "HTTP/1.1") and returns the corresponding
     * {@link HttpVersion} instance. If the string matches a well-known version,
     * the cached constant is returned.
     * @param text the version string to parse
     * @return the matching HttpVersion
     * @throws IllegalArgumentException if the text is not a valid HTTP version
     */
    public static HttpVersion valueOf(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        // Fast path: direct comparison (works for canonical HTTP/1.x without whitespace)
        if ("HTTP/1.1".equals(text)) {
            return HTTP_1_1;
        }
        if ("HTTP/1.0".equals(text)) {
            return HTTP_1_0;
        }

        // Slow path: normalize and retry
        text = text.trim().toUpperCase();
        if ("HTTP/1.1".equals(text)) {
            return HTTP_1_1;
        }
        if ("HTTP/1.0".equals(text)) {
            return HTTP_1_0;
        }

        // Parse custom version: PROTOCOL/MAJOR.MINOR
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

    /** Returns the protocol name (e.g., "HTTP"). */
    public String protocolName() {
        return protocolName;
    }

    /** Returns the major version number (e.g., 1 in HTTP/1.1). */
    public int majorVersion() {
        return majorVersion;
    }

    /** Returns the minor version number (e.g., 1 in HTTP/1.1). */
    public int minorVersion() {
        return minorVersion;
    }

    /** Returns the full version text (e.g., "HTTP/1.1"). */
    public String text() {
        return text;
    }

    /** Returns the pre-cached ASCII bytes of the version text (e.g., "HTTP/1.1"). */
    public byte[] textBytes() {
        return textBytes;
    }

    /**
     * Returns {@code true} if the connection is kept alive by default
     * unless the "Connection" header is set to "close" explicitly.
     * <p>
     * Per RFC 7230, HTTP/1.1 defaults to keep-alive, while HTTP/1.0 defaults to close.
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
        return majorVersion == that.majorVersion && minorVersion == that.minorVersion && protocolName.equals(that.protocolName);
    }
}
