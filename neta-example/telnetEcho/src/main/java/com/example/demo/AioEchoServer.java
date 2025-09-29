/*
 * Copyright 2015-2022 the original author or authors.
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
package com.example.demo;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.handler.PlayLoad;
import net.hasor.neta.handler.ProtoHelper;
import net.hasor.neta.handler.codec.LineBasedFrameHandler;
import net.hasor.neta.handler.codec.string.StringHandler;

import java.net.InetSocketAddress;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年7月11日
 */
public class AioEchoServer {

    public static void main(String[] args) throws Throwable {
        // telnet protocol stack
        ProtoInitializer initializer = ctx -> ProtoHelper.standard()
                //split according to \r\n, max line is 4K
                .nextDecoder("max length", new LineBasedFrameHandler(4096, false))
                // encoder/decoder string
                .nextDuplex("string", new StringHandler())
                // Build ProtoStack
                .build();

        // telnet server
        NetManager socket = new NetManager();
        socket.bind(new InetSocketAddress("127.0.0.1", 5567), initializer, SoConfig.TCP());

        // echo any message to client
        socket.getContext().subscribe(PlayLoad::isInbound, AioEchoServer::echoMessage);

        // wait.
        System.in.read();
    }

    private static final String CTRL_C = new String(new byte[] { -17, -65, -67, -17, -65, -67, -17, -65, -67, -17, -65, -67, 6 });

    private static void echoMessage(PlayLoad data) {
        if (data.isSuccess()) {
            NetChannel channel = (NetChannel) data.getSource();
            String str = (String) data.getData();
            if (CTRL_C.equals(str)) {
                channel.sendData("bye.\n").onFinal(f -> channel.close());
            } else {
                channel.sendData("echo " + str + "\n");
            }
        } else {
            System.out.println("onError");
        }
    }
}