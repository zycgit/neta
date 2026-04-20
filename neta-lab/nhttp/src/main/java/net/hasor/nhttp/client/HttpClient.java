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

import java.io.Closeable;
import java.io.IOException;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.codec.http.websocket.WebSocketVersion;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.nhttp.client.internal.ClientRuntime;
import net.hasor.nhttp.client.internal.RealCall;
import net.hasor.nhttp.request.Request;

/**
 * Main entry point for the nhttp client API.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpClient implements Closeable {
    private final HttpClientConfig config;
    private final ClientRuntime    runtime;

    private HttpClient(Builder builder) {
        this.config = new HttpClientConfig(builder);
        this.runtime = new ClientRuntime(this.config);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public HttpClientConfig config() {
        return this.config;
    }

    public Call newCall(Request request) {
        return new RealCall(this.runtime, this.config, request);
    }

    public Future<WebSocket> openWebSocket(Request request, WebSocketListener listener) {
        return this.runtime.openWebSocket(request, listener);
    }

    @Override
    public void close() throws IOException {
        this.runtime.close();
    }

    /**
     * Builder for {@link HttpClient}.
     */
    public static class Builder {
        HttpVersionPolicy versionPolicy              = HttpVersionPolicy.AUTO;
        SslConfig         sslConfig;
        int               maxContentLength           = 1024 * 1024;
        long              connectTimeoutMillis       = 10_000L;
        long              readTimeoutMillis          = 30_000L;
        long              writeTimeoutMillis         = 30_000L;
        long              callTimeoutMillis          = 30_000L;
        long              webSocketOpenTimeoutMillis = 30_000L;
        int               maxConcurrentCalls         = 64;
        int               maxConcurrentCallsPerHost  = 8;
        WebSocketVersion  webSocketVersion           = WebSocketVersion.V13;

        public Builder versionPolicy(HttpVersionPolicy versionPolicy) {
            this.versionPolicy = versionPolicy == null ? HttpVersionPolicy.AUTO : versionPolicy;
            return this;
        }

        public Builder ssl(SslConfig sslConfig) {
            this.sslConfig = sslConfig;
            return this;
        }

        public Builder maxContentLength(int maxContentLength) {
            this.maxContentLength = positive(maxContentLength, "maxContentLength");
            return this;
        }

        public Builder connectTimeout(long millis) {
            this.connectTimeoutMillis = positive(millis, "connectTimeoutMillis");
            return this;
        }

        public Builder readTimeout(long millis) {
            this.readTimeoutMillis = positive(millis, "readTimeoutMillis");
            return this;
        }

        public Builder writeTimeout(long millis) {
            this.writeTimeoutMillis = positive(millis, "writeTimeoutMillis");
            return this;
        }

        public Builder callTimeout(long millis) {
            this.callTimeoutMillis = positive(millis, "callTimeoutMillis");
            return this;
        }

        public Builder webSocketOpenTimeout(long millis) {
            this.webSocketOpenTimeoutMillis = positive(millis, "webSocketOpenTimeoutMillis");
            return this;
        }

        public Builder maxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = positive(maxConcurrentCalls, "maxConcurrentCalls");
            return this;
        }

        public Builder maxConcurrentCallsPerHost(int maxConcurrentCallsPerHost) {
            this.maxConcurrentCallsPerHost = positive(maxConcurrentCallsPerHost, "maxConcurrentCallsPerHost");
            return this;
        }

        public Builder webSocketVersion(WebSocketVersion webSocketVersion) {
            this.webSocketVersion = webSocketVersion == null ? WebSocketVersion.V13 : webSocketVersion;
            return this;
        }

        public HttpClient build() {
            return new HttpClient(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
            }
            return value;
        }

        private static long positive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
            }
            return value;
        }
    }
}