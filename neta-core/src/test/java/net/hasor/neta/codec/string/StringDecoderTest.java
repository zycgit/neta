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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoQueue;
import net.hasor.neta.channel.ProtoStatus;
import org.junit.Test;

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
        src.sndSubmit();

        ProtoStatus status = decoder.onMessage(null, src, dst);
        src.rcvSubmit();
        dst.sndSubmit();

        assert status == ProtoStatus.Next;
        assert dst.queueSize() == 1;
        assert "hello".equals(dst.takeMessage());
        assert input.isFree();
        assert input.refCnt() == 0;
    }
}