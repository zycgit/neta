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
package net.hasor.neta.codec.http3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.quic.QuicContext;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link Http3Context} and {@link QuicContext} implementation
 * and registration through {@link Http3ServerDuplexe} and {@link Http3ClientDuplexe}.
 */
public class Http3ContextTest {

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

    // ==================== Http3Context Tests ====================

    @Test
    public void serverDuplexe_registersHttp3Context() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertNotNull("Http3Context should be registered after onInit", h3ctx);
    }

    @Test
    public void clientDuplexe_registersHttp3Context() throws Throwable {
        Http3ClientDuplexe duplexe = new Http3ClientDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertNotNull("Http3Context should be registered after onInit", h3ctx);
    }

    @Test
    public void serverContext_isServer() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertTrue("Server duplexe should report isServer=true", h3ctx.isServer());
        assertFalse("Server duplexe should report isClient=false", h3ctx.isClient());
    }

    @Test
    public void clientContext_isClient() throws Throwable {
        Http3ClientDuplexe duplexe = new Http3ClientDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertFalse("Client duplexe should report isServer=false", h3ctx.isServer());
        assertTrue("Client duplexe should report isClient=true", h3ctx.isClient());
    }

    @Test
    public void initialState_notReady() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertFalse("Server should not be ready before SETTINGS received", h3ctx.isReady());
    }

    @Test
    public void initialState_defaultSettings() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        // Default HTTP/3 settings — defaults should be non-negative
        assertTrue("maxFieldSectionSize should be >= 0", h3ctx.maxFieldSectionSize() >= 0);
        assertTrue("qpackMaxTableCapacity should be >= 0", h3ctx.qpackMaxTableCapacity() >= 0);
        assertTrue("qpackBlockedStreams should be >= 0", h3ctx.qpackBlockedStreams() >= 0);
    }

    @Test
    public void initialState_lastStreamIdZero() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertEquals("No streams should exist initially", 0L, h3ctx.lastStreamId());
    }

    // ==================== QuicContext Tests ====================

    @Test
    public void serverDuplexe_registersQuicContext() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertNotNull("QuicContext should be registered after onInit", quicCtx);
    }

    @Test
    public void clientDuplexe_registersQuicContext() throws Throwable {
        Http3ClientDuplexe duplexe = new Http3ClientDuplexe();
        ProtoContext ctx = mockContext();

        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertNotNull("QuicContext should be registered after onInit", quicCtx);
    }

    @Test
    public void quicContext_serverMode() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertTrue("QUIC server should report isServer=true", quicCtx.isServer());
        assertFalse("QUIC server should report isClient=false", quicCtx.isClient());
    }

    @Test
    public void quicContext_clientMode() throws Throwable {
        Http3ClientDuplexe duplexe = new Http3ClientDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertFalse("QUIC client should report isServer=false", quicCtx.isServer());
        assertTrue("QUIC client should report isClient=true", quicCtx.isClient());
    }

    @Test
    public void quicContext_initialState() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertTrue("QUIC should be ready", quicCtx.isReady());
        assertEquals("QUIC version should be v1", 0x00000001, quicCtx.negotiatedVersion());
        assertNotNull("peerConnectionId should not be null", quicCtx.peerConnectionId());
        assertNotNull("localConnectionId should not be null", quicCtx.localConnectionId());
    }

    @Test
    public void quicContext_transportSettings() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertTrue("maxBidiStreams should be >= 0", quicCtx.maxBidiStreams() >= 0);
        assertTrue("maxUniStreams should be >= 0", quicCtx.maxUniStreams() >= 0);
        assertTrue("idleTimeout should be >= 0", quicCtx.idleTimeout() >= 0);
    }

    // ==================== Both contexts registered simultaneously ====================

    @Test
    public void serverDuplexe_registersBothContexts() throws Throwable {
        Http3ServerDuplexe duplexe = new Http3ServerDuplexe();
        ProtoContext ctx = mockContext();
        duplexe.onInit(ctx);

        assertNotNull("Http3Context should be available", ctx.context(Http3Context.class));
        assertNotNull("QuicContext should be available", ctx.context(QuicContext.class));

        // They should be independent
        assertNotSame("Http3Context and QuicContext should be different objects", ctx.context(Http3Context.class), ctx.context(QuicContext.class));
    }

    @Test
    public void createContext_decoderLevel() {
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);
        Http3Context h3ctx = decoder.createContext();

        assertTrue("Server decoder should report isServer=true", h3ctx.isServer());
        assertFalse("Server decoder should report isClient=false", h3ctx.isClient());
    }
}
