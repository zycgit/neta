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
package net.hasor.neta.codec;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class FixedLengthFrameHandlerTest {
    private static byte[] pollBytes(Queue<ByteBuf> rcvData) {
        ByteBuf buf = rcvData.poll();
        if (buf == null) {
            return null;
        }
        try {
            return buf.asByteArray();
        } finally {
            buf.free();
        }
    }

    private static byte pollFirstByte(Queue<ByteBuf> rcvData) {
        ByteBuf buf = rcvData.poll();
        if (buf == null) {
            throw new AssertionError("expected frame but queue is empty");
        }
        try {
            return buf.getByte(0);
        } finally {
            buf.free();
        }
    }

    @Test
    public void asEncoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asEncoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asEncoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asEncoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.sendData(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asDecoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // send data
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asDecoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asDecoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asDecoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.sendData(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    // ===========================================
    // Data exactly equals fixedLength
    // ===========================================

    @Test
    public void asDecoder_exactLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(5);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // exactly fixedLength -> 1 frame
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(pollBytes(rcvData), new byte[] { 1, 2, 3, 4, 5 });
    }

    // ===========================================
    // Data less than fixedLength
    // ===========================================

    @Test
    public void asDecoder_lessThanLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // less than fixedLength -> no frame
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3 }));
        assert rcvData.isEmpty();
    }

    // ===========================================
    // fixedLength = 1
    // ===========================================

    @Test
    public void asDecoder_singleByte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(1);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        client.sendData(ByteBuf.wrap(new byte[] { 10, 20, 30 }));
        assert rcvData.size() == 3;
        assert pollFirstByte(rcvData) == 10;
        assert pollFirstByte(rcvData) == 20;
        assert pollFirstByte(rcvData) == 30;
    }
}