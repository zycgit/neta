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
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2FrameEncoderTest extends AbstractHttp2Test {
    @Test
    public void testEncoderSerializesDataFrameHeaderAndPayload() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-frame-encoder", new Http2FrameEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> outbound = sendAndOutBound(pipe, Http2Frame.data(3, Http2Flags.END_STREAM, "Wiki".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(1, outbound.size());
            assertArrayEquals(frame(4, Http2FrameType.DATA, Http2Flags.END_STREAM, 3, "Wiki".getBytes(StandardCharsets.US_ASCII)), bytes(outbound.toArray(new ByteBuf[0])));
        });
    }

    @Test
    public void testEncoderWritesPrefaceWithoutFrameHeader() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-frame-encoder", new Http2FrameEncoder());
            }, VrtSoConfig.asClient());

            List<ByteBuf> outbound = sendAndOutBound(pipe, new Http2Frame(Http2FrameType.PREFACE, Http2Flags.NONE, 0, CLIENT_PREFACE));
            assertEquals(1, outbound.size());
            assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", text(outbound));
        });
    }

    @Test
    public void testEncoderUsesPayloadSlice() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-frame-encoder", new Http2FrameEncoder());
            }, VrtSoConfig.asClient());

            byte[] payload = "012345".getBytes(StandardCharsets.US_ASCII);
            List<ByteBuf> outbound = sendAndOutBound(pipe, new Http2Frame(Http2FrameType.DATA, Http2Flags.NONE, 1, payload, 1, 3));
            assertEquals(1, outbound.size());
            assertArrayEquals(frame(3, Http2FrameType.DATA, Http2Flags.NONE, 1, "123".getBytes(StandardCharsets.US_ASCII)), bytes(outbound.toArray(new ByteBuf[0])));
        });
    }

    @Test
    public void testEncoderRejectsOutOfRangeStreamIdentifier() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("h2-frame-encoder", new Http2FrameEncoder());
            }, VrtSoConfig.asClient());

            List<Throwable> outboundErrors = sendAndOutError(pipe, new Http2Frame(Http2FrameType.HEADERS, Http2Flags.END_HEADERS, 0x92345678L));
            assertTrue(pipe.channelOutbound().isEmpty());
            assertEquals(1, outboundErrors.size());
            assertTrue(outboundErrors.get(0) instanceof IllegalArgumentException);
            assertTrue(outboundErrors.get(0).getMessage().contains("31-bit range"));
        });
    }

}