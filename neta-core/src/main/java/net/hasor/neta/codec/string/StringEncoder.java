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

/**
 * Encodes each outbound {@link String} into a {@link ByteBuf} with the configured charset.
 * <p>
 * This handler only converts bytes and does not append delimiters or other framing
 * markers. Protocols that require separators, line endings, or length headers need
 * an additional outbound framing stage.
 * <p><b>Ownership:</b> each emitted {@link ByteBuf} is newly created and belongs to
 * downstream once offered. The consumed {@link String} itself has no release lifecycle.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-21
 */
public class StringEncoder implements ProtoHandler<String, ByteBuf> {
    private final Charset charset;

    /**
     * Creates a new instance with the current system character set.
     */
    public StringEncoder() {
        this(Charset.defaultCharset());
    }

    /**
     * Creates a new instance with the specified character set.
     */
    public StringEncoder(Charset charset) {
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
