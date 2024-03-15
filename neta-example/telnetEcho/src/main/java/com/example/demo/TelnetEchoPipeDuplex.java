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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.*;

/**
 *
 * @version : 2014年7月11日
 * @author 赵永春 (zyc@hasor.net)
 */
public class TelnetEchoPipeDuplex implements ProtoHandler<String, String> {
    private static final String CTRL_C = new String(new byte[] { -17, -65, -67, -17, -65, -67, -17, -65, -67, -17, -65, -67, 6 });

    @Override
    public void onInit(ProtoContext context) {
        System.out.println("onInit");
    }

    @Override
    public void onActive(ProtoContext context) {
        System.out.println("onActive");
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
        NetChannel channel = (NetChannel) context.getChannel();
        while (src.hasMore()) {
            String data = src.takeMessage();
            if (CTRL_C.equals(data)) {
                channel.sendData("bye.\n").onFinal(f -> channel.close());
            } else {
                channel.sendData("echo " + data + "\n");
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
        System.out.println("onError");
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        System.out.println("onClose");
    }
}
