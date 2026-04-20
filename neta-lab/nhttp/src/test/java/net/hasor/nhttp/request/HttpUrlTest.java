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
package net.hasor.nhttp.request;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.hasor.neta.codec.http.HttpScheme;

public class HttpUrlTest {
    @Test
    public void testBuildFullUrlFromIndependentParts() {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder()
                .scheme(HttpScheme.HTTPS)
                .userInfo("demo user:pwd")
                .host("example.com")
                .port(9443)
                .addPathSegment("api")
                .addPathSegment("v1")
                .addPathSegment("user list")
                .addQueryParameter("page", "1")
                .addQueryParameter("sort", "name asc")
                .fragment("section 2")
                .build();
        // @formatter:on

        assertEquals("https://demo+user%3Apwd@example.com:9443/api/v1/user+list?page=1&sort=name+asc#section+2", httpUrl.toString());
    }

    @Test
    public void testBuildWebSocketUrlWithTypedScheme() {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder()
                .scheme(HttpScheme.WSS)
                .host("example.com")
                .addPathSegment("socket")
                .addQueryParameter("room", "alpha")
                .build();
        // @formatter:on

        assertEquals("wss://example.com/socket?room=alpha", httpUrl.toString());
    }

    @Test
    public void testBuildPlainWebSocketUrlWithTypedScheme() {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder()
                .scheme(HttpScheme.WS)
                .host("example.com")
                .addPathSegment("chat")
                .addQueryParameter("client", "demo")
                .build();
        // @formatter:on

        assertEquals("ws://example.com/chat?client=demo", httpUrl.toString());
    }

    @Test
    public void testMutateExistingUrl() {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder("http://127.0.0.1/base/path?from=test")
                .addPathSegment("next")
                .addQueryParameter("page", "2")
                .fragment("tail")
                .build();
        // @formatter:on

        assertEquals("http://127.0.0.1/base/path/next?from=test&page=2#tail", httpUrl.toString());
    }

    @Test
    public void testReplacePathAndQuery() {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder("https://example.com/old?a=1")
                .path("/new/path")
                .clearQuery()
                .addQueryParameter("keyword", "hello world")
                .build();
        // @formatter:on

        assertEquals("https://example.com/new/path?keyword=hello+world", httpUrl.toString());
    }
}