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
import java.net.InetSocketAddress;
import net.hasor.neta.channel.*;
import net.hasor.neta.handler.codec.LineBasedFrameHandler;
import net.hasor.neta.handler.codec.string.StringHandler;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年7月11日
 */
public class AioEchoServer {

    public static void main(String[] args) throws Throwable {
        // telnet protocol stack
        ProtoInitializer initializer = ctx -> {
            //split according to \r\n, max line is 4K
            ctx.addLastDecoder("max length", new LineBasedFrameHandler(4096, false));
            // encoder/decoder string
            ctx.addLast("string", new StringHandler());
        };

        // telnet server
        NetManager socket = new NetManager();
        socket.bind(new InetSocketAddress(5567), initializer, SoConfig.TCP());

        // echo any message to client
        String CTRL_C = new String(new byte[] { -17, -65, -67, -17, -65, -67, -17, -65, -67, -17, -65, -67, 6 });
        socket.subscribe(PlayLoad::isInbound, playload -> {
            NetChannel channel = (NetChannel) playload.getSource();
            String str = (String) playload.getData();
            if (CTRL_C.equals(str)) {
                channel.sendData("bye.\n").onFinal(f -> channel.close());
            } else {
                channel.sendData("echo " + str + "\n");
            }
        });

        // wait.
        System.in.read();
    }
}