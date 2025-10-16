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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LineBasedFrameHandlerTest {
    @Test
    public void lineBasedFrame_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
            ProtoHelper.standard().nextDecoder(lineBasedFrame).build(ctx);
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc".getBytes()));
        assert rcvData.isEmpty();
        channel.onReceive(ByteBuf.wrap("\r\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc\r\n");
    }

    @Test
    public void lineBasedFrame_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
            ProtoHelper.standard().nextDecoder(lineBasedFrame).build(ctx);
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc\r\n123".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc\r\n");
    }

    @Test
    public void lineBasedFrame_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LineBasedFrameHandler lineBasedFrame = new LineBasedFrameHandler();
            ProtoHelper.standard().nextDecoder(lineBasedFrame).build(ctx);
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc\r\n123".getBytes()));
        channel.onReceive(ByteBuf.wrap("\r\n".getBytes()));
        assert rcvData.size() == 2;
        assert new String(rcvData.poll().asByteArray()).equals("abc\r\n");
        assert new String(rcvData.poll().asByteArray()).equals("123\r\n");
    }
}