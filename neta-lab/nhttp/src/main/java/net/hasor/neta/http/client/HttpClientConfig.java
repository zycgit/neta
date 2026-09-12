/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http.client;

import net.hasor.neta.codec.http.websocket.WebSocketVersion;
import net.hasor.neta.codec.ssl.SslConfig;

/**
 * Runtime options for {@link NetaHttpClient}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpClientConfig {
    private HttpVersionPolicy versionPolicy    = HttpVersionPolicy.AUTO;
    private SslConfig         sslConfig;
    private int               maxContentLength = 1048576;
    private long              timeoutMillis    = 30000L;
    private WebSocketVersion  webSocketVersion = WebSocketVersion.V13;

    public HttpVersionPolicy getVersionPolicy() {
        return this.versionPolicy;
    }

    public HttpClientConfig versionPolicy(HttpVersionPolicy versionPolicy) {
        this.versionPolicy = versionPolicy == null ? HttpVersionPolicy.AUTO : versionPolicy;
        return this;
    }

    public SslConfig getSslConfig() {
        return this.sslConfig;
    }

    public HttpClientConfig ssl(SslConfig sslConfig) {
        this.sslConfig = sslConfig;
        return this;
    }

    public int getMaxContentLength() {
        return this.maxContentLength;
    }

    public HttpClientConfig maxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
        return this;
    }

    public long getTimeoutMillis() {
        return this.timeoutMillis;
    }

    public HttpClientConfig timeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public WebSocketVersion getWebSocketVersion() {
        return this.webSocketVersion;
    }

    public HttpClientConfig webSocketVersion(WebSocketVersion webSocketVersion) {
        this.webSocketVersion = webSocketVersion == null ? WebSocketVersion.V13 : webSocketVersion;
        return this;
    }
}
