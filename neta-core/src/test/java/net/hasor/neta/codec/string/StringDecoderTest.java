/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.string;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoQueue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-12
 */
public class StringDecoderTest {

    @Test
    public void stringDecoder_releasesConsumedByteBuf() {
        StringDecoder decoder = new StringDecoder(StandardCharsets.UTF_8);
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(4);
        ProtoQueue<String> dst = new ProtoQueue<>(4);
        ByteBuf input = ByteBuf.wrap("hello".getBytes(StandardCharsets.UTF_8));

        assert src.offerMessage(input);

        ProtoStatus status = decoder.onMessage(null, src, dst);

        assert status == ProtoStatus.Next;
        assert dst.queueSize() == 1;
        assert "hello".equals(dst.takeMessage());
        assert input.isFree();
        assert input.refCnt() == 0;
    }
}
