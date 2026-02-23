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
package net.hasor.neta.codec.http.h2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link Http2Context} implementation and registration
 * through {@link Http2ServerDuplexe} and {@link Http2ClientDuplexe}.
 */
public class Http2ContextTest {

    private static ProtoContext mockContext() {
        Map<Class<?>, Object> contextMap = new ConcurrentHashMap<>();
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            if ("context".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                } else if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
            }
            return null;
        });
    }

    @Test
    public void serverDuplexe_registersContext() throws Throwable {
        Http2ServerDuplexe duplexe = new Http2ServerDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertNotNull("Http2Context should be registered after onInit", h2ctx);
    }

    @Test
    public void clientDuplexe_registersContext() throws Throwable {
        Http2ClientDuplexe duplexe = new Http2ClientDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertNotNull("Http2Context should be registered after onInit", h2ctx);
    }

    @Test
    public void serverContext_isServer() throws Throwable {
        Http2ServerDuplexe duplexe = new Http2ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertTrue("Server duplexe should report isServer=true", h2ctx.isServer());
        assertFalse("Server duplexe should report isClient=false", h2ctx.isClient());
    }

    @Test
    public void clientContext_isClient() throws Throwable {
        Http2ClientDuplexe duplexe = new Http2ClientDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertFalse("Client duplexe should report isServer=false", h2ctx.isServer());
        assertTrue("Client duplexe should report isClient=true", h2ctx.isClient());
    }

    @Test
    public void initialState_notReady() throws Throwable {
        Http2ServerDuplexe duplexe = new Http2ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        // Before receiving the connection preface, isReady should be false (for server mode)
        assertFalse("Server should not be ready before preface", h2ctx.isReady());
    }

    @Test
    public void initialState_defaultSettings() throws Throwable {
        Http2ServerDuplexe duplexe = new Http2ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        // Default HTTP/2 settings (RFC 7540 Section 6.5.2)
        assertTrue("maxConcurrentStreams should be positive", h2ctx.maxConcurrentStreams() > 0);
        assertTrue("initialWindowSize should be positive", h2ctx.initialWindowSize() > 0);
        assertTrue("maxFrameSize should be >= 16384", h2ctx.maxFrameSize() >= 16384);
    }

    @Test
    public void initialState_lastStreamIdZero() throws Throwable {
        Http2ServerDuplexe duplexe = new Http2ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertEquals("No streams should exist initially", 0, h2ctx.lastStreamId());
    }

    @Test
    public void createContext_returnsLiveView() throws Throwable {
        // Verify that the context object delegates to live decoder state
        Http2FrameToHttpDecoder decoder = new Http2FrameToHttpDecoder(true);
        Http2Context h2ctx = decoder.createContext();

        assertTrue("createContext on server decoder should report isServer=true", h2ctx.isServer());
        assertFalse("createContext on server decoder should report isClient=false", h2ctx.isClient());
    }

    @Test
    public void createContext_clientDecoder() {
        Http2FrameToHttpDecoder decoder = new Http2FrameToHttpDecoder(false);
        Http2Context h2ctx = decoder.createContext();

        assertFalse("createContext on client decoder should report isServer=false", h2ctx.isServer());
        assertTrue("createContext on client decoder should report isClient=true", h2ctx.isClient());
    }
}
