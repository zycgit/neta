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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.StringView;

/**
 * Decodes the value of the HTTP <b>request</b> {@code Cookie} header into a list of
 * {@link Cookie} objects.
 * <p>A request {@code Cookie} header contains one or more {@code name=value} pairs
 * separated by {@code "; "} (semicolon followed by space), as specified in
 * <a href="https://tools.ietf.org/html/rfc6265#section-4.2">RFC 6265 §4.2</a>.
 * <h3>Usage</h3>
 * <pre>
 *   String header = "session=abc123; lang=en; theme=dark";
 *   List&lt;Cookie&gt; cookies = CookieDecoder.decode(header);
 * </pre>
 */
public final class CookieDecoder {
    /**
     * Decodes the value of an HTTP {@code Cookie} request header from a {@link ByteBuf}.
     * The buffer's readerIndex is not modified.
     * @param buf the buffer containing the raw {@code Cookie} header value; may be {@code null}
     * @return an unmodifiable list of decoded cookies; empty if input is null or has no readable bytes
     */
    public static List<Cookie> decode(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0) {
            return Collections.emptyList();
        }

        final int len = buf.readableBytes();
        List<Cookie> cookies = new ArrayList<>();
        int pos = 0;

        while (pos < len) {
            int semiIdx = CookieUtils.indexOf(buf, pos, len, (byte) ';');
            if (semiIdx < 0) {
                semiIdx = len;
            }

            int segStart = pos;
            while (segStart < semiIdx && buf.getByte(segStart) == ' ') {
                segStart++;
            }

            int eqIdx = CookieUtils.indexOf(buf, segStart, semiIdx, (byte) '=');
            if (eqIdx < 0 || eqIdx == segStart) {
                pos = semiIdx + 1;
                continue;
            }

            int nameEnd = eqIdx;
            while (nameEnd > segStart && buf.getByte(nameEnd - 1) == ' ') {
                nameEnd--;
            }
            if (nameEnd <= segStart) {
                pos = semiIdx + 1;
                continue;
            }

            int valStart = eqIdx + 1;
            while (valStart < semiIdx && buf.getByte(valStart) == ' ') {
                valStart++;
            }
            int valEnd = semiIdx;
            while (valEnd > valStart && buf.getByte(valEnd - 1) == ' ') {
                valEnd--;
            }

            if (valStart < valEnd && buf.getByte(valStart) == '"') {
                if (valEnd - valStart < 2 || buf.getByte(valEnd - 1) != '"') {
                    pos = semiIdx + 1;
                    continue;
                }
                valStart++;
                valEnd--;
            }

            cookies.add(new DefaultCookie(//
                    StringView.request(buf, segStart, nameEnd - segStart),//
                    StringView.request(buf, valStart, valEnd - valStart)));
            pos = semiIdx + 1;
        }

        return Collections.unmodifiableList(cookies);
    }

    /**
     * Decodes the value of an HTTP {@code Cookie} request header.
     * @param cookieHeader the raw value of the {@code Cookie} header; may be {@code null}
     * @return an unmodifiable list of decoded cookies; empty if input is null or blank
     */
    public static List<Cookie> decode(String cookieHeader) {
        if (StringUtils.isBlank(cookieHeader)) {
            return Collections.emptyList();
        }

        final int len = cookieHeader.length();
        List<Cookie> cookies = new ArrayList<>();

        int pos = 0;
        while (pos < len) {
            // find ';' for segment boundary (or end of string)
            int semiIdx = cookieHeader.indexOf(';', pos);
            if (semiIdx < 0) {
                semiIdx = len;
            }

            // skip leading whitespace in this segment
            int segStart = pos;
            while (segStart < semiIdx && cookieHeader.charAt(segStart) == ' ') {
                segStart++;
            }

            // find '=' within this segment
            int eqIdx = cookieHeader.indexOf('=', segStart);
            if (eqIdx < 0 || eqIdx >= semiIdx || eqIdx == segStart) {
                // No '=' in this segment, or name is empty – skip
                pos = semiIdx + 1;
                continue;
            }

            // extract name (trim trailing spaces)
            int nameEnd = eqIdx;
            while (nameEnd > segStart && cookieHeader.charAt(nameEnd - 1) == ' ') {
                nameEnd--;
            }
            if (nameEnd <= segStart) {
                pos = semiIdx + 1;
                continue;
            }

            // extract value (trim leading/trailing spaces, strip optional quotes)
            int valStart = eqIdx + 1;
            while (valStart < semiIdx && cookieHeader.charAt(valStart) == ' ') {
                valStart++;
            }
            int valEnd = semiIdx;
            while (valEnd > valStart && cookieHeader.charAt(valEnd - 1) == ' ') {
                valEnd--;
            }

            // RFC 6265: cookie-value may optionally be enclosed in double quotes
            if (valStart < valEnd && cookieHeader.charAt(valStart) == '"') {
                if (valEnd - valStart < 2 || cookieHeader.charAt(valEnd - 1) != '"') {
                    pos = semiIdx + 1;
                    continue;
                }
                valStart++;
                valEnd--;
            }

            String name = cookieHeader.substring(segStart, nameEnd);
            String value = cookieHeader.substring(valStart, valEnd);
            cookies.add(new DefaultCookie(name, value));

            pos = semiIdx + 1;
        }

        return Collections.unmodifiableList(cookies);
    }
}
