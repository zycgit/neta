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
package net.hasor.nhttp.server;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.nhttp.server.connector.BackpressureStrategy;

/**
 * Immutable server configuration. Use {@link Builder} to construct an instance.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class ServerConfig {
    // Basic
    private final String serverName;
    private final String contextPath;
    // Protocol limits
    private final int maxContentLength;
    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxChunkSize;
    private final int bodyQueueCapacity;
    // Concurrency and timeouts
    private final int  maxConcurrentRequests;
    private final long requestTimeoutMillis;
    private final int  maxConnections;
    private final long connectionIdleTimeoutMillis;
    private final long gracefulShutdownMillis;
    // Thread pool
    private final ExecutorService executor;
    // Back-pressure
    private final BackpressureStrategy backpressureStrategy;
    // Protocol switches
    private final boolean http2Enabled;
    // SSL / CORS
    private final SslConfig  sslConfig;
    private final CorsConfig corsConfig;
    // Extension
    private final ErrorHandler errorHandler;

    private ServerConfig(Builder b) {
        this.serverName = b.serverName;
        this.contextPath = b.contextPath;
        this.maxContentLength = b.maxContentLength;
        this.maxInitialLineLength = b.maxInitialLineLength;
        this.maxHeaderSize = b.maxHeaderSize;
        this.maxChunkSize = b.maxChunkSize;
        this.bodyQueueCapacity = b.bodyQueueCapacity;
        this.maxConcurrentRequests = b.maxConcurrentRequests;
        this.requestTimeoutMillis = b.requestTimeoutMillis;
        this.maxConnections = b.maxConnections;
        this.connectionIdleTimeoutMillis = b.connectionIdleTimeoutMillis;
        this.gracefulShutdownMillis = b.gracefulShutdownMillis;
        this.executor = b.executor;
        this.backpressureStrategy = b.backpressureStrategy;
        this.http2Enabled = b.http2Enabled;
        this.sslConfig = b.sslConfig;
        this.corsConfig = b.corsConfig;
        this.errorHandler = b.errorHandler;
    }

    /** Returns a new {@link Builder} pre-populated with default values. */
    public static Builder builder() {
        return new Builder();
    }

    public String getServerName() {
        return serverName;
    }

    public String getContextPath() {
        return contextPath;
    }

    /** Maximum allowed request body size in bytes. {@code <= 0} means unlimited. */
    public int getMaxContentLength() {
        return maxContentLength;
    }

    public int getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    public int getMaxHeaderSize() {
        return maxHeaderSize;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    /** Capacity of the per-request {@code BodyChannel} queue (number of {@code HttpContent} objects). */
    public int getBodyQueueCapacity() {
        return bodyQueueCapacity;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /** Maximum number of simultaneous TCP connections. 0 = unlimited. */
    public int getMaxConnections() {
        return maxConnections;
    }

    public long getConnectionIdleTimeoutMillis() {
        return connectionIdleTimeoutMillis;
    }

    public long getGracefulShutdownMillis() {
        return gracefulShutdownMillis;
    }

    /** Custom worker {@link ExecutorService}. {@code null} means use the internal default. */
    public ExecutorService getExecutor() {
        return executor;
    }

    public BackpressureStrategy getBackpressureStrategy() {
        return backpressureStrategy;
    }

    public boolean isHttp2Enabled() {
        return http2Enabled;
    }

    public SslConfig getSslConfig() {
        return sslConfig;
    }

    public CorsConfig getCorsConfig() {
        return corsConfig;
    }

    /** Custom error handler. {@code null} means use {@code DefaultErrorHandler}. */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    // -------------------------------------------------------------------------

    /**
     * Builder for {@link ServerConfig}.
     */
    public static final class Builder {
        private String               serverName                  = "Neta-HTTP";
        private String               contextPath                 = "";
        private int                  maxContentLength            = 0;         // 0 = unlimited
        private int                  maxInitialLineLength        = 4096;
        private int                  maxHeaderSize               = 8192;
        private int                  maxChunkSize                = 8192;
        private int                  bodyQueueCapacity           = 16;
        private int                  maxConcurrentRequests       = 200;
        private long                 requestTimeoutMillis        = 30_000L;
        private int                  maxConnections              = 10_000;
        private long                 connectionIdleTimeoutMillis = 60_000L;
        private long                 gracefulShutdownMillis      = 30_000L;
        private ExecutorService      executor                    = null;
        private BackpressureStrategy backpressureStrategy        = BackpressureStrategy.limitedWait(200L);
        private boolean              http2Enabled                = true;
        private SslConfig            sslConfig                   = null;
        private CorsConfig           corsConfig                  = null;
        private ErrorHandler         errorHandler                = null;

        private Builder() {
        }

        public Builder serverName(String serverName) {
            this.serverName = Objects.requireNonNull(serverName);
            return this;
        }

        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath == null ? "" : contextPath;
            return this;
        }

        /** Sets the maximum request body size in bytes. {@code <= 0} disables transport-level size enforcement. */
        public Builder maxContentLength(int bytes) {
            this.maxContentLength = bytes;
            return this;
        }

        public Builder maxInitialLineLength(int len) {
            this.maxInitialLineLength = len;
            return this;
        }

        public Builder maxHeaderSize(int size) {
            this.maxHeaderSize = size;
            return this;
        }

        public Builder maxChunkSize(int size) {
            this.maxChunkSize = size;
            return this;
        }

        public Builder bodyQueueCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be > 0");
            }
            this.bodyQueueCapacity = capacity;
            return this;
        }

        public Builder maxConcurrentRequests(int max) {
            this.maxConcurrentRequests = max;
            return this;
        }

        public Builder requestTimeout(long millis) {
            this.requestTimeoutMillis = millis;
            return this;
        }

        public Builder maxConnections(int max) {
            this.maxConnections = max;
            return this;
        }

        public Builder connectionIdleTimeout(long millis) {
            this.connectionIdleTimeoutMillis = millis;
            return this;
        }

        public Builder gracefulShutdown(long millis) {
            this.gracefulShutdownMillis = millis;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder backpressureStrategy(BackpressureStrategy strategy) {
            this.backpressureStrategy = Objects.requireNonNull(strategy);
            return this;
        }

        public Builder http2(boolean enabled) {
            this.http2Enabled = enabled;
            return this;
        }

        public Builder ssl(SslConfig ssl) {
            this.sslConfig = ssl;
            return this;
        }

        public Builder cors(CorsConfig cors) {
            this.corsConfig = cors;
            return this;
        }

        public Builder errorHandler(ErrorHandler handler) {
            this.errorHandler = handler;
            return this;
        }

        /** Builds the immutable {@link ServerConfig}. */
        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
