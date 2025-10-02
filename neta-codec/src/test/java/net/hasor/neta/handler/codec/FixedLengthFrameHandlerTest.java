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
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.channel.ProtoHelper;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class FixedLengthFrameHandlerTest {

    @Test
    public void asEncoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextEncoder("", handler).build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asEncoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextEncoder("", handler).build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asEncoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextEncoder("", handler).build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asEncoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextEncoder("", handler).build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.sendData(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asDecoder_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextDecoder("", handler).build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // send data
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }

    @Test
    public void asDecoder_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextDecoder("", handler).build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asDecoder_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextDecoder("", handler).build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        assert rcvData.size() == 1;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
    }

    @Test
    public void asDecoder_4() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            FixedLengthFrameHandler handler = new FixedLengthFrameHandler(10);
            return ProtoHelper.standard().nextDecoder("", handler).build();
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            return ProtoHelper.standard().build();
        }, VrtSoConfig.asClient());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        client.sendData(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        client.sendData(ByteBuf.wrap(new byte[] { 5, 6, 7, 8 }));
        client.sendData(ByteBuf.wrap(new byte[] { 9, 10, 11, 12, 13, 14 }));
        client.sendData(ByteBuf.wrap(new byte[] { 15, 16 }));
        client.sendData(ByteBuf.wrap(new byte[] { 17, 18, 19, 20, 21, 22 }));
        assert rcvData.size() == 2;
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        assert Objects.deepEquals(rcvData.poll().asByteArray(), new byte[] { 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 });
    }
}