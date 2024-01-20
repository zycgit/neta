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
import net.hasor.neta.channel.PipelineFactory;
import net.hasor.neta.handler.*;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class CourierPipeHandlerTest {
    @Test
    public void courierFrame_1() {
        CourierPipeHandler<ByteBuf> courier = new CourierPipeHandler<>();
        PipelineFactory pipeStack = new PipeInitializer().nextTo(courier, courier).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, pipeStack, context);
        EmbeddedChannel client = new EmbeddedChannel(false, pipeStack, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.writeSndUp(ByteBufAllocator.DEFAULT.wrap(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }));
        transfer.transferToServer();

        assert server.getRcvDownSize() == 1;

        ByteBuf buf1 = (ByteBuf) server.readRcvDown();
        assert buf1.readableBytes() == 9;
        assert buf1.getByte(0) == 0;
        assert buf1.getByte(1) == 1;
        assert buf1.getByte(2) == 2;
        assert buf1.getByte(3) == 3;
        assert buf1.getByte(4) == 4;
        assert buf1.getByte(5) == 5;
        assert buf1.getByte(6) == 6;
        assert buf1.getByte(7) == 7;
        assert buf1.getByte(8) == 8;
        ByteBuf buf2 = (ByteBuf) server.readRcvDown();
        assert buf2 == null;
    }

    @Test
    public void courierFrame_2() {
        CourierPipeHandler<ByteBuf> courier = new CourierPipeHandler<>();

        PipelineFactory pipeStack = new PipeInitializer()//
                .nextTo((PipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
                    rcvDown.offerMessage(rcvUp);
                    sndDown.offerMessage(sndUp);
                    throw new IllegalStateException();
                }).nextTo(courier, courier).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeStack, context);

        channel.writeRcvUp(ByteBufAllocator.DEFAULT.wrap(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }));

        ByteBuf buf1 = (ByteBuf) channel.readRcvDown();
        assert buf1.readableBytes() == 9;
        assert buf1.getByte(0) == 0;
        assert buf1.getByte(1) == 1;
        assert buf1.getByte(2) == 2;
        assert buf1.getByte(3) == 3;
        assert buf1.getByte(4) == 4;
        assert buf1.getByte(5) == 5;
        assert buf1.getByte(6) == 6;
        assert buf1.getByte(7) == 7;
        assert buf1.getByte(8) == 8;
        ByteBuf buf2 = (ByteBuf) channel.readRcvDown();
        assert buf2 == null;
    }
}