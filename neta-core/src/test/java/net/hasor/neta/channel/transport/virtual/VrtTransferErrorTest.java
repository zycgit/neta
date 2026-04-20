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

import java.net.SocketException;

import org.junit.Test;

import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoInitializer;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class VrtTransferErrorTest {
    @Test
    public void error1() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.standard().build();

        // server and client
        NetManager neta = new NetManager();
        VrtChannel channel1 = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());
        VrtChannel channel2 = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());

        // transfer channel
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(channel1, channel2, VrtTransfer.direct());

        try {
            transfer.linkTo(channel1, channel2, VrtTransfer.direct());
            assert false;
        } catch (SocketException e) {
            assert e.getMessage().equals("link " + channel1.getChannelId() + " -> " + channel2.getChannelId() + " already exists");
        }

        neta.shutdown();
    }

    @Test
    public void error2() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.standard().build();

        // server and client
        NetManager neta1 = new NetManager();
        NetManager neta2 = new NetManager();
        VrtChannel channel1 = (VrtChannel) neta1.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());
        VrtChannel channel2 = (VrtChannel) neta2.connectSync(new VrtSocketAddress(2), initializer, VrtSoConfig.asDefault());

        try {
            VrtTransfer transfer = new VrtTransfer(neta1);
            transfer.linkTo(channel1, channel2, VrtTransfer.direct());
            assert false;
        } catch (SocketException e) {
            assert e.getMessage().equals("channels need same NetaManager");
        }

        neta1.shutdown();
        neta2.shutdown();
    }

    @Test
    public void error2_1() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.standard().build();

        // server and client
        NetManager neta1 = new NetManager();
        NetManager neta2 = new NetManager();
        VrtChannel channel1 = (VrtChannel) neta1.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());
        VrtChannel channel2 = (VrtChannel) neta1.connectSync(new VrtSocketAddress(2), initializer, VrtSoConfig.asDefault());

        try {
            VrtTransfer transfer = new VrtTransfer(neta2);
            transfer.linkTo(channel1, channel2, VrtTransfer.direct());
            assert false;
        } catch (SocketException e) {
            assert e.getMessage().equals("channels and VrtTransfer need same NetaManager.");
        }

        neta1.shutdown();
        neta2.shutdown();
    }

    @Test
    public void error3() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.standard().build();

        // server and client
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());

        try {
            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(channel, channel, VrtTransfer.direct());
            assert false;
        } catch (SocketException e) {
            assert e.getMessage().equals("cannot create self link");
        }

        neta.shutdown();
    }
}