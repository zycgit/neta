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
import java.util.Set;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.neta.codec.http.HttpResponse;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpMethod;

/**
 * Utility methods for applying CORS response headers based on a {@link CorsConfig}.
 * <h3>Typical usage</h3>
 * <pre>
 *   // In your subscribe callback:
 *   String origin = request.headers().get(HttpHeaderNames.ORIGIN);
 *   if (CorsUtil.isPreflightRequest(request)) {
 *       FullHttpResponse preflight = CorsUtil.buildPreflightResponse(request, config);
 *       channel.sendData(preflight);
 *       return;
 *   }
 *   // ... build actual response ...
 *   CorsUtil.applySimpleCorsHeaders(request, response, config);
 *   channel.sendData(response);
 * </pre>
 */
public final class CorsUtil {
    private CorsUtil() {
    }

    // -------------------------------------------------------------------------
    // Request inspection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the request is a CORS preflight:
     * method is OPTIONS <em>and</em> the {@code Access-Control-Request-Method} header is present.
     */
    public static boolean isPreflightRequest(HttpRequest request) {
        if (request == null) {
            return false;
        }
        if (!HttpMethod.OPTIONS.name().equalsIgnoreCase(request.method().name())) {
            return false;
        }
        String acrm = request.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
        return acrm != null && !acrm.isEmpty();
    }

    /**
     * Returns the value of the {@code Origin} header, or {@code null} if absent.
     */
    public static String getOrigin(HttpRequest request) {
        if (request == null) {
            return null;
        }
        return request.headers().get(HttpHeaderNames.ORIGIN);
    }

    // -------------------------------------------------------------------------
    // Response header helpers
    // -------------------------------------------------------------------------

    /**
     * Applies the simple (non-preflight) CORS response headers to {@code response}.
     * <p>
     * Only writes headers when the request {@code Origin} is allowed by {@code config}.
     * Does nothing if CORS is disabled or the origin is not allowed.
     * @param request the HTTP request that triggered this response
     * @param response the HTTP response to decorate
     * @param config the active CORS configuration
     */
    public static void applySimpleCorsHeaders(HttpRequest request, HttpResponse response, CorsConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        String origin = getOrigin(request);
        if (!config.isOriginAllowed(origin)) {
            return;
        }

        HttpHeaders headers = response.headers();
        setAllowOrigin(headers, config, origin);
        setAllowCredentials(headers, config);
        setExposeHeaders(headers, config);
    }

    /**
     * Applies preflight (OPTIONS) CORS response headers to {@code response}.
     * <p>
     * This writes {@code Access-Control-Allow-Origin}, {@code Access-Control-Allow-Methods},
     * {@code Access-Control-Allow-Headers}, and optionally {@code Access-Control-Max-Age}
     * and {@code Access-Control-Allow-Credentials}.
     * @param request the preflight OPTIONS request
     * @param response the response to set headers on
     * @param config the active CORS configuration
     */
    public static void applyPreflightCorsHeaders(HttpRequest request, HttpResponse response, CorsConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        String origin = getOrigin(request);
        if (!config.isOriginAllowed(origin)) {
            return;
        }

        HttpHeaders headers = response.headers();
        setAllowOrigin(headers, config, origin);
        setAllowCredentials(headers, config);
        setAllowMethods(headers, config);
        setAllowHeaders(headers, config, request);
        setMaxAge(headers, config);
    }

    // -------------------------------------------------------------------------
    // Private header writers
    // -------------------------------------------------------------------------

    private static void setAllowOrigin(HttpHeaders headers, CorsConfig config, String requestOrigin) {
        if (config.isAnyOrigin()) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, requestOrigin);
            // When a specific origin is echoed back, the response must vary by Origin
            String vary = headers.get(HttpHeaderNames.VARY);
            if (vary == null || vary.isEmpty()) {
                headers.set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
            } else {
                headers.set(HttpHeaderNames.VARY, vary + ", " + HttpHeaderNames.ORIGIN);
            }
        }
    }

    private static void setAllowCredentials(HttpHeaders headers, CorsConfig config) {
        if (config.isAllowCredentials()) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    private static void setExposeHeaders(HttpHeaders headers, CorsConfig config) {
        Set<String> exposed = config.exposedHeaders();
        if (!exposed.isEmpty()) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", exposed));
        }
    }

    private static void setAllowMethods(HttpHeaders headers, CorsConfig config) {
        Set<String> methods = config.allowedMethods();
        if (!methods.isEmpty()) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", methods));
        }
    }

    private static void setAllowHeaders(HttpHeaders headers, CorsConfig config, HttpRequest request) {
        Set<String> configHeaders = config.allowedHeaders();
        if (!configHeaders.isEmpty()) {
            // Use the configured allow-headers list
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", configHeaders));
        } else {
            // Echo back the requested headers if no explicit allow-headers configured
            String requested = request.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
            if (requested != null && !requested.isEmpty()) {
                headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, requested);
            }
        }
    }

    private static void setMaxAge(HttpHeaders headers, CorsConfig config) {
        if (config.maxAge() >= 0) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, String.valueOf(config.maxAge()));
        }
    }
}