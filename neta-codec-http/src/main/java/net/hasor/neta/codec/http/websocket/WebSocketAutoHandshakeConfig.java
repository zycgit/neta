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
 * Configuration object for automatically initiating a client-side handshake.
 * <p>
 * Provides the request path, additional headers, and cookies needed to send the
 * handshake request automatically after channel activation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-24
 */
public class WebSocketAutoHandshakeConfig {
    private final String             requestPath;
    private final DefaultHttpHeaders headers;
    private final Cookie[]           cookies;

    /**
     * Create the auto-handshake configuration with the specified request path.
     * @param requestPath handshake request path
     */
    public WebSocketAutoHandshakeConfig(String requestPath) {
        this(requestPath, null);
    }

    /**
     * Create the auto-handshake configuration with the specified arguments.
     * @param requestPath handshake request path
     * @param headers additional request headers
     * @param cookies additional cookies
     */
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

    /**
     * Return the handshake request path.
     */
    public String requestPath() {
        return this.requestPath;
    }

    /**
     * Return a copy of the additional request headers.
     */
    public HttpHeaders headers() {
        return new DefaultHttpHeaders().appendHeaders(this.headers);
    }

    /**
     * Return a copy of the additional cookie array.
     */
    public Cookie[] cookies() {
        return Arrays.copyOf(this.cookies, this.cookies.length);
    }
}