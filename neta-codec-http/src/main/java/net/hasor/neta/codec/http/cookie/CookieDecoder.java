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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Decodes the HTTP request-side {@code Cookie} header value into a list of {@link Cookie} objects.
 * <p>A request {@code Cookie} header consists of one or more {@code name=value} fragments separated
 * by semicolons, and whitespace is trimmed at fragment boundaries. The current implementation skips
 * fragments that have no name, no equals sign, or unclosed double quotes.
 * <p>In other words, if a request contains multiple cookies, a single {@code Cookie} header value
 * should be read first and then decoded into a list by this type.
 * If the same header value contains multiple cookies with the same name, the current implementation
 * preserves all of them in encounter order.
 * <h3>Usage Example</h3>
 * <pre>
 *   String header = "session=abc123; lang=en; theme=dark";
 *   List&lt;Cookie&gt; cookies = CookieDecoder.decode(header);
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class CookieDecoder {
    /**
     * Decodes the HTTP request {@code Cookie} header value from a {@link ByteBuf}.
     * This method does not modify the buffer readerIndex.
     * @param buf the buffer containing the raw {@code Cookie} header value; may be {@code null}
     * @return an unmodifiable cookie list; returns an empty list when the input is {@code null},
     * has no readable bytes, or all fragments are invalid
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
                    buf.getString(segStart, nameEnd - segStart, StandardCharsets.US_ASCII),//
                    buf.getString(valStart, valEnd - valStart, StandardCharsets.US_ASCII)));
            pos = semiIdx + 1;
        }

        return Collections.unmodifiableList(cookies);
    }

    /**
     * Decodes an HTTP request {@code Cookie} header value.
     * The current implementation skips fragments that have no name, no equals sign, or unclosed
     * double quotes.
     * @param cookieHeader the raw {@code Cookie} header value; may be {@code null}
     * @return an unmodifiable cookie list; returns an empty list when the input is {@code null},
     * blank, or all fragments are invalid
     */
    public static List<Cookie> decode(String cookieHeader) {
        if (StringUtils.isBlank(cookieHeader)) {
            return Collections.emptyList();
        }

        final int len = cookieHeader.length();
        List<Cookie> cookies = new ArrayList<>();

        int pos = 0;
        while (pos < len) {
            // Find ';' as the current fragment boundary, or the end of the string.
            int semiIdx = cookieHeader.indexOf(';', pos);
            if (semiIdx < 0) {
                semiIdx = len;
            }

            // Skip leading whitespace of the current fragment.
            int segStart = pos;
            while (segStart < semiIdx && cookieHeader.charAt(segStart) == ' ') {
                segStart++;
            }

            // Find '=' inside the current fragment.
            int eqIdx = cookieHeader.indexOf('=', segStart);
            if (eqIdx < 0 || eqIdx >= semiIdx || eqIdx == segStart) {
                // The current fragment has no '=', or the name is empty, so skip it.
                pos = semiIdx + 1;
                continue;
            }

            // Extract the name and trim trailing spaces.
            int nameEnd = eqIdx;
            while (nameEnd > segStart && cookieHeader.charAt(nameEnd - 1) == ' ') {
                nameEnd--;
            }
            if (nameEnd <= segStart) {
                pos = semiIdx + 1;
                continue;
            }

            // Extract the value and trim surrounding spaces and optional quotes.
            int valStart = eqIdx + 1;
            while (valStart < semiIdx && cookieHeader.charAt(valStart) == ' ') {
                valStart++;
            }
            int valEnd = semiIdx;
            while (valEnd > valStart && cookieHeader.charAt(valEnd - 1) == ' ') {
                valEnd--;
            }

            // RFC 6265: cookie-value may be wrapped in double quotes.
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
