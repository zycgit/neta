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
import net.hasor.neta.channel.CobbleSocket;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.PipelineFactory;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.handler.PipeInitializer;
import net.hasor.neta.handler.PipeListener;
import net.hasor.neta.handler.codec.LineBasedFrameHandler;
import net.hasor.neta.handler.codec.string.StringPipeLayer;

import java.io.IOException;

/**
 *
 * @version : 2014年7月11日
 * @author 赵永春 (zyc@hasor.net)
 */
public class AioEchoServer {
    private static final String CTRL_C = new String(new byte[] { -17, -65, -67, -17, -65, -67, -17, -65, -67, -17, -65, -67, 6 });

    public static void main(String[] args) throws IOException {
        PipelineFactory pipeline = PipeInitializer.builder()
                //split according to \r\n, max line is 4K
                .nextToDecoder(new LineBasedFrameHandler(4096, false))
                // encoder/decoder string
                .nextTo(new StringPipeLayer())
                // echo any message to client
                .bindReceive((PipeListener<String>) (channel, data) -> {
                    if (CTRL_C.equals(data)) {
                        ((NetChannel) channel).sendData("bye.").onFinal(f -> channel.close());
                    } else {
                        ((NetChannel) channel).sendData("echo " + data + "\n");
                    }
                }).build();

        CobbleSocket socket = new CobbleSocket(new SoConfig());
        socket.listen("127.0.0.1", 5567, pipeline);

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