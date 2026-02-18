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

    private ServerCookieDecoder() {
    }

    /**
     * Decodes one {@code Set-Cookie} response header value.
     * @param setCookieHeader the raw {@code Set-Cookie} header value; may be {@code null}
     * @return the decoded {@link DefaultCookie}, or {@code null} if the input is null or blank
     * @throws IllegalArgumentException if the header does not contain a valid {@code name=value} pair
     */
    public static DefaultCookie decode(String setCookieHeader) {
        if (setCookieHeader == null || setCookieHeader.isEmpty()) {
            return null;
        }

        String[] parts = setCookieHeader.split(";");
        if (parts.length == 0) {
            return null;
        }

        // First token is always name=value
        String nvPair = parts[0].trim();
        int eqIdx = nvPair.indexOf('=');
        if (eqIdx <= 0) {
            throw new IllegalArgumentException("Invalid Set-Cookie header: missing '=' in name=value pair: " + nvPair);
        }
        String name = nvPair.substring(0, eqIdx).trim();
        String value = nvPair.substring(eqIdx + 1).trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }

        DefaultCookie cookie = new DefaultCookie(name, value);

        // Parse attributes
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.isEmpty()) {
                continue;
            }
            String attrLower = attr.toLowerCase();
            if (attrLower.equals("secure")) {
                cookie.setSecure(true);
            } else if (attrLower.equals("httponly")) {
                cookie.setHttpOnly(true);
            } else if (attrLower.startsWith("domain=")) {
                cookie.setDomain(attr.substring("domain=".length()).trim());
            } else if (attrLower.startsWith("path=")) {
                cookie.setPath(attr.substring("path=".length()).trim());
            } else if (attrLower.startsWith("max-age=")) {
                String maxAgeStr = attr.substring("max-age=".length()).trim();
                try {
                    cookie.setMaxAge(Long.parseLong(maxAgeStr));
                } catch (NumberFormatException ignored) {
                    // ignore malformed max-age
                }
            } else if (attrLower.startsWith("expires=")) {
                cookie.setExpires(attr.substring("expires=".length()).trim());
            } else if (attrLower.startsWith("samesite=")) {
                cookie.setSameSite(attr.substring("samesite=".length()).trim());
            }
            // Unknown attributes are silently ignored per RFC 6265
        }

        return cookie;
    }
}
