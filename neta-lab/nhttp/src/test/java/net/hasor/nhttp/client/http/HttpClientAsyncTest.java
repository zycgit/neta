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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.nhttp.client.ClientTestSupport;
import net.hasor.nhttp.client.HttpClient;
import net.hasor.nhttp.client.Response;
import net.hasor.nhttp.request.ContentBody;
import net.hasor.nhttp.request.Request;
import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

public class HttpClientAsyncTest extends ClientTestSupport {
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
    public void testExecuteAsyncPost() throws Exception {
        this.server.addServlet("/echo", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.write("echo:" + req.getBodyAsString());
            }
        });
        this.server.start(this.port);
        waitForServer();

        this.client = HttpClient.newBuilder().connectTimeout(5_000).readTimeout(5_000).writeTimeout(5_000).callTimeout(5_000).build();
        Request request = new Request.Builder().url("http://127.0.0.1:" + this.port + "/echo").post(ContentBody.text("abc")).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Response> responseRef = new AtomicReference<Response>();
        Future<Response> future = this.client.newCall(request).executeAsync();
        future.onCompleted(done -> {
            responseRef.set(done.getResult());
            latch.countDown();
        }).onFailed(done -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(200, responseRef.get().statusCode());
        assertEquals("echo:abc", responseRef.get().bodyText());
    }
}
