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

    private CookieDecoder() {
    }

    /**
     * Decodes the value of an HTTP {@code Cookie} request header.
     * @param cookieHeader the raw value of the {@code Cookie} header; may be {@code null}
     * @return an unmodifiable list of decoded cookies; empty if input is null or blank
     */
    public static List<Cookie> decode(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return Collections.emptyList();
        }

        List<Cookie> cookies = new ArrayList<>();
        // Pairs are separated by "; " (semicolon + optional whitespace)
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) {
                // No '=' or name is empty – skip malformed token
                continue;
            }
            String name = pair.substring(0, eqIdx).trim();
            String value = pair.substring(eqIdx + 1).trim();
            // RFC 6265: cookie-value may optionally be enclosed in double quotes
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            if (!name.isEmpty()) {
                cookies.add(new DefaultCookie(name, value));
            }
        }
        return Collections.unmodifiableList(cookies);
    }
}
