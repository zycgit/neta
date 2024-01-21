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
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;
import net.hasor.neta.handler.codec.DelimiterBasedFrameHandler;
import net.hasor.neta.handler.codec.LineBasedFrameHandler;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Decodes a received {@link ByteBuf} into a {@link String}.
 * Please note that this decoder must be used with a proper ByteBuf to String
 * such as {@link DelimiterBasedFrameHandler} or {@link LineBasedFrameHandler}
 * if you are using a stream-based transport such as TCP/IP.
 * A typical setup for a text-based line protocol in a TCP/IP socket
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-21
 */
public class StringDecoderHandler implements PipeHandler<ByteBuf, String> {
    private final Charset charset;

    /**
     * Creates a new instance with the current system character set.
     */
    public StringDecoderHandler() {
        this(Charset.defaultCharset());
    }

    /**
     * Creates a new instance with the specified character set.
     */
    public StringDecoderHandler(Charset charset) {
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public PipeStatus doHandler(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
        boolean hasAny = false;
        while (src.hasMore()) {
            ByteBuf byteBuf = src.takeMessage();
            dst.offerMessage(byteBuf.readString(byteBuf.readableBytes(), this.charset));
            hasAny = true;
        }
        return hasAny ? PipeStatus.Next : PipeStatus.Exit;
    }
}
