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
import net.hasor.neta.channel.virtual.VrtSoConfig;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Http2FrameDuplexeTest extends AbstractHttp2Test {
    @Test
    public void testServerFrameDuplexeDecodesInboundClientPrefaceAndEncodesOutboundFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplexe(true));
            }, VrtSoConfig.asServer());

            List<Http2Frame> inbound = receiveAndIntBound(pipe, ByteBuf.wrap(concat(CLIENT_PREFACE, frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0))));
            assertEquals(1, inbound.size());
            assertEquals(Http2FrameType.SETTINGS, inbound.get(0).type());
            assertEquals(0, inbound.get(0).streamId());

            List<ByteBuf> outbound = sendAndOutBound(pipe, Http2Frame.data(3, Http2Flags.END_STREAM, "Wiki".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(1, outbound.size());
            assertArrayEquals(frame(4, Http2FrameType.DATA, Http2Flags.END_STREAM, 3, "Wiki".getBytes(StandardCharsets.US_ASCII)), bytes(outbound.toArray(new ByteBuf[0])));
        });
    }

    @Test
    public void testClientFrameDuplexePassesPrefaceAsRawBytesOnSend() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplexe(false));
            }, VrtSoConfig.asClient());

            List<Http2Frame> inbound = receiveAndIntBound(pipe, ByteBuf.wrap(frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0)));
            assertEquals(1, inbound.size());
            assertEquals(Http2FrameType.SETTINGS, inbound.get(0).type());

            List<ByteBuf> outbound = sendAndOutBound(pipe, new Http2Frame(Http2FrameType.PREFACE, Http2Flags.NONE, 0, CLIENT_PREFACE));
            assertEquals(1, outbound.size());
            assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", text(outbound));
        });
    }
}
