/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.string;
import java.nio.charset.Charset;
import java.util.Objects;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
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
            if (!ByteBufUtils.hasWritableSlots(dst, 1)) {
                return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
            }
            String string = src.takeMessage();
            if (string == null) {
                continue;
            }
            ByteBuf output = ByteBuf.wrap(string.getBytes(this.charset));
            dst.offerMessage(output);
            hasAny = true;
        }
        return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
    }

    @Override
    public void onClose(ProtoContext context) {
    }
}
