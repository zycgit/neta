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
package net.hasor.neta.channel;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtil;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SocketServerTest {
    public static void main(String[] args) throws Exception {
        SoConfig config = new SoConfig();
        config.setSwapBuf(64, 64);
        config.setLocalBuf(32, 32);
        //        config.setSoReadTimeoutMs(6000);
        //        config.setSoKeepAlive(true);
        //        config.setSoKeepIntervalSec(10);
        //        config.setSoKeepIdleSec(10);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        //
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(1);
        config.setTaskThreads(1);

        try (CobbleSocket socket = new CobbleSocket(config)) {
            NetListen listen = socket.listen("127.0.0.1", 5567, pipeCtx -> new PipeStack<Object, Object>() {
                @Override
                public Object[] rcvLayer(PipeContext pipeContext, Object rcvData) {
                    ByteBuf rcvByteBuf = (ByteBuf) rcvData;
                    String line = rcvByteBuf.readLine();
                    rcvByteBuf.markReader();

                    if (line != null) {
                        ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + line + "\n").getBytes());
                        System.out.println("rcvChannel " + pipeContext.channel().getChannelID() + ", data=" + line);

                        return new ByteBuf[] { buf };
                    }
                    return new ByteBuf[0];
                }

                @Override
                public Object[] sndLayer(PipeContext pipeContext, Object sndData) {
                    ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(("echo " + sndData + "\n").getBytes());
                    return new ByteBuf[] { buf };
                }
            });
            read(listen);
        }
    }

    private static void read(NetListen netListen) {
        try {
            System.out.println("10s after suspend.");
            ThreadUtils.sleep(10000);
            netListen.suspend();

            System.out.println("5s after resume.");
            ThreadUtils.sleep(5000);
            netListen.resume();

            System.out.println("resume.");
            System.in.read();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}