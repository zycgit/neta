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
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LimitFrameHandlerTest {

    @Test
    public void asEncoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asEncoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asEncoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asEncoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 6;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;
        assert buf1.getByte(4) == 5;
        assert buf1.getByte(5) == 6;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 7;
        assert buf2.getByte(1) == 8;
        assert buf2.getByte(8) == 15;
        assert buf2.getByte(9) == 16;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 10;
        assert buf3.getByte(0) == 17;
        assert buf3.getByte(1) == 18;
        assert buf3.getByte(8) == 25;
        assert buf3.getByte(9) == 26;
    }

    @Test
    public void asEncoder_5() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asEncoder_6() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.sendData(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.sendData(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;
    }

    @Test
    public void asEncoder_7() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.sendData(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.sendData(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 4;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 5;
        assert buf2.getByte(1) == 6;
        assert buf2.getByte(4) == 9;
        assert buf2.getByte(5) == 10;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 4;
        assert buf3.getByte(0) == 11;
        assert buf3.getByte(1) == 12;
        assert buf3.getByte(2) == 13;
        assert buf3.getByte(3) == 14;
    }

    @Test
    public void asDecoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asDecoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 6;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;
        assert buf1.getByte(4) == 5;
        assert buf1.getByte(5) == 6;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 7;
        assert buf2.getByte(1) == 8;
        assert buf2.getByte(8) == 15;
        assert buf2.getByte(9) == 16;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 10;
        assert buf3.getByte(0) == 17;
        assert buf3.getByte(1) == 18;
        assert buf3.getByte(8) == 25;
        assert buf3.getByte(9) == 26;
    }

    @Test
    public void asDecoder_4_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));
        transfer.setBatchSize(3);

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 6;
        assert buf3.getByte(0) == 21;
        assert buf3.getByte(1) == 22;
        assert buf3.getByte(4) == 25;
        assert buf3.getByte(5) == 26;
    }

    @Test
    public void asDecoder_5() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 10;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(8) == 19;
        assert buf2.getByte(9) == 20;
    }

    @Test
    public void asDecoder_6() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(10, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.sendData(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.sendData(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;
    }

    @Test
    public void asDecoder_7() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.sendData(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.sendData(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 3;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 4;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(2) == 3;
        assert buf1.getByte(3) == 4;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 5;
        assert buf2.getByte(1) == 6;
        assert buf2.getByte(4) == 9;
        assert buf2.getByte(5) == 10;

        ByteBuf buf3 = rcvData.poll();
        assert buf3.readableBytes() == 4;
        assert buf3.getByte(0) == 11;
        assert buf3.getByte(1) == 12;
        assert buf3.getByte(2) == 13;
        assert buf3.getByte(3) == 14;
    }

    @Test
    public void asDecoder_7_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(4, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));
        transfer.setBatchSize(6);

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2 }));
        client.sendData(ByteBuf.wrap(new byte[] { 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6 }));
        client.sendData(ByteBuf.wrap(new byte[] { 7, 8, 9, 10 }));
        client.sendData(ByteBuf.wrap(new byte[] { 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 2;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(1) == 2;
        assert buf1.getByte(8) == 9;
        assert buf1.getByte(9) == 10;

        ByteBuf buf2 = rcvData.poll();
        assert buf2.readableBytes() == 6;
        assert buf2.getByte(0) == 11;
        assert buf2.getByte(1) == 12;
        assert buf2.getByte(4) == 15;
        assert buf2.getByte(5) == 16;
    }

    // ===========================================
    // Constructor validation tests
    // ===========================================

    @Test(expected = IllegalArgumentException.class)
    public void constructor_minGreaterThanMax() {
        new LimitFrameHandler(10, 5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_minLessThanOne() {
        new LimitFrameHandler(0, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_negativeMin() {
        new LimitFrameHandler(-1, 10);
    }

    // ===========================================
    // Exact size tests
    // ===========================================

    @Test
    public void asDecoder_exactMaxLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // data exactly equals maxLength -> 1 frame
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }));
        assert rcvData.size() == 1;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 10;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(9) == 10;
    }

    @Test
    public void asDecoder_exactMinLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // data exactly equals minLength -> 1 frame
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 }));
        assert rcvData.size() == 1;

        ByteBuf buf1 = rcvData.poll();
        assert buf1.readableBytes() == 5;
        assert buf1.getByte(0) == 1;
        assert buf1.getByte(4) == 5;
    }

    @Test
    public void asDecoder_belowMinLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(5, 10);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // data below minLength -> no frames
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        assert rcvData.isEmpty();
    }

    // ===========================================
    // Single byte frame tests
    // ===========================================

    @Test
    public void asDecoder_singleByteFrames() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 1);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // min=1, max=1 -> each byte becomes a frame
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3 }));
        assert rcvData.size() == 3;

        assert rcvData.poll().getByte(0) == 1;
        assert rcvData.poll().getByte(0) == 2;
        assert rcvData.poll().getByte(0) == 3;
    }

    @Test
    public void asEncoder_singleByteFrames() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(1, 1);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3 }));
        assert rcvData.size() == 3;

        assert rcvData.poll().getByte(0) == 1;
        assert rcvData.poll().getByte(0) == 2;
        assert rcvData.poll().getByte(0) == 3;
    }

    // ===========================================
    // min equals max with non-divisible data
    // ===========================================

    @Test
    public void asDecoder_minEqualsMax_nonDivisible() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LimitFrameHandler handler = new LimitFrameHandler(3, 3);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().build().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // 7 bytes with fixedLength 3 -> 2 frames (3+3), 1 byte left over
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7 }));
        assert rcvData.size() == 2;

        ByteBuf f1 = rcvData.poll();
        assert f1.readableBytes() == 3;
        assert f1.getByte(0) == 1;
        assert f1.getByte(2) == 3;

        ByteBuf f2 = rcvData.poll();
        assert f2.readableBytes() == 3;
        assert f2.getByte(0) == 4;
        assert f2.getByte(2) == 6;
    }
}