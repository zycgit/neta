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
package net.hasor.neta.codec.http.cors;
import java.util.*;

/**
 * Immutable configuration for the CORS handler.
 * <p>Use {@link CorsConfig.Builder} to create instances:
 * <pre>
 *   // Allow any origin (wildcard) with common defaults
 *   CorsConfig cors = CorsConfig.builder().allowAnyOrigin().build();
 *   // Fine-grained configuration
 *   CorsConfig cors = CorsConfig.builder()
 *       .allowOrigins("https://example.com", "https://app.example.com")
 *       .allowMethods("GET", "POST", "PUT", "DELETE")
 *       .allowHeaders("Content-Type", "Authorization", "X-Requested-With")
 *       .exposeHeaders("X-Custom-Header")
 *       .allowCredentials(true)
 *       .maxAge(3600)
 *       .build();
 * </pre>
 */
public final class CorsConfig {

    private final Set<String> allowedOrigins;  // null means wildcard (*)
    private final boolean     anyOrigin;
    private final Set<String> allowedMethods;
    private final Set<String> allowedHeaders;
    private final Set<String> exposedHeaders;
    private final boolean     allowCredentials;
    private final long        maxAge;           // -1 means not set
    private final boolean     enabled;

    private CorsConfig(Builder b) {
        this.anyOrigin = b.anyOrigin;
        this.allowedOrigins = b.anyOrigin ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(b.allowedOrigins));
        this.allowedMethods = Collections.unmodifiableSet(new LinkedHashSet<>(b.allowedMethods));
        this.allowedHeaders = Collections.unmodifiableSet(new LinkedHashSet<>(b.allowedHeaders));
        this.exposedHeaders = Collections.unmodifiableSet(new LinkedHashSet<>(b.exposedHeaders));
        this.allowCredentials = b.allowCredentials;
        this.maxAge = b.maxAge;
        this.enabled = b.enabled;
    }

    /** Returns a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Whether CORS processing is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether the wildcard origin {@code "*"} is set (all origins allowed). */
    public boolean isAnyOrigin() {
        return anyOrigin;
    }

    /**
     * Returns the set of explicitly allowed origins.
     * Empty when {@link #isAnyOrigin()} is {@code true}.
     */
    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Checks whether the given origin is allowed by this configuration.
     * @param origin the value of the {@code Origin} request header (may be null)
     * @return {@code true} if the origin is allowed
     */
    public boolean isOriginAllowed(String origin) {
        if (!enabled) {
            return false;
        }
        if (anyOrigin) {
            return true;
        }
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        return allowedOrigins.contains(origin);
    }

    /** Returns the set of allowed HTTP methods (upper-cased). */
    public Set<String> allowedMethods() {
        return allowedMethods;
    }

    /** Returns the set of allowed request headers (lower-cased for comparison). */
    public Set<String> allowedHeaders() {
        return allowedHeaders;
    }

    /** Returns the set of response headers exposed to the browser. */
    public Set<String> exposedHeaders() {
        return exposedHeaders;
    }

    /** Whether cookies / credentials may be included in cross-origin requests. */
    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    /**
     * Returns the preflight cache duration in seconds, or {@code -1} if not set
     * (i.e. the {@code Access-Control-Max-Age} header will be omitted).
     */
    public long maxAge() {
        return maxAge;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "CorsConfig{enabled=" + enabled + ", anyOrigin=" + anyOrigin + ", allowedOrigins=" + allowedOrigins + ", allowedMethods=" + allowedMethods + ", allowedHeaders=" + allowedHeaders + ", allowCredentials=" + allowCredentials + ", maxAge=" + maxAge + '}';
    }

    /** Fluent builder for {@link CorsConfig}. */
    public static final class Builder {

        private boolean     anyOrigin        = false;
        private Set<String> allowedOrigins   = new LinkedHashSet<>();
        private Set<String> allowedMethods   = new LinkedHashSet<>(Arrays.asList("GET", "HEAD", "POST"));
        private Set<String> allowedHeaders   = new LinkedHashSet<>();
        private Set<String> exposedHeaders   = new LinkedHashSet<>();
        private boolean     allowCredentials = false;
        private long        maxAge           = -1;
        private boolean     enabled          = true;

        private Builder() {
        }

        /**
         * Allows all origins (sets {@code Access-Control-Allow-Origin: *}).
         * Cannot be combined with {@link #allowCredentials(boolean) allowCredentials(true)}.
         */
        public Builder allowAnyOrigin() {
            this.anyOrigin = true;
            return this;
        }

        /**
         * Allows the specified origins.
         * @param origins one or more origin strings, e.g. {@code "https://example.com"}
         */
        public Builder allowOrigins(String... origins) {
            this.anyOrigin = false;
            Collections.addAll(this.allowedOrigins, origins);
            return this;
        }

        /**
         * Sets the allowed HTTP methods (replaces defaults).
         * Values are upper-cased automatically.
         */
        public Builder allowMethods(String... methods) {
            this.allowedMethods.clear();
            for (String m : methods) {
                this.allowedMethods.add(m.toUpperCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Sets the allowed request headers.
         * Values are lower-cased automatically for case-insensitive matching.
         */
        public Builder allowHeaders(String... headers) {
            for (String h : headers) {
                this.allowedHeaders.add(h.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Sets the response headers that the browser is allowed to read.
         */
        public Builder exposeHeaders(String... headers) {
            Collections.addAll(this.exposedHeaders, headers);
            return this;
        }

        /**
         * Enables or disables the {@code Access-Control-Allow-Credentials: true} header.
         * When {@code true}, {@link #allowAnyOrigin()} must not be used.
         */
        public Builder allowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
            return this;
        }

        /**
         * Sets the value of {@code Access-Control-Max-Age} in seconds.
         * Use {@code -1} (the default) to omit the header.
         */
        public Builder maxAge(long seconds) {
            this.maxAge = seconds;
            return this;
        }

        /** Disables CORS processing entirely (the handler becomes a no-op). */
        public Builder disable() {
            this.enabled = false;
            return this;
        }

        /** Builds an immutable {@link CorsConfig} instance. */
        public CorsConfig build() {
            if (anyOrigin && allowCredentials) {
                throw new IllegalStateException("allowAnyOrigin() and allowCredentials(true) cannot be combined (RFC 6454)");
            }
            return new CorsConfig(this);
        }
    }
}
