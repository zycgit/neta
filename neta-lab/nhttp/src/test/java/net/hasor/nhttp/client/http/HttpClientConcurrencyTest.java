/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.hasor.nhttp.client.ClientTestSupport;
import net.hasor.nhttp.client.HttpClient;
import net.hasor.nhttp.request.Request;
import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

public class HttpClientConcurrencyTest extends ClientTestSupport {
    private NetaHttpServer server;
    private HttpClient     client;
    private int            port;

    @Before
    public void setUp() throws Exception {
        this.port = findFreePort();
        this.server = new NetaHttpServer();
    }

    @After
    public void tearDown() throws Exception {
        if (this.client != null) {
            this.client.close();
        }
        if (this.server != null) {
            this.server.stop();
        }
    }

    @Test
    public void testDispatcherPerHostLimit() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxSeen = new AtomicInteger(0);

        this.server.addServlet("/slow", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
                int running = active.incrementAndGet();
                while (true) {
                    int before = maxSeen.get();
                    if (running <= before || maxSeen.compareAndSet(before, running)) {
                        break;
                    }
                }
                try {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    resp.setContentType("text/plain");
                    resp.write("done");
                } finally {
                    active.decrementAndGet();
                }
            }
        });
        this.server.start(this.port);
        waitForServer();

        this.client = HttpClient.newBuilder().connectTimeout(5_000).readTimeout(5_000).writeTimeout(5_000).callTimeout(5_000).maxConcurrentCalls(4).maxConcurrentCallsPerHost(1).build();

        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            Request request = new Request.Builder().url("http://127.0.0.1:" + this.port + "/slow?id=" + i).get().build();
            this.client.newCall(request).executeAsync().onFinal(done -> latch.countDown());
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, maxSeen.get());
    }
}
