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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.*;
/**
 * Utility methods for applying CORS headers to responses based on {@link CorsConfig}.
 * <h3>Typical Usage</h3>
 * <pre>
 *   if (CorsUtil.isPreflightRequest(request, requestHeaders)) {
 *       HttpResponse response = new DefaultHttpResponse(request.protocolVersion(), HttpStatus.NO_CONTENT);
 *       LastHttpHeaders responseHeaders = new DefaultLastHttpHeaders();
 *       CorsUtil.applyPreflightCorsHeaders(request, requestHeaders, responseHeaders, config);
 *       channel.sendData(response);
 *       channel.sendData(responseHeaders);
 *       channel.sendData(new DefaultLastHttpContent(ByteBuf.EMPTY));
 *       return;
 *   }
 *   CorsUtil.applySimpleCorsHeaders(requestHeaders, responseHeaders, config);
 *   channel.sendData(response);
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class CorsUtil {
    private CorsUtil() {
    }

    // -------------------------------------------------------------------------
    // Request inspection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the request is a CORS preflight request:
     * the request method is OPTIONS <em>and</em> the request contains an {@code Access-Control-Request-Method} header.
     */
    public static boolean isPreflightRequest(HttpRequest request) {
        return isPreflightRequest(request, asHeaders(request));
    }

    /**
     * Returns {@code true} if the given request line and request-header block together form a CORS preflight request.
     */
    public static boolean isPreflightRequest(HttpRequest request, HttpHeaders headers) {
        if (request == null) {
            return false;
        }
        return isPreflightRequest(request.method(), headers);
    }

    /**
     * Returns {@code true} if the given method and request-header block together form a CORS preflight request.
     */
    public static boolean isPreflightRequest(HttpMethod method, HttpHeaders headers) {
        if (method == null) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(HttpMethod.OPTIONS.name(), method.name())) {
            return false;
        }
        String acrm = headers != null ? headers.getString(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD) : null;
        return StringUtils.isNotBlank(acrm);
    }

    /**
     * Returns the value of the {@code Origin} header, or {@code null} if it is absent.
     */
    public static String getOrigin(HttpRequest request) {
        return getOrigin(asHeaders(request));
    }

    /**
     * Returns the {@code Origin} value from the given request-header block, or {@code null} if it is absent.
     */
    public static String getOrigin(HttpHeaders headers) {
        return headers != null ? headers.getString(HttpHeaderNames.ORIGIN) : null;
    }

    // -------------------------------------------------------------------------
    // Response-header helpers
    // -------------------------------------------------------------------------

    /**
     * Applies simple-request (non-preflight) CORS response headers to {@code response}.
     * <p>
     * Response headers are written only when the request {@code Origin} is allowed by {@code config}
     * and {@code response} exposes a writable header collection.
     * @param request the HTTP request that triggered the response
     * @param response the HTTP response that should receive the extra headers
     * @param config the currently effective CORS configuration
     */
    public static void applySimpleCorsHeaders(HttpRequest request, HttpResponse response, CorsConfig config) {
        applySimpleCorsHeaders(asHeaders(request), asHeaders(response), config);
    }

    /**
     * Applies CORS response headers to the response-header block of a regular request.
     * <p>
     * This overload targets the staged HTTP object model, where the caller passes the request-header
     * block and the response-header block explicitly.
     */
    public static void applySimpleCorsHeaders(HttpHeaders requestHeaders, HttpHeaders responseHeaders, CorsConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        String origin = getOrigin(requestHeaders);
        if (!config.isOriginAllowed(origin)) {
            return;
        }

        if (responseHeaders == null) {
            return;
        }
        setAllowOrigin(responseHeaders, config, origin);
        setAllowCredentials(responseHeaders, config);
        setExposeHeaders(responseHeaders, config);
    }

    /**
     * Applies preflight-request (OPTIONS) CORS response headers to {@code response}.
     * <p>
     * This method writes {@code Access-Control-Allow-Origin}, {@code Access-Control-Allow-Methods},
     * and {@code Access-Control-Allow-Headers}, and supplements them with
     * {@code Access-Control-Max-Age} and {@code Access-Control-Allow-Credentials} when configured.
     * When the configuration does not declare an allowed-header list, the current implementation
     * echoes the request {@code Access-Control-Request-Headers} value.
     * @param request the preflight OPTIONS request
     * @param response the response object whose headers should be set
     * @param config the currently effective CORS configuration
     */
    public static void applyPreflightCorsHeaders(HttpRequest request, HttpResponse response, CorsConfig config) {
        applyPreflightCorsHeaders(request, asHeaders(request), asHeaders(response), config);
    }

    /**
     * Applies CORS response headers to the response-header block of a preflight request.
     * <p>
     * This overload targets the staged HTTP object model, where the caller passes the request line,
     * request-header block, and response-header block explicitly.
     */
    public static void applyPreflightCorsHeaders(HttpRequest request, HttpHeaders requestHeaders, HttpHeaders responseHeaders, CorsConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        String origin = getOrigin(requestHeaders);
        if (!config.isOriginAllowed(origin)) {
            return;
        }

        if (responseHeaders == null) {
            return;
        }

        setAllowOrigin(responseHeaders, config, origin);
        setAllowCredentials(responseHeaders, config);
        setAllowMethods(responseHeaders, config);
        setAllowHeaders(responseHeaders, config, requestHeaders);
        setMaxAge(responseHeaders, config);
    }

    // -------------------------------------------------------------------------
    // Private response-header writers
    // -------------------------------------------------------------------------

    private static void setAllowOrigin(HttpHeaders headers, CorsConfig config, String requestOrigin) {
        if (config.isAnyOrigin()) {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, requestOrigin);
            // When echoing a concrete origin, the response must declare Vary: Origin.
            String vary = headers.getString(HttpHeaderNames.VARY);
            if (StringUtils.isBlank(vary)) {
                headers.setHeader(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
            } else {
                headers.setHeader(HttpHeaderNames.VARY, vary + ", " + HttpHeaderNames.ORIGIN);
            }
        }
    }

    private static void setAllowCredentials(HttpHeaders headers, CorsConfig config) {
        if (config.isAllowCredentials()) {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    private static void setExposeHeaders(HttpHeaders headers, CorsConfig config) {
        Set<String> exposed = config.exposedHeaders();
        if (!exposed.isEmpty()) {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", exposed));
        }
    }

    private static void setAllowMethods(HttpHeaders headers, CorsConfig config) {
        Set<String> methods = config.allowedMethods();
        if (!methods.isEmpty()) {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", methods));
        }
    }

    private static void setAllowHeaders(HttpHeaders headers, CorsConfig config, HttpHeaders requestHeaders) {
        Set<String> configHeaders = config.allowedHeaders();
        if (!configHeaders.isEmpty()) {
            // Use the allow-headers list declared by the configuration.
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", configHeaders));
        } else {
            // If allow-headers was not configured explicitly, echo the headers declared by the request.
            String requested = requestHeaders != null ? requestHeaders.getString(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS) : null;
            if (StringUtils.isNotBlank(requested)) {
                headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, requested);
            }
        }
    }

    private static void setMaxAge(HttpHeaders headers, CorsConfig config) {
        if (config.maxAge() >= 0) {
            headers.setHeader(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, String.valueOf(config.maxAge()));
        }
    }

    private static HttpHeaders asHeaders(HttpObject httpObject) {
        return httpObject instanceof HttpHeaders ? (HttpHeaders) httpObject : null;
    }
}