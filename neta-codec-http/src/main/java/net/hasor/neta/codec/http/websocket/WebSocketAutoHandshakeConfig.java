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
package net.hasor.neta.codec.http.websocket;
import java.util.Arrays;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.cookie.Cookie;

/**
 * Configuration for client-side automatic opening handshake.
 * <p>
 * Supplies request path, extra headers, and cookies for the handshake sent on channel activation.
 */
public class WebSocketAutoHandshakeConfig {
    private final String             requestPath;
    private final DefaultHttpHeaders headers;
    private final Cookie[]           cookies;

    public WebSocketAutoHandshakeConfig(String requestPath) {
        this(requestPath, null);
    }

    public WebSocketAutoHandshakeConfig(String requestPath, HttpHeaders headers, Cookie... cookies) {
        if (StringUtils.isBlank(requestPath)) {
            throw new IllegalArgumentException("requestPath must not be blank");
        }
        this.requestPath = requestPath;
        this.headers = new DefaultHttpHeaders();
        if (headers != null) {
            this.headers.appendHeaders(headers);
        }
        this.cookies = cookies == null ? new Cookie[0] : Arrays.copyOf(cookies, cookies.length);
    }

    public String requestPath() {
        return this.requestPath;
    }

    public HttpHeaders headers() {
        return new DefaultHttpHeaders().appendHeaders(this.headers);
    }

    public Cookie[] cookies() {
        return Arrays.copyOf(this.cookies, this.cookies.length);
    }
}