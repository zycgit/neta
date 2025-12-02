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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.DelimiterBasedFrameHandler;
import net.hasor.neta.codec.LineBasedFrameHandler;

/**
 * Combined {@link StringDecoder}, {@link StringEncoder}.
 * Please note that this decoder must be used with a proper ByteBuf to String
 * such as {@link DelimiterBasedFrameHandler} or {@link LineBasedFrameHandler}
 * if you are using a stream-based transport such as TCP/IP.
 * A typical setup for a text-based line protocol in a TCP/IP socket
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-21
 */
public class StringDuplexer implements ProtoDuplexer<ByteBuf, String, String, ByteBuf> {
    private final StringDecoder stringDecoder;
    private final StringEncoder stringEncoder;

    /**
     * Creates a new instance with the current system character set.
     */
    public StringDuplexer() {
        this(Charset.defaultCharset());
    }

    /**
     * Creates a new instance with the specified character set.
     */
    public StringDuplexer(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        this.stringDecoder = new StringDecoder(charset);
        this.stringEncoder = new StringEncoder(charset);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<String> rcvDown, ProtoRcvQueue<String> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
        if (isRcv) {
            return this.stringDecoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.stringEncoder.onMessage(context, sndUp, sndDown);
        }
    }
}