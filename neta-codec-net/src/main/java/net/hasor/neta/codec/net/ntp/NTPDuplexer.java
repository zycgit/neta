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
package net.hasor.neta.codec.net.ntp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoDuplexer;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Composite NTP codec that combines {@link NTPDecoder} and {@link NTPEncoder}
 * into a single bidirectional handler.
 * <p>
 * RCV direction: {@link net.hasor.neta.bytebuf.ByteBuf} → {@link NTPMessage} (via {@link NTPDecoder})<br>
 * SND direction: {@link NTPMessage} → {@link net.hasor.neta.bytebuf.ByteBuf} (via {@link NTPEncoder})
 * <p>
 * This is the recommended entry point for NTP pipeline setup over UDP:
 * <pre>
 *   manager.bind(address, ctx -&gt; {
 *       ctx.addLast("ntp", new NTPDuplexer());
 *       ctx.addLast("handler", myNtpHandler);
 *   }, SoConfig.UDP());
 * </pre>
 * <b>Applicable transports:</b> UDP (standard NTP uses UDP port 123; RFC 5905).
 * @author 赵永春 (zyc@hasor.net)
 * @see NTPDecoder
 * @see NTPEncoder
 * @see NTPMessage
 */
public class NTPDuplexer implements ProtoDuplexer<ByteBuf, NTPMessage, NTPMessage, ByteBuf> {
    private final NTPDecoder decoder = new NTPDecoder();
    private final NTPEncoder encoder = new NTPEncoder();

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<NTPMessage> rcvDown, ProtoRcvQueue<NTPMessage> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }
}
