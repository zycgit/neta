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
import net.hasor.cobble.StringUtils;

/**
 * Immutable configuration object for the CORS handler.
 * <p>Instances can be created through {@link CorsConfig.Builder}:
 * <pre>
 *   // Use common defaults and allow any origin (wildcard)
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
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
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

    /** Returns whether CORS handling is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Returns whether the wildcard origin {@code "*"} is enabled. */
    public boolean isAnyOrigin() {
        return anyOrigin;
    }

    /**
     * Returns the explicitly allowed origin set.
     * This set is empty when {@link #isAnyOrigin()} is {@code true}.
     */
    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Checks whether the given origin is allowed by the current configuration.
     * When {@link #isAnyOrigin()} is enabled, this returns {@code true} as long as the configuration itself is enabled.
     * @param origin the value of the request {@code Origin} header, which may be null
     * @return {@code true} if the origin is allowed
     */
    public boolean isOriginAllowed(String origin) {
        if (!enabled) {
            return false;
        }
        if (anyOrigin) {
            return true;
        }
        if (StringUtils.isBlank(origin)) {
            return false;
        }
        return allowedOrigins.contains(origin);
    }

    /** Returns the allowed HTTP method set, with all elements normalized to uppercase. */
    public Set<String> allowedMethods() {
        return allowedMethods;
    }

    /** Returns the allowed request-header set, with all elements normalized to lowercase for comparison. */
    public Set<String> allowedHeaders() {
        return allowedHeaders;
    }

    /** Returns the response-header set exposed to the browser. */
    public Set<String> exposedHeaders() {
        return exposedHeaders;
    }

    /** Returns whether cookies or other credentials are allowed on cross-origin requests. */
    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    /**
     * Returns the preflight cache duration in seconds.
     * If not set, {@code -1} is returned, meaning no {@code Access-Control-Max-Age} header will be emitted.
     */
    public long maxAge() {
        return maxAge;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Returns the string representation of the current configuration object.
     */
    @Override
    public String toString() {
        return "CorsConfig{enabled=" + enabled + ", anyOrigin=" + anyOrigin + ", allowedOrigins=" + allowedOrigins + ", allowedMethods=" + allowedMethods + ", allowedHeaders=" + allowedHeaders + ", allowCredentials=" + allowCredentials + ", maxAge=" + maxAge + '}';
    }

    /** Chainable builder for {@link CorsConfig}. */
    public static final class Builder {
        private final Set<String> allowedOrigins   = new LinkedHashSet<>();
        private final Set<String> allowedMethods   = new LinkedHashSet<>(Arrays.asList("GET", "HEAD", "POST"));
        private final Set<String> allowedHeaders   = new LinkedHashSet<>();
        private final Set<String> exposedHeaders   = new LinkedHashSet<>();
        private boolean           anyOrigin        = false;
        private boolean           allowCredentials = false;
        private long              maxAge           = -1;
        private boolean           enabled          = true;

        private Builder() {
        }

        /**
         * Allows all origins.
         * The built response headers will emit {@code Access-Control-Allow-Origin: *}.
         */
        public Builder allowAnyOrigin() {
            this.anyOrigin = true;
            return this;
        }

        /**
         * Allows specific origins.
         * @param origins one or more origin strings, such as {@code "https://example.com"}
         */
        public Builder allowOrigins(String... origins) {
            this.anyOrigin = false;
            Collections.addAll(this.allowedOrigins, origins);
            return this;
        }

        /**
         * Sets the allowed HTTP methods and overrides the defaults.
         * Method names are automatically converted to uppercase.
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
         * Header names are automatically converted to lowercase for case-insensitive matching.
         */
        public Builder allowHeaders(String... headers) {
            for (String h : headers) {
                this.allowedHeaders.add(h.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Sets the response headers that browsers are allowed to read.
         */
        public Builder exposeHeaders(String... headers) {
            Collections.addAll(this.exposedHeaders, headers);
            return this;
        }

        /**
         * Enables or disables the {@code Access-Control-Allow-Credentials: true} header.
         * When set to {@code true}, it cannot be combined with {@link #allowAnyOrigin()}.
         */
        public Builder allowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
            return this;
        }

        /**
         * Sets the value of {@code Access-Control-Max-Age} in seconds.
         * Using {@code -1} (the default) means the header will not be emitted.
         */
        public Builder maxAge(long seconds) {
            this.maxAge = seconds;
            return this;
        }

        /** Disables CORS handling completely, causing requests to pass through unchanged and utility methods to skip all CORS headers. */
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