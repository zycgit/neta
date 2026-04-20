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
package net.hasor.neta.codec.string;
import java.nio.charset.Charset;
import java.util.Objects;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.DelimiterBasedFrameHandler;
import net.hasor.neta.codec.LineBasedFrameHandler;
/**
 * Decodes each received {@link ByteBuf} into a {@link String} with the configured charset.
 * <p>
 * This handler performs only byte-to-string conversion. It does not split a byte
 * stream into message boundaries, so stream transports such as TCP should pair it
 * with a frame decoder like {@link DelimiterBasedFrameHandler} or
 * {@link LineBasedFrameHandler} first.
 * <p><b>Ownership:</b> once a {@link ByteBuf} is converted into a {@link String},
 * this decoder releases the consumed buffer before returning.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-21
 */
public class StringDecoder implements ProtoHandler<ByteBuf, String> {
    private final Charset charset;

    /**
     * Creates a new instance with the current system character set.
     */
    public StringDecoder() {
        this(Charset.defaultCharset());
    }

    /**
     * Creates a new instance with the specified character set.
     */
    public StringDecoder(Charset charset) {
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<String> dst) {
        boolean hasAny = false;
        while (src.hasMore()) {
            ByteBuf byteBuf = src.takeMessage();
            if (byteBuf != null) {
                try {
                    dst.offerMessage(byteBuf.readString(byteBuf.readableBytes(), this.charset));
                    hasAny = true;
                } finally {
                    byteBuf.release();
                }
            }
        }
        return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
    }
}
