/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HttpCodecBenchmarkTest {
    @Test
    public void readingFourHeadersObservesTheSameValuesAndReleasesBothPipelines() throws Throwable {
        int expected = "www.example.com".hashCode() + "text/html,application/xhtml+xml".hashCode() + "en-US,en;q=0.9".hashCode() + "keep-alive".hashCode();
        HttpCodecBenchmark benchmark = new HttpCodecBenchmark();
        benchmark.setupTrial();
        try {
            benchmark.captureBaseline();
            for (int i = 0; i < 64; i++) {
                assertEquals(expected, benchmark.neta_decodeSimpleRequestReadHeaders());
                assertEquals(expected, benchmark.netty_decodeSimpleRequestReadHeaders());
            }
            benchmark.assertNoLeak();
        } finally {
            benchmark.tearDownTrial();
        }
    }
}
