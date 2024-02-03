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
import net.hasor.neta.handler.*;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LimitFrameHandlerTest {
    @Test
    public void limitFrame_1() {
        LimitFrameHandler limitFrame = new LimitFrameHandler(2);
        EmbeddedInitializer initializer = ctx -> {
            return PipeHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", limitFrame).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, initializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, initializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBufAllocator.DEFAULT.wrap(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }));
        transfer.transferToServer();

        assert server.getRcvSize() == 5;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 2;
        assert buf1.getByte(0) == 0;
        assert buf1.getByte(1) == 1;
        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 2;
        assert buf2.getByte(0) == 2;
        assert buf2.getByte(1) == 3;
        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 2;
        assert buf3.getByte(0) == 4;
        assert buf3.getByte(1) == 5;
        ByteBuf buf4 = (ByteBuf) server.readRcv();
        assert buf4.readableBytes() == 2;
        assert buf4.getByte(0) == 6;
        assert buf4.getByte(1) == 7;
        ByteBuf buf5 = (ByteBuf) server.readRcv();
        assert buf5.readableBytes() == 1;
        assert buf5.getByte(0) == 8;
    }

    @Test
    public void limitFrame_2() {
        LimitFrameHandler limitFrame = new LimitFrameHandler(2);
        EmbeddedInitializer initializer = ctx -> {
            return PipeHelper.embedded(ByteBuf.class, ByteBuf.class).nextDuplex(limitFrame, limitFrame).build();
        };

        //
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, initializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, initializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(new Object[] {//
                ByteBufAllocator.DEFAULT.wrap(new byte[] { 0, 1, 2 }),//
                ByteBufAllocator.DEFAULT.wrap(new byte[] { 3, 4, 5, 6, 7, 8 }) });
        transfer.transferToServer();

        assert server.getRcvSize() == 5;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 2;
        assert buf1.getByte(0) == 0;
        assert buf1.getByte(1) == 1;
        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 2;
        assert buf2.getByte(0) == 2;
        assert buf2.getByte(1) == 3;
        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 2;
        assert buf3.getByte(0) == 4;
        assert buf3.getByte(1) == 5;
        ByteBuf buf4 = (ByteBuf) server.readRcv();
        assert buf4.readableBytes() == 2;
        assert buf4.getByte(0) == 6;
        assert buf4.getByte(1) == 7;
        ByteBuf buf5 = (ByteBuf) server.readRcv();
        assert buf5.readableBytes() == 1;
        assert buf5.getByte(0) == 8;
    }
}