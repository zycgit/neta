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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.SoRcvException;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.HttpProtocolException;
import net.hasor.neta.codec.http.HttpProtocolOutOfBoundsException;
import net.hasor.neta.codec.http.HttpProtocolStateException;

public class Http2FrameDecoderTest extends AbstractHttp2Test {
    @Test
    public void testServerDecoderAcceptsFragmentedPrefaceAndSettingsFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            }, VrtSoConfig.asServer());

            List<Http2Frame> first = receiveAndIntBound(pipe, ByteBuf.wrap(Arrays.copyOfRange(CLIENT_PREFACE, 0, 8)));
            assertTrue(first.isEmpty());

            byte[] remainPreface = Arrays.copyOfRange(CLIENT_PREFACE, 8, CLIENT_PREFACE.length);
            byte[] settingsFrame = frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0);
            List<Http2Frame> second = receiveAndIntBound(pipe, ByteBuf.wrap(concat(remainPreface, settingsFrame)));
            assertEquals(1, second.size());

            Http2Frame frame = second.get(0);
            assertEquals(Http2FrameType.SETTINGS, frame.type());
            assertEquals(Http2Flags.NONE, frame.flags());
            assertEquals(0, frame.streamId());
            assertEquals(0, frame.payloadLength());
        });
    }

    @Test
    public void testClientDecoderWaitsForCompleteFramePayload() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(false));
            }, VrtSoConfig.asClient());

            byte[] payload = "Wiki".getBytes(StandardCharsets.US_ASCII);
            byte[] encoded = frame(payload.length, Http2FrameType.DATA, Http2Flags.END_STREAM, 3, payload);

            List<Http2Frame> first = receiveAndIntBound(pipe, ByteBuf.wrap(Arrays.copyOfRange(encoded, 0, 7)));
            assertTrue(first.isEmpty());

            List<Http2Frame> second = receiveAndIntBound(pipe, ByteBuf.wrap(Arrays.copyOfRange(encoded, 7, encoded.length)));
            assertEquals(1, second.size());

            Http2Frame frame = second.get(0);
            assertEquals(Http2FrameType.DATA, frame.type());
            assertEquals(Http2Flags.END_STREAM, frame.flags());
            assertEquals(3, frame.streamId());
            assertEquals("Wiki", new String(frame.payload(), frame.payloadOffset(), frame.payloadLength(), StandardCharsets.US_ASCII));
        });
    }

    @Test
    public void testDecoderRejectsInvalidConnectionPreface() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(true));
            }, VrtSoConfig.asServer());

            List<Throwable> errors = receiveAndIntError(pipe, ByteBuf.wrap("NOT A VALID HTTP/2 PREFACE!!".getBytes(StandardCharsets.US_ASCII)));
            Throwable root = assertSingleProtocolViolation(errors);
            assertTrue(root instanceof HttpProtocolStateException);
            assertTrue(root.getMessage().contains("invalid connection preface"));
            assertEquals(0, ((HttpProtocolException) root).streamId());
            assertTrue(receiveAndIntBound(pipe).isEmpty());
        });
    }

    @Test
    public void testDecoderRejectsFrameExceedingMaxFrameSize() throws Throwable {
        autoCloseNeta(neta -> {
            Http2Settings settings = new Http2Settings().maxFrameSize(16384);
            Http2FrameDecoder decoder = new Http2FrameDecoder(false, settings);
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", decoder);
            }, VrtSoConfig.asClient());

            List<Throwable> errors = receiveAndIntError(pipe, ByteBuf.wrap(frameHeader(16385, Http2FrameType.DATA, Http2Flags.NONE, 1)));
            Throwable root = assertSingleProtocolViolation(errors);
            assertTrue(root instanceof HttpProtocolOutOfBoundsException);
            assertTrue(root.getMessage().contains("SETTINGS_MAX_FRAME_SIZE"));
            assertEquals(1, ((HttpProtocolException) root).streamId());
            assertTrue(receiveAndIntBound(pipe).isEmpty());
        });
    }

    @Test
    public void testDecoderStripsReservedBitFromStreamIdentifier() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("h2-frame-decoder", new Http2FrameDecoder(false));
            }, VrtSoConfig.asClient());

            List<Http2Frame> frames = receiveAndIntBound(pipe, ByteBuf.wrap(frame(0, Http2FrameType.HEADERS, Http2Flags.END_HEADERS, 0x81234567)));
            assertEquals(1, frames.size());
            assertEquals(0x01234567, frames.get(0).streamId());
        });
    }

    private static Throwable assertSingleProtocolViolation(List<Throwable> errors) {
        assertEquals(1, errors.size());
        Throwable error = errors.get(0);
        assertTrue(error instanceof SoRcvException || error instanceof HttpProtocolException);
        Throwable root = error.getCause() != null ? error.getCause() : error;
        assertTrue(root instanceof HttpProtocolException);
        return root;
    }

}