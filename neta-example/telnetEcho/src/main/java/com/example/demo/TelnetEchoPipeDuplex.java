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
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.*;

/**
 *
 * @version : 2014年7月11日
 * @author 赵永春 (zyc@hasor.net)
 */
public class TelnetEchoPipeDuplex implements PipeHandler<String, String> {
    private static final String CTRL_C = new String(new byte[] { -17, -65, -67, -17, -65, -67, -17, -65, -67, -17, -65, -67, 6 });

    @Override
    public void onInit(PipeContext context) {
        System.out.println("onInit");
    }

    @Override
    public void onActive(PipeContext context) {
        System.out.println("onActive");
    }

    @Override
    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
        NetChannel channel = (NetChannel) context.getChannel();
        while (src.hasMore()) {
            String data = src.takeMessage();
            if (CTRL_C.equals(data)) {
                channel.sendData("bye.\n").onFinal(f -> channel.close());
            } else {
                channel.sendData("echo " + data + "\n");
            }
        }

        return PipeStatus.Next;
    }

    @Override
    public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
        System.out.println("onError");
        return PipeStatus.Next;
    }

    @Override
    public void onClose(PipeContext context) {
        System.out.println("onClose");
    }
}
