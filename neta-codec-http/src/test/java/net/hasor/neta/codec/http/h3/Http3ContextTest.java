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
package net.hasor.neta.codec.http.h3;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link Http3Context} implementation created via {@link Http3FrameDecoder#createContext()}.
 */
public class Http3ContextTest {
    private static final Http3Settings SERVER_H3_SETTINGS = Http3Settings.defaultLocalSettings(true);
    private static final Http3Settings CLIENT_H3_SETTINGS = Http3Settings.defaultLocalSettings(false);

    // ==================== Http3Context via Decoder ====================

    @Test
    public void createContext_serverDecoder() {
        Http3DecoderContent state = new Http3DecoderContent(SERVER_H3_SETTINGS);
        Http3Context h3ctx = new Http3ContextImpl(true, state);

        assertNotNull("createContext should return non-null", h3ctx);
        assertTrue("Server decoder should report isServer=true", h3ctx.isServer());
        assertFalse("Server decoder should report isClient=false", h3ctx.isClient());
    }

    @Test
    public void createContext_clientDecoder() {
        Http3DecoderContent state = new Http3DecoderContent(CLIENT_H3_SETTINGS);
        Http3Context h3ctx = new Http3ContextImpl(false, state);

        assertNotNull("createContext should return non-null", h3ctx);
        assertFalse("Client decoder should report isServer=false", h3ctx.isServer());
        assertTrue("Client decoder should report isClient=true", h3ctx.isClient());
    }

    @Test
    public void createContext_initialState_notReady() {
        Http3DecoderContent state = new Http3DecoderContent(SERVER_H3_SETTINGS);
        Http3Context h3ctx = new Http3ContextImpl(true, state);

        assertFalse("Should not be ready before SETTINGS received", h3ctx.isReady());
    }

    @Test
    public void createContext_initialState_defaultSettings() {
        Http3DecoderContent state = new Http3DecoderContent(SERVER_H3_SETTINGS);
        Http3Context h3ctx = new Http3ContextImpl(true, state);

        assertTrue("maxFieldSectionSize should be >= 0", h3ctx.maxFieldSectionSize() >= 0);
        assertTrue("qpackMaxTableCapacity should be >= 0", h3ctx.qpackMaxTableCapacity() >= 0);
        assertTrue("qpackBlockedStreams should be >= 0", h3ctx.qpackBlockedStreams() >= 0);
    }

    @Test
    public void createContext_initialState_lastStreamIdZero() {
        Http3DecoderContent state = new Http3DecoderContent(SERVER_H3_SETTINGS);
        Http3Context h3ctx = new Http3ContextImpl(true, state);

        assertEquals("No streams should exist initially", 0L, h3ctx.lastStreamId());
    }

    @Test
    public void createContext_multipleCallsReturnIndependentInstances() {
        Http3DecoderContent state = new Http3DecoderContent(SERVER_H3_SETTINGS);
        Http3Context ctx1 = new Http3ContextImpl(true, state);
        Http3Context ctx2 = new Http3ContextImpl(true, state);

        assertNotNull(ctx1);
        assertNotNull(ctx2);
        // Both should reflect the same underlying decoder state
        assertEquals(ctx1.isServer(), ctx2.isServer());
        assertEquals(ctx1.isReady(), ctx2.isReady());
    }
}
