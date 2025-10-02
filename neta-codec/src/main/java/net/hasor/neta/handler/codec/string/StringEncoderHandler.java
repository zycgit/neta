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
package net.hasor.neta.handler.codec.string;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.ProtoStatus;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Encodes the requested {@link String} into a {@link ByteBuf}.
 * A typical setup for a text-based line protocol in a TCP/IP socket
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-21
 */
public class StringEncoderHandler implements ProtoHandler<String, ByteBuf> {
    private final Charset charset;

    /**
     * Creates a new instance with the current system character set.
     */
    public StringEncoderHandler() {
        this(Charset.defaultCharset());
    }

    /**
     * Creates a new instance with the specified character set.
     */
    public StringEncoderHandler(Charset charset) {
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<ByteBuf> dst) {
        boolean hasAny = false;
        while (src.hasMore()) {
            String string = src.takeMessage();
            if (string != null) {
                dst.offerMessage(ByteBuf.wrap(string.getBytes(this.charset)));
                hasAny = true;
            }
        }
        return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
    }
}
