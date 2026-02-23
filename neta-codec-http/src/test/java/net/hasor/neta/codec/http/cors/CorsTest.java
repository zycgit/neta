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
import java.util.Collections;
import java.util.Set;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the CORS package:
 * {@link CorsConfig}, {@link CorsUtil}, {@link CorsHandler}.
 */
public class CorsTest {

    // =========================================================================
    // CorsConfig — builder & accessors
    // =========================================================================

    private static DefaultFullHttpRequest buildGetRequest(String origin) {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/data");
        req.headers().set(HttpHeaderNames.ORIGIN, origin);
        return req;
    }

    private static DefaultFullHttpRequest buildOptionsRequest(String origin, String acrm, String acrh) {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api/data");
        if (origin != null) {
            req.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        if (acrm != null) {
            req.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, acrm);
        }
        if (acrh != null) {
            req.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, acrh);
        }
        return req;
    }

    @Test
    public void testCorsConfig_defaults() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        assertTrue(cfg.isEnabled());
        assertTrue(cfg.isAnyOrigin());
        assertTrue(cfg.allowedOrigins().isEmpty());
        assertFalse(cfg.isAllowCredentials());
        assertEquals(-1L, cfg.maxAge());
        assertTrue(cfg.exposedHeaders().isEmpty());
        // default methods
        assertTrue(cfg.allowedMethods().contains("GET"));
        assertTrue(cfg.allowedMethods().contains("POST"));
        assertTrue(cfg.allowedMethods().contains("HEAD"));
    }

    @Test
    public void testCorsConfig_specificOrigins() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://a.com", "https://b.com").build();
        assertFalse(cfg.isAnyOrigin());
        Set<String> origins = cfg.allowedOrigins();
        assertTrue(origins.contains("https://a.com"));
        assertTrue(origins.contains("https://b.com"));
        assertEquals(2, origins.size());
    }

    @Test
    public void testCorsConfig_allowedMethods() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().allowMethods("GET", "POST", "DELETE").build();
        assertTrue(cfg.allowedMethods().contains("GET"));
        assertTrue(cfg.allowedMethods().contains("POST"));
        assertTrue(cfg.allowedMethods().contains("DELETE"));
        assertFalse(cfg.allowedMethods().contains("HEAD")); // replaced
    }

    @Test
    public void testCorsConfig_allowedHeaders() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().allowHeaders("Content-Type", "Authorization").build();
        assertTrue(cfg.allowedHeaders().contains("content-type")); // lower-cased
        assertTrue(cfg.allowedHeaders().contains("authorization"));
    }

    @Test
    public void testCorsConfig_exposeHeaders() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().exposeHeaders("X-Custom-Header").build();
        assertTrue(cfg.exposedHeaders().contains("X-Custom-Header"));
    }

    @Test
    public void testCorsConfig_credentials() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").allowCredentials(true).build();
        assertTrue(cfg.isAllowCredentials());
    }

    @Test(expected = IllegalStateException.class)
    public void testCorsConfig_credentialsWithWildcardThrows() {
        CorsConfig.builder().allowAnyOrigin().allowCredentials(true).build();
    }

    @Test
    public void testCorsConfig_maxAge() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().maxAge(3600).build();
        assertEquals(3600L, cfg.maxAge());
    }

    // =========================================================================
    // CorsConfig — isOriginAllowed
    // =========================================================================

    @Test
    public void testCorsConfig_disabled() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().disable().build();
        assertFalse(cfg.isEnabled());
    }

    @Test
    public void testCorsConfig_toStringContainsEnabled() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        String s = cfg.toString();
        assertTrue(s.contains("enabled=true"));
        assertTrue(s.contains("anyOrigin=true"));
    }

    @Test
    public void testIsOriginAllowed_anyOrigin() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        assertTrue(cfg.isOriginAllowed("https://example.com"));
        assertTrue(cfg.isOriginAllowed("http://localhost:3000"));
        assertTrue(cfg.isOriginAllowed("null"));
    }

    @Test
    public void testIsOriginAllowed_specificOrigin_match() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").build();
        assertTrue(cfg.isOriginAllowed("https://example.com"));
    }

    @Test
    public void testIsOriginAllowed_specificOrigin_noMatch() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").build();
        assertFalse(cfg.isOriginAllowed("https://other.com"));
    }

    // =========================================================================
    // CorsUtil — isPreflightRequest
    // =========================================================================

    @Test
    public void testIsOriginAllowed_nullOrigin() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        // wildcard allows any, but null/empty check for specific origins
        CorsConfig specific = CorsConfig.builder().allowOrigins("https://example.com").build();
        assertFalse(specific.isOriginAllowed(null));
        assertFalse(specific.isOriginAllowed(""));
    }

    @Test
    public void testIsOriginAllowed_disabledConfig() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().disable().build();
        assertFalse(cfg.isOriginAllowed("https://example.com"));
    }

    @Test
    public void testIsPreflightRequest_true() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api");
        req.headers().set(HttpHeaderNames.ORIGIN, "https://example.com");
        req.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        assertTrue(CorsUtil.isPreflightRequest(req));
    }

    @Test
    public void testIsPreflightRequest_noAcrm() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api");
        req.headers().set(HttpHeaderNames.ORIGIN, "https://example.com");
        // No ACCESS_CONTROL_REQUEST_METHOD
        assertFalse(CorsUtil.isPreflightRequest(req));
    }

    @Test
    public void testIsPreflightRequest_notOptions() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api");
        req.headers().set(HttpHeaderNames.ORIGIN, "https://example.com");
        req.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        assertFalse(CorsUtil.isPreflightRequest(req));
    }

    @Test
    public void testIsPreflightRequest_null() {
        assertFalse(CorsUtil.isPreflightRequest(null));
    }

    @Test
    public void testGetOrigin() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api");
        req.headers().set(HttpHeaderNames.ORIGIN, "https://example.com");
        assertEquals("https://example.com", CorsUtil.getOrigin(req));
    }

    // =========================================================================
    // CorsUtil — applySimpleCorsHeaders (wildcard)
    // =========================================================================

    @Test
    public void testGetOrigin_null() {
        assertNull(CorsUtil.getOrigin(null));
    }

    @Test
    public void testGetOrigin_absent() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api");
        assertNull(CorsUtil.getOrigin(req));
    }

    @Test
    public void testApplySimpleCorsHeaders_wildcard() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        assertEquals("*", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testApplySimpleCorsHeaders_specificOrigin_echoed() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").build();
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        assertEquals("https://example.com", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        // Vary: Origin must be set when echoing a specific origin
        assertNotNull(resp.headers().get(HttpHeaderNames.VARY));
        assertTrue(resp.headers().get(HttpHeaderNames.VARY).contains("origin"));
    }

    @Test
    public void testApplySimpleCorsHeaders_disallowedOrigin() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://allowed.com").build();
        DefaultFullHttpRequest req = buildGetRequest("https://other.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        // No CORS headers written for disallowed origin
        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testApplySimpleCorsHeaders_credentials() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").allowCredentials(true).build();
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        assertEquals("true", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    public void testApplySimpleCorsHeaders_exposeHeaders() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().exposeHeaders("X-Custom", "X-Token").build();
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        String exposed = resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertNotNull(exposed);
        assertTrue(exposed.contains("X-Custom"));
        assertTrue(exposed.contains("X-Token"));
    }

    // =========================================================================
    // CorsUtil — applyPreflightCorsHeaders
    // =========================================================================

    @Test
    public void testApplySimpleCorsHeaders_nullConfig() {
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        // Must not throw
        CorsUtil.applySimpleCorsHeaders(req, resp, null);
        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testApplySimpleCorsHeaders_disabledConfig() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().disable().build();
        DefaultFullHttpRequest req = buildGetRequest("https://example.com");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);

        CorsUtil.applySimpleCorsHeaders(req, resp, cfg);

        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testApplyPreflightCorsHeaders_basic() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().allowMethods("GET", "POST").build();
        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "POST", null);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        assertEquals("*", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        String methods = resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS);
        assertNotNull(methods);
        assertTrue(methods.contains("GET"));
        assertTrue(methods.contains("POST"));
    }

    @Test
    public void testApplyPreflightCorsHeaders_maxAge() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().maxAge(1800).build();
        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "GET", null);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        assertEquals("1800", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
    }

    @Test
    public void testApplyPreflightCorsHeaders_noMaxAge() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build(); // maxAge = -1
        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "GET", null);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
    }

    @Test
    public void testApplyPreflightCorsHeaders_configuredAllowHeaders() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().allowHeaders("Content-Type", "Authorization").build();
        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "POST", "Content-Type, X-Custom");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        String allowedHeaders = resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS);
        assertNotNull(allowedHeaders);
        // Should use configured headers, not echo back
        assertTrue(allowedHeaders.contains("content-type"));
        assertTrue(allowedHeaders.contains("authorization"));
    }

    // =========================================================================
    // CorsHandler — pipeline handler
    // =========================================================================

    @Test
    public void testApplyPreflightCorsHeaders_echoRequestedHeaders() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build(); // no explicit allowHeaders
        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "POST", "X-Custom-Header, Content-Type");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        String allowedHeaders = resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS);
        assertNotNull(allowedHeaders);
        assertTrue(allowedHeaders.contains("X-Custom-Header"));
    }

    @Test
    public void testApplyPreflightCorsHeaders_disallowedOrigin() {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://allowed.com").build();
        DefaultFullHttpRequest req = buildOptionsRequest("https://other.com", "POST", null);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);

        CorsUtil.applyPreflightCorsHeaders(req, resp, cfg);

        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testCorsHandler_constructor_nullConfigThrows() {
        try {
            new CorsHandler(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testCorsHandler_config() {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        CorsHandler handler = new CorsHandler(cfg);
        assertSame(cfg, handler.config());
    }

    @Test
    public void testCorsHandler_preflight_emits204Response() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().allowMethods("GET", "POST").build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "POST", null);

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        assertEquals(1, snd.size());
        Object emitted = snd.poll();
        assertTrue("Expected FullHttpResponse for preflight", emitted instanceof FullHttpResponse);
        FullHttpResponse preflight = (FullHttpResponse) emitted;
        assertEquals(204, preflight.status().code());
        assertEquals("*", preflight.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        String methods = preflight.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS);
        assertNotNull(methods);
        assertTrue(methods.contains("GET"));
        assertTrue(methods.contains("POST"));
    }

    @Test
    public void testCorsHandler_normalRequest_passThrough() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = buildGetRequest("https://example.com");

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        assertEquals(1, snd.size());
        assertSame(req, snd.poll());
    }

    @Test
    public void testCorsHandler_noOrigin_passThrough() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api");
        // No Origin header

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        assertEquals(1, snd.size());
        assertSame(req, snd.poll());
    }

    @Test
    public void testCorsHandler_disabled_passThrough() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().disable().build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "POST", null);

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        // Disabled — passes through even OPTIONS preflight
        assertEquals(1, snd.size());
        assertSame(req, snd.poll());
    }

    @Test
    public void testCorsHandler_emptyQueue_returnsStop() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowAnyOrigin().build();
        CorsHandler handler = new CorsHandler(cfg);

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        net.hasor.neta.channel.ProtoStatus status = handler.onMessage(null, rcv, snd);

        assertEquals(net.hasor.neta.channel.ProtoStatus.Stop, status);
        assertEquals(0, snd.size());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Test
    public void testCorsHandler_preflight_withCredentials() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://example.com").allowCredentials(true).maxAge(600).build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = buildOptionsRequest("https://example.com", "DELETE", "Authorization");

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        FullHttpResponse resp = (FullHttpResponse) snd.poll();
        assertEquals(204, resp.status().code());
        assertEquals("https://example.com", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertEquals("600", resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
        // Vary must be set
        assertNotNull(resp.headers().get(HttpHeaderNames.VARY));
    }

    @Test
    public void testCorsHandler_preflight_originNotAllowed_emitsNoOriginHeader() throws Throwable {
        CorsConfig cfg = CorsConfig.builder().allowOrigins("https://allowed.com").build();
        CorsHandler handler = new CorsHandler(cfg);

        DefaultFullHttpRequest req = buildOptionsRequest("https://disallowed.com", "POST", null);

        SimpleProtoRcvQueue<FullHttpRequest> rcv = new SimpleProtoRcvQueue<>();
        rcv.add(req);
        SimpleProtoSndQueue<Object> snd = new SimpleProtoSndQueue<>();

        handler.onMessage(null, rcv, snd);

        // A 204 response is still emitted for the OPTIONS, but without CORS allow-origin header
        Object emitted = snd.poll();
        assertTrue(emitted instanceof FullHttpResponse);
        FullHttpResponse resp = (FullHttpResponse) emitted;
        assertNull(resp.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // =========================================================================
    // Minimal ProtoRcvQueue / ProtoSndQueue stubs
    // =========================================================================

    private static class SimpleProtoRcvQueue<T> implements net.hasor.neta.channel.ProtoRcvQueue<T> {
        private final java.util.List<T> list = new java.util.ArrayList<>();

        public void add(T item) {
            list.add(item);
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int queueSize() {
            return list.size();
        }

        @Override
        public net.hasor.neta.channel.ProtoRcvQueue<T> rcvSubmit() {
            return this;
        }

        @Override
        public net.hasor.neta.channel.ProtoRcvQueue<T> rcvReset() {
            return this;
        }

        @Override
        public java.util.List<T> takeMessage(int cnt) {
            if (list.isEmpty())
                return java.util.Collections.emptyList();
            int take = Math.min(cnt, list.size());
            java.util.List<T> result = new java.util.ArrayList<>(list.subList(0, take));
            list.subList(0, take).clear();
            return result;
        }

        @Override
        public java.util.List<T> peekMessage(int cnt) {
            if (list.isEmpty())
                return java.util.Collections.emptyList();
            int take = Math.min(cnt, list.size());
            return new java.util.ArrayList<>(list.subList(0, take));
        }

        @Override
        public void skipMessage(int cnt) {
            int skip = Math.min(cnt, list.size());
            list.subList(0, skip).clear();
        }
    }

    private static class SimpleProtoSndQueue<T> implements net.hasor.neta.channel.ProtoSndQueue<T> {
        private final java.util.List<T> list = new java.util.ArrayList<>();

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int slotSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean hasCommit() {
            return true;
        }

        @Override
        public net.hasor.neta.channel.ProtoSndQueue<T> sndSubmit() {
            return this;
        }

        @Override
        public net.hasor.neta.channel.ProtoSndQueue<T> sndReset() {
            return this;
        }

        @Override
        public int offerMessage(T[] offerList) {
            Collections.addAll(list, offerList);
            return offerList.length;
        }

        @Override
        public int offerMessage(java.util.List<T> offerList) {
            list.addAll(offerList);
            return offerList.size();
        }

        @Override
        public int offerMessage(net.hasor.neta.channel.ProtoRcvQueue<T> offerList) {
            int count = 0;
            while (offerList.hasMore()) {
                list.add(offerList.takeMessage());
                count++;
            }
            return count;
        }

        public int size() {
            return list.size();
        }

        public T poll() {
            return list.isEmpty() ? null : list.remove(0);
        }
    }
}
