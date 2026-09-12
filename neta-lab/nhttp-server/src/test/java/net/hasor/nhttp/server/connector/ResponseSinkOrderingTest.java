/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.DefaultHttpResponse;
import net.hasor.neta.codec.http.DefaultLastHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpVersion;

/**
 * Verifies response sinks send the status line and header block as one ordered batch.
 */
public class ResponseSinkOrderingTest {
    @Test
    public void http1_headersAreSentAsSingleBatch() {
        RecordingContext recording = new RecordingContext();
        Http1ResponseSink sink = new Http1ResponseSink(recording.proxy(), HttpVersion.HTTP_1_1);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        sink.sendHeaders(200, headers, true);

        assert recording.batches.size() == 1 : "http/1 sink should send one encoded batch";
        Object[] batch = recording.batches.get(0);
        assert batch.length == 2 : "http/1 sink should send response line plus header block together";
        assert batch[0] instanceof DefaultHttpResponse;
        assert batch[1] instanceof DefaultLastHttpHeaders;
    }

    @Test
    public void http2_headersAreSentAsSingleBatch() {
        RecordingContext recording = new RecordingContext();
        Http2ResponseSink sink = new Http2ResponseSink(recording.proxy(), 7L);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        sink.sendHeaders(200, headers, true);

        assert recording.batches.size() == 1 : "http/2 sink should send one encoded batch";
        Object[] batch = recording.batches.get(0);
        assert batch.length == 2 : "http/2 sink should send response line plus header block together";
        assert batch[0] instanceof DefaultHttpResponse;
        assert batch[1] instanceof DefaultLastHttpHeaders;
    }

    private static class RecordingContext implements InvocationHandler {
        private final List<Object[]> batches = new ArrayList<>();

        private ProtoContext proxy() {
            return (ProtoContext) Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("sendEncoded".equals(method.getName())) {
                Object firstArg = args != null && args.length > 0 ? args[0] : null;
                if (firstArg instanceof Object[]) {
                    this.batches.add((Object[]) firstArg);
                } else if (firstArg != null) {
                    this.batches.add(new Object[] { firstArg });
                }
                return null;
            }

            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
