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

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LimitFrameHandlerTest {

    @Test
    public void asEncoder_1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asEncoder_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asEncoder_3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 2;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asEncoder_4() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 6;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;
        assert buf1.getByte(4) == 5;
        assert buf1.getByte(5) == 6;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 7;
        assert buf2.getByte(1) == 8;
        assert buf2.getByte(8) == 15;
        assert buf2.getByte(9) == 16;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 10;
        assert buf3.getByte(0) == 17;
        assert buf3.getByte(1) == 18;
        assert buf3.getByte(8) == 25;
        assert buf3.getByte(9) == 26;
    }

    @Test
    public void asEncoder_5() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 2;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asEncoder_6() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 1;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;
    }

    @Test
    public void asEncoder_7() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 4;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 5;
        assert buf2.getByte(1) == 6;
        assert buf2.getByte(4) == 9;
        assert buf2.getByte(5) == 10;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 4;
        assert buf3.getByte(0) == 11;
        assert buf3.getByte(1) == 12;
        assert buf3.getByte(2) == 13;
        assert buf3.getByte(3) == 14;
    }

    @Test
    public void asDecoder_1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 2;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asDecoder_4() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 6;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;
        assert buf1.getByte(4) == 5;
        assert buf1.getByte(5) == 6;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 7;
        assert buf2.getByte(1) == 8;
        assert buf2.getByte(8) == 15;
        assert buf2.getByte(9) == 16;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 10;
        assert buf3.getByte(0) == 17;
        assert buf3.getByte(1) == 18;
        assert buf3.getByte(8) == 25;
        assert buf3.getByte(9) == 26;
    }

    @Test
    public void asDecoder_4_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_5() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 2;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asDecoder_6() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 1;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;
    }

    @Test
    public void asDecoder_7() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 3, 4 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        transfer.transferToServer();
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 3;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 4;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 5;
        assert buf2.getByte(1) == 6;
        assert buf2.getByte(4) == 9;
        assert buf2.getByte(5) == 10;

        ByteBuf buf3 = (ByteBuf) server.readRcv();
        assert buf3.readableBytes() == 4;
        assert buf3.getByte(0) == 11;
        assert buf3.getByte(1) == 12;
        assert buf3.getByte(2) == 13;
        assert buf3.getByte(3) == 14;
    }

    @Test
    public void asDecoder_7_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);

        client.send(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.send(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.send(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.send(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        transfer.transferToServer();

        assert server.getRcvQueueSize() == 2;

        ByteBuf buf1 = (ByteBuf) server.readRcv();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = (ByteBuf) server.readRcv();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(4) == 15;
        assert buf2.getByte(5) == 16;
    }
}