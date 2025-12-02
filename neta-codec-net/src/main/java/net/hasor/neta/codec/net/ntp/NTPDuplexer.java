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
import net.hasor.neta.channel.*;

/**
 * A duplex handler that combines {@link NTPDecoder} and {@link NTPEncoder}.
 * <p>
 * It handles both inbound (decoding) and outbound (encoding) NTP messages.
 * </p>
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
