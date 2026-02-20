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
package net.hasor.neta.codec.spdy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link SpdyContext} implementation and registration
 * through {@link SpdyServerDuplexe} and {@link SpdyClientDuplexe}.
 */
public class SpdyContextTest {

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
        SpdyServerDuplexe duplexe = new SpdyServerDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertNotNull("SpdyContext should be registered after onInit", spdyCtx);
    }

    @Test
    public void clientDuplexe_registersContext() throws Throwable {
        SpdyClientDuplexe duplexe = new SpdyClientDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertNotNull("SpdyContext should be registered after onInit", spdyCtx);
    }

    @Test
    public void serverContext_isServer() throws Throwable {
        SpdyServerDuplexe duplexe = new SpdyServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertTrue("Server duplexe should report isServer=true", spdyCtx.isServer());
        assertFalse("Server duplexe should report isClient=false", spdyCtx.isClient());
    }

    @Test
    public void clientContext_isClient() throws Throwable {
        SpdyClientDuplexe duplexe = new SpdyClientDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertFalse("Client duplexe should report isServer=false", spdyCtx.isServer());
        assertTrue("Client duplexe should report isClient=true", spdyCtx.isClient());
    }

    @Test
    public void context_version() throws Throwable {
        SpdyServerDuplexe duplexe = new SpdyServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertEquals("SPDY version should be 3", 3, spdyCtx.version());
    }

    @Test
    public void context_initialState() throws Throwable {
        SpdyServerDuplexe duplexe = new SpdyServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertTrue("SPDY should be ready after init", spdyCtx.isReady());
        assertEquals("No streams should exist initially", 0, spdyCtx.lastStreamId());
        assertTrue("maxConcurrentStreams should be positive", spdyCtx.maxConcurrentStreams() > 0);
    }

    @Test
    public void createContext_decoderLevel() {
        SpdyFrameDecoder decoder = new SpdyFrameDecoder(true);
        SpdyContext spdyCtx = decoder.createContext();

        assertTrue("Server decoder should report isServer=true", spdyCtx.isServer());
        assertEquals("Version should be 3", 3, spdyCtx.version());
    }

    @Test
    public void createContext_clientDecoder() {
        SpdyFrameDecoder decoder = new SpdyFrameDecoder(false);
        SpdyContext spdyCtx = decoder.createContext();

        assertFalse("Client decoder should report isServer=false", spdyCtx.isServer());
        assertTrue("Client decoder should report isClient=true", spdyCtx.isClient());
    }
}
