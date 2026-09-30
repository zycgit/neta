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

public class HttpHeaderAccessBenchmarkTest {
    @Test
    public void bothEnginesObserveTheSameRotatingHeaders() throws Throwable {
        for (int count : new int[] { 4, 12, 32, 64 }) {
            for (String access : new String[] { "decode", "lookup", "first", "repeat" }) {
                HttpHeaderAccessBenchmark benchmark = new HttpHeaderAccessBenchmark();
                benchmark.headerCount = count;
                benchmark.access = access;
                benchmark.setupTrial();
                benchmark.captureBaseline();
                try {
                    for (int rotation = 0; rotation < 2; rotation++) {
                        for (int i = 0; i < 16; i++) {
                            assertEquals(count + ":" + access, benchmark.expectedNext(), benchmark.neta_decodeAndAccess());
                        }
                        for (int i = 0; i < 16; i++) {
                            assertEquals(count + ":" + access, benchmark.expectedNext(), benchmark.netty_decodeAndAccess());
                        }
                    }
                } finally {
                    benchmark.tearDownTrial();
                    benchmark.assertNoLeak();
                }
            }
        }
    }
}
