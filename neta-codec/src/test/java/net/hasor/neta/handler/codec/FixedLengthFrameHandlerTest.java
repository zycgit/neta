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
public class FixedLengthFrameHandlerTest {

    @Test
    public void asEncoder_1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asEncoder_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asEncoder_3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.send(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asEncoder_4() {
        EmbeddedInitializer serverInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextEncoder("", handler).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.send(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.send(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asDecoder_1() {
        EmbeddedInitializer serverInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asDecoder_2() {
        EmbeddedInitializer serverInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asDecoder_3() {
        EmbeddedInitializer serverInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.send(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
    public void asDecoder_4() {
        EmbeddedInitializer serverInitializer = ctx -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).nextDecoder("", handler).build();
        };
        EmbeddedInitializer clientInitializer = ctx -> {
            return ProtoHelper.embedded(ByteBuf.class, ByteBuf.class).build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, serverInitializer, context);
        EmbeddedChannel client = new EmbeddedChannel(false, clientInitializer, context);

        client.send(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.send(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.send(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.send(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.send(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));

        EmbeddedTransfer transfer = context.joinChannel(client, server);
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
}