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
package net.hasor.neta.handler.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.handler.EmbeddedChannel;
import net.hasor.neta.handler.EmbeddedInitializer;
import net.hasor.neta.handler.EmbeddedSoContext;
import net.hasor.neta.handler.ProtoHelper;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LineBasedFrameHandlerTest {
    @Test
    public void lineBasedFrame_1() {
        LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
        EmbeddedInitializer initializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder(lineBasedFrame).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(ByteBufAllocator.DEFAULT.wrap("abc".getBytes()));
        assert channel.readRcv() == null;

        channel.receive(ByteBufAllocator.DEFAULT.wrap("\r\n".getBytes()));

        ByteBuf rcvDown = (ByteBuf) channel.readRcv();
        assert new String(ByteBufUtils.toBytes(rcvDown)).equals("abc\r\n");
    }

    @Test
    public void lineBasedFrame_2() {
        LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
        EmbeddedInitializer initializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder(lineBasedFrame).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        channel.receive(ByteBufAllocator.DEFAULT.wrap("abc\r\n123".getBytes()));

        ByteBuf rcvDown = (ByteBuf) channel.readRcv();
        assert new String(ByteBufUtils.toBytes(rcvDown)).equals("abc\r\n");
    }

    @Test
    public void lineBasedFrame_3() {
        LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
        EmbeddedInitializer initializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder(lineBasedFrame).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.receive(ByteBufAllocator.DEFAULT.wrap("abc\r\n123".getBytes()));
        channel.receive(ByteBufAllocator.DEFAULT.wrap("\r\n".getBytes()));

        ByteBuf dat1 = (ByteBuf) channel.readRcv();
        assert new String(ByteBufUtils.toBytes(dat1)).equals("abc\r\n");
        ByteBuf dat2 = (ByteBuf) channel.readRcv();
        assert new String(ByteBufUtils.toBytes(dat2)).equals("123\r\n");
    }
}