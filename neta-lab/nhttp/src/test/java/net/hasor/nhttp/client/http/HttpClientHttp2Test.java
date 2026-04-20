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
package net.hasor.nhttp.client.http;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.nhttp.client.ClientTestSupport;
import net.hasor.nhttp.client.HttpClient;
import net.hasor.nhttp.client.HttpVersionPolicy;
import net.hasor.nhttp.client.Response;
import net.hasor.nhttp.request.Request;
import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * TLS + HTTP/2 integration tests for HttpClient.
 */
public class HttpClientHttp2Test extends ClientTestSupport {
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
    public void testHttpsHttp2Get() throws Exception {
        this.server.ssl(serverSslConfig()).http2(true);
        this.server.addServlet("/hello", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.write("hello-h2");
            }
        });
        this.server.startSSL(this.port);
        waitForServer();

        this.client = HttpClient.newBuilder().ssl(trustAllClientSslConfig()).versionPolicy(HttpVersionPolicy.HTTP_2).connectTimeout(5_000).readTimeout(5_000).writeTimeout(5_000).callTimeout(5_000).build();
        Request request = new Request.Builder().url("https://127.0.0.1:" + this.port + "/hello").get().build();

        Response response = this.client.newCall(request).execute();
        assertEquals(200, response.statusCode());
        assertEquals("hello-h2", response.bodyText());
        assertEquals(HttpVersion.HTTP_2_0, response.version());
    }
}