/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.util.List;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HttpContextPublicationTest extends AbstractHttpTest {
    @Test
    public void requestsReplaceExternalBindingsAndTrackProtocolChanges() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] captured = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta, context -> {
                captured[0] = context;
                context.addLastDecoder("request", new HttpRequestDecoder());
            }, VrtSoConfig.asServer());
            for (HttpVersion version : new HttpVersion[] { HttpVersion.HTTP_1_1, HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1 }) {
                captured[0].context(HttpVersion.class, HttpVersion.HTTP_3_0);
                captured[0].context(HttpScope.class, HttpScope.STREAM);
                List<HttpObject> messages = receiveAndIntBound(pipe, ascii("GET / " + version.text() + "\r\nHost: example.com\r\n\r\n"));
                try {
                    assertEquals(version, captured[0].context(HttpVersion.class));
                    assertEquals(HttpScope.CONNECTION, captured[0].context(HttpScope.class));
                } finally {
                    free(messages);
                }
                captured[0].context(HttpVersion.class, null);
                captured[0].context(HttpScope.class, null);
                messages = receiveAndIntBound(pipe, ascii("GET / " + version.text() + "\r\nHost: example.com\r\n\r\n"));
                try {
                    assertEquals(version, captured[0].context(HttpVersion.class));
                    assertEquals(HttpScope.CONNECTION, captured[0].context(HttpScope.class));
                } finally {
                    free(messages);
                }
            }
        });
    }

    @Test
    public void responsesReplaceExternalBindingsAndTrackProtocolChanges() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] captured = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta, context -> {
                captured[0] = context;
                context.addLastDecoder("response", new HttpResponseDecoder());
            }, VrtSoConfig.asClient());
            for (HttpVersion version : new HttpVersion[] { HttpVersion.HTTP_1_1, HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1 }) {
                captured[0].context(HttpVersion.class, HttpVersion.HTTP_3_0);
                captured[0].context(HttpScope.class, HttpScope.STREAM);
                List<HttpObject> messages = receiveAndIntBound(pipe, ascii(version.text() + " 200 OK\r\nContent-Length: 0\r\n\r\n"));
                try {
                    assertEquals(version, captured[0].context(HttpVersion.class));
                    assertEquals(HttpScope.CONNECTION, captured[0].context(HttpScope.class));
                } finally {
                    free(messages);
                }
                captured[0].context(HttpVersion.class, null);
                captured[0].context(HttpScope.class, null);
                messages = receiveAndIntBound(pipe, ascii(version.text() + " 200 OK\r\nContent-Length: 0\r\n\r\n"));
                try {
                    assertEquals(version, captured[0].context(HttpVersion.class));
                    assertEquals(HttpScope.CONNECTION, captured[0].context(HttpScope.class));
                } finally {
                    free(messages);
                }
            }
        });
    }
}
