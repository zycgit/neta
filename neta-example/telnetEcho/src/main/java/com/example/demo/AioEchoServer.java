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
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.handler.ProtoHelper;
import net.hasor.neta.handler.codec.LineBasedFrameHandler;
import net.hasor.neta.handler.codec.string.StringHandler;

import java.io.IOException;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年7月11日
 */
public class AioEchoServer {
    public static void main(String[] args) throws IOException {
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder()
                    //split according to \r\n, max line is 4K
                    .nextDecoder("max length", new LineBasedFrameHandler(4096, false))
                    // encoder/decoder string
                    .nextDuplex("string", new StringHandler())
                    // echo any message to client
                    .nextDecoder("echo", new TelnetEchoPipeDuplex())
                    // Build ProtoStack
                    .build();
        };

        NetManager socket = new NetManager(new SoConfig());
        socket.listen("127.0.0.1", 5567, initializer);

        System.in.read();
    }

    //    private static void read(NetListen netListen) {
    //        try {
    //            System.out.println("10s after suspend.");
    //            ThreadUtils.sleep(10000);
    //            netListen.suspend();
    //
    //            System.out.println("5s after resume.");
    //            ThreadUtils.sleep(5000);
    //            netListen.resume();
    //
    //            System.out.println("resume.");
    //            System.in.read();
    //        } catch (Exception e) {
    //            throw new RuntimeException(e);
    //        }
    //    }
}