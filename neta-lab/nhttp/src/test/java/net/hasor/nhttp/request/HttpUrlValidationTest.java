/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import net.hasor.neta.codec.http.HttpScheme;

public class HttpUrlValidationTest {
    @Test
    public void testBuildWithoutSchemeRejected() throws Exception {
        expectThrows(IllegalStateException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().host("example.com").build();
            }
        });
    }

    @Test
    public void testBuildWithoutHostRejected() throws Exception {
        expectThrows(IllegalStateException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().scheme(HttpScheme.HTTP).build();
            }
        });
    }

    @Test
    public void testBlankSchemeRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().scheme(" ");
            }
        });
    }

    @Test
    public void testBlankHostRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().host(" ");
            }
        });
    }

    @Test
    public void testInvalidPortRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().port(65536);
            }
        });
    }

    @Test
    public void testNullTypedSchemeRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().scheme((HttpScheme) null);
            }
        });
    }

    @Test
    public void testNullPathSegmentRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().addPathSegment(null);
            }
        });
    }

    @Test
    public void testNullEncodedPathSegmentRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().addEncodedPathSegment(null);
            }
        });
    }

    @Test
    public void testBlankQueryNameRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().addQueryParameter(" ", "value");
            }
        });
    }

    @Test
    public void testBlankEncodedQueryNameRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                HttpUrl.builder().addEncodedQueryParameter(" ", "value");
            }
        });
    }

    @Test
    public void testIpv6HostIsNormalizedWithBrackets() throws Exception {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder()
                .scheme(HttpScheme.HTTP)
                .host("2001:db8::1")
                .addPathSegment("api")
                .build();
        // @formatter:on

        assertEquals("http://[2001:db8::1]/api", httpUrl.toString());
        assertEquals("[2001:db8::1]", httpUrl.toUri().getHost());
    }

    @Test
    public void testEncodedSegmentsAndNullQueryValueArePreserved() throws Exception {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder()
                .scheme(HttpScheme.HTTP)
                .host("example.com")
                .addEncodedPathSegment("/%E4%BD%A0%E5%A5%BD/")
                .addEncodedQueryParameter("raw", "A%2BB")
                .addQueryParameter("empty", null)
                .build();
        // @formatter:on

        assertEquals("http://example.com/%E4%BD%A0%E5%A5%BD?raw=A%2BB&empty=", httpUrl.toString());
    }

    @Test
    public void testMutatingEncodedExistingUriKeepsRawEncoding() throws Exception {
        // @formatter:off
        HttpUrl httpUrl = HttpUrl.builder("https://example.com/a%2Fb?raw=A%2BB")
                .addEncodedPathSegment("tail%2Fend")
                .addEncodedQueryParameter("keep", "%E4%BD%A0%E5%A5%BD")
                .build();
        // @formatter:on

        assertEquals("https://example.com/a%2Fb/tail%2Fend?raw=A%2BB&keep=%E4%BD%A0%E5%A5%BD", httpUrl.toString());
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("expected exception: " + type.getName());
        } catch (Throwable e) {
            if (!type.isInstance(e)) {
                throw new AssertionError("expected exception: " + type.getName() + ", but was: " + e.getClass().getName(), e);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
