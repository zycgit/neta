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
package net.hasor.neta.handler;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeInitializer;
import net.hasor.neta.channel.SimplePipeLayer;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class CourierHandlerTest {
    @Test
    public void courierFrame_1() {
        CourierHandler<ByteBuf> courier = new CourierHandler<>();
        PipeInitializer pipeStack = new PipeHelper().nextHandler(courier, courier).build();

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
        CourierHandler<ByteBuf> courier = new CourierHandler<>();

        PipeInitializer pipeStack = new PipeHelper()//
                .nextDuplex("L1", new SimplePipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf>() {
                    @Override
                    public PipeStatus onMessage(PipeContext context, boolean isRcv, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws Throwable {
                        rcvDown.offerMessage(rcvUp);
                        sndDown.offerMessage(sndUp);
                        throw new IllegalStateException();
                    }
                }).nextHandler(courier, courier).build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeStack, context);

        assert channel.getPipeStatistical().heapUpOfRcv() == 0;

        channel.writeRcvUp(ByteBufAllocator.DEFAULT.wrap(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 }));

        assert channel.readRcvDown() == null;
        assert channel.readRcvDown() == null;
        assert channel.getPipeStatistical().heapUpOfRcv() == 1;
    }
}