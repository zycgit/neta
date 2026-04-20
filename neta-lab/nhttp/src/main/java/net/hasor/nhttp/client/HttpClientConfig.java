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
package net.hasor.nhttp.client;

import net.hasor.neta.codec.http.websocket.WebSocketVersion;
import net.hasor.neta.codec.ssl.SslConfig;

/**
 * Immutable runtime configuration for {@link HttpClient}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpClientConfig {
    private final HttpVersionPolicy versionPolicy;
    private final SslConfig         sslConfig;
    private final int               maxContentLength;
    private final long              connectTimeoutMillis;
    private final long              readTimeoutMillis;
    private final long              writeTimeoutMillis;
    private final long              callTimeoutMillis;
    private final long              webSocketOpenTimeoutMillis;
    private final int               maxConcurrentCalls;
    private final int               maxConcurrentCallsPerHost;
    private final WebSocketVersion  webSocketVersion;

    HttpClientConfig(HttpClient.Builder builder) {
        this.versionPolicy = builder.versionPolicy;
        this.sslConfig = builder.sslConfig;
        this.maxContentLength = builder.maxContentLength;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.writeTimeoutMillis = builder.writeTimeoutMillis;
        this.callTimeoutMillis = builder.callTimeoutMillis;
        this.webSocketOpenTimeoutMillis = builder.webSocketOpenTimeoutMillis;
        this.maxConcurrentCalls = builder.maxConcurrentCalls;
        this.maxConcurrentCallsPerHost = builder.maxConcurrentCallsPerHost;
        this.webSocketVersion = builder.webSocketVersion;
    }

    public HttpVersionPolicy getVersionPolicy() {
        return this.versionPolicy;
    }

    public SslConfig getSslConfig() {
        return this.sslConfig;
    }

    public int getMaxContentLength() {
        return this.maxContentLength;
    }

    public long getConnectTimeoutMillis() {
        return this.connectTimeoutMillis;
    }

    public long getReadTimeoutMillis() {
        return this.readTimeoutMillis;
    }

    public long getWriteTimeoutMillis() {
        return this.writeTimeoutMillis;
    }

    public long getCallTimeoutMillis() {
        return this.callTimeoutMillis;
    }

    public long getWebSocketOpenTimeoutMillis() {
        return this.webSocketOpenTimeoutMillis;
    }

    public int getMaxConcurrentCalls() {
        return this.maxConcurrentCalls;
    }

    public int getMaxConcurrentCallsPerHost() {
        return this.maxConcurrentCallsPerHost;
    }

    public WebSocketVersion getWebSocketVersion() {
        return this.webSocketVersion;
    }
}