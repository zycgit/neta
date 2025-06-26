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
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.nio.ByteOrder;
import java.util.Objects;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LengthFieldBasedFrameHandlerTest {

    private void coderTest1_case1(EmbeddedChannel channel) {
        ByteBuf buf1 = (ByteBuf) channel.readRcv();
        assert buf1.readableBytes() == 6;
        assert Objects.deepEquals(buf1.asByteArray(), new byte[] { 0, 4, 1, 2, 3, 4 });

        ByteBuf buf2 = (ByteBuf) channel.readRcv();
        assert buf2.readableBytes() == 4;
        assert Objects.deepEquals(buf2.asByteArray(), new byte[] { 0, 2, 1, 2 });

        ByteBuf buf3 = (ByteBuf) channel.readRcv();
        assert buf3.readableBytes() == 10;
        assert Objects.deepEquals(buf3.asByteArray(), new byte[] { 0, 8, 1, 2, 3, 4, 5, 6, 7, 8 });

        ByteBuf buf4 = (ByteBuf) channel.readRcv();
        assert buf4.readableBytes() == 2;
        assert Objects.deepEquals(buf4.asByteArray(), new byte[] { 0, 0 });

        ByteBuf buf5 = (ByteBuf) channel.readRcv();
        assert buf5.readableBytes() == 3;
        assert Objects.deepEquals(buf5.asByteArray(), new byte[] { 0, 1, 1 });
    }

    private void coderTest1_case2(EmbeddedChannel channel) {
        ByteBuf buf1 = (ByteBuf) channel.readRcv();
        assert buf1.readableBytes() == 4;
        assert Objects.deepEquals(buf1.asByteArray(), new byte[] { 1, 2, 3, 4 });

        ByteBuf buf2 = (ByteBuf) channel.readRcv();
        assert buf2.readableBytes() == 2;
        assert Objects.deepEquals(buf2.asByteArray(), new byte[] { 1, 2 });

        ByteBuf buf3 = (ByteBuf) channel.readRcv();
        assert buf3.readableBytes() == 8;
        assert Objects.deepEquals(buf3.asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

        ByteBuf buf4 = (ByteBuf) channel.readRcv();
        assert buf4.readableBytes() == 0;
        assert Objects.deepEquals(buf4.asByteArray(), new byte[0]);

        ByteBuf buf5 = (ByteBuf) channel.readRcv();
        assert buf5.readableBytes() == 1;
        assert Objects.deepEquals(buf5.asByteArray(), new byte[] { 1 });
    }

    @Test
    public void coder_1_case1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] {   //
                0, 4, 1, 2, 3, 4,               //
                0, 2, 1, 2,                     //
                0, 8, 1, 2, 3, 4, 5, 6, 7, 8,   //
                0, 0,                           //
                0, 1, 1,                        //
                99, 99 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case1(server);
    }

    @Test
    public void coder_1_case2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] {   //
                0, 4, 1, 2, 3, 4,               //
                0, 2, 1, 2,                     //
                0, 8, 1, 2, 3, 4, 5, 6, 7, 8,   //
                0, 0,                           //
                0, 1, 1,                        //
                99, 99 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }

    @Test
    public void coder_1_case3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] {   //
                1, 0, 4, 1, 2, 3, 4,            //
                2, 0, 2, 1, 2,                  //
                3, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8,//
                4, 0, 0,                        //
                5, 0, 1, 1,                     //
                6, 99, 99 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }

    @Test
    public void coder_2_case1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        byte[] bytes1 = new byte[] {            //
                0, 4, 1, 2, 3, 4,               //
                0, 2, 1, 2,                     //
                0, 8, 1, 2, 3, 4, 5, 6, 7, 8,   //
                0, 0,                           //
                0, 1, 1,                        //
                99, 99 };
        for (byte b : bytes1) {
            client.send(ByteBuf.wrap(new byte[] { b }));
        }

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case1(server);
    }

    @Test
    public void coder_2_case2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        byte[] bytes1 = new byte[] {            //
                0, 4, 1, 2, 3, 4,               //
                0, 2, 1, 2,                     //
                0, 8, 1, 2, 3, 4, 5, 6, 7, 8,   //
                0, 0,                           //
                0, 1, 1,                        //
                99, 99 };
        for (byte b : bytes1) {
            client.send(ByteBuf.wrap(new byte[] { b }));
        }

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }

    @Test
    public void coder_2_case3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        byte[] bytes1 = new byte[] {            //
                1, 0, 4, 1, 2, 3, 4,            //
                2, 0, 2, 1, 2,                  //
                3, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8,//
                4, 0, 0,                        //
                5, 0, 1, 1,                     //
                6, 99, 99 };
        for (byte b : bytes1) {
            client.send(ByteBuf.wrap(new byte[] { b }));
        }

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }

    @Test
    public void coder_3_case1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 0 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 1 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 3, 4, 0 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 1, 2, 0, 8, 1, 2, 3 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case1(server);
    }

    @Test
    public void coder_3_case2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 0 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 1 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 3, 4, 0 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 1, 2, 0, 8, 1, 2, 3 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }

    @Test
    public void coder_3_case3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 0 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 1 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 3, 4, 2, 0 }));
        client.send(ByteBuf.wrap(new byte[] { 2, 1, 2, 3, 0, 8, 1, 2, 3 }));
        client.send(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 4, 0, 0, 5, 0, 1, 1, 6, 99, 99 }));
        byte[] bytes1 = new byte[] {            //
                1, 0, 4, 1, 2, 3, 4,            //
                2, 0, 2, 1, 2,                  //
                3, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8,//
                4, 0, 0,                        //
                5, 0, 1, 1,                     //
                6, 99, 99 };
        for (byte b : bytes1) {
            client.send(ByteBuf.wrap(new byte[] { b }));
        }

        EmbeddedTransfer transfer = context.joinChannel(client, server);
        transfer.transferToServer();
        assert server.getRcvQueueSize() == 5;

        coderTest1_case2(server);
    }
}