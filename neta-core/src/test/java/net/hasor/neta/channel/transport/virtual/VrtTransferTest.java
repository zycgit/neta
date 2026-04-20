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
package net.hasor.neta.channel.transport.virtual;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class VrtTransferTest {
    @Test
    public void echoTest_1() throws Throwable {
        ProtoInitializer serverProto = ProtoHelper.typed(String.class, String.class).nextDecoder(new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
                while (src.hasMore()) {
                    context.sendData("Echo " + src.takeMessage());
                }
                return ProtoStatus.Stop;
            }
        }).build();
        ProtoInitializer clientProto = ProtoHelper.standard().build();

        // server and client
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverProto, VrtSoConfig.asDefault());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), clientProto, VrtSoConfig.asDefault());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.direct());
        transfer.linkTo(server, client, VrtTransfer.direct());

        //
        List<String> rcv = new ArrayList<>();
        client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> rcv.add((String) d.getData()));

        client.sendData("Hello Vrt");
        assert rcv.get(0).equals("Echo Hello Vrt");

        neta.shutdown();
    }

    @Test
    public void echoTest_2() throws Throwable {
        ProtoInitializer serverProto = ProtoHelper.typed(String.class, String.class).nextDuplex(new ProtoDuplexer<String, String, String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv,  //
                    ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown,//
                    ProtoRcvQueue<String> sndUp, ProtoSndQueue<String> sndDown) {
                if (isRcv) {
                    while (rcvUp.hasMore()) {
                        sndDown.offerMessage("Echo " + rcvUp.takeMessage());
                    }
                }
                return ProtoStatus.Next;
            }
        }).build();
        ProtoInitializer clientProto = ProtoHelper.standard().build();

        // server and client
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverProto, VrtSoConfig.asDefault());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), clientProto, VrtSoConfig.asDefault());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.direct());
        transfer.linkTo(server, client, VrtTransfer.direct());

        //
        List<String> rcv = new ArrayList<>();
        client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> rcv.add((String) d.getData()));

        client.sendData("Hello Vrt");
        assert rcv.get(0).equals("Echo Hello Vrt");

        neta.shutdown();
    }
}