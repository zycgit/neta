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
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtil;
import net.hasor.neta.handler.PipeInitializer;
import net.hasor.neta.handler.ssl.SslAuthKeyType;
import net.hasor.neta.handler.ssl.SslConfig;
import net.hasor.neta.handler.ssl.SslPipeLayer;
import net.hasor.neta.handler.ssl.SslProtocol;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslSocketTest {
    @Test
    public void realSSLSocketTest() throws Exception {
        LoggerFactory.useStdOutLogger();
        // socket config.
        SoConfig config = new SoConfig();
        config.setNetlog(true);
        config.setSwapBuf(64, 64);
        config.setLocalBuf(128, 128);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(1);
        config.setTaskThreads(1);

        // ssl config.
        SslConfig sslConfig = new SslConfig();
        sslConfig.setSsllog(true);
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setAppProtocol(new String[] { "SPDY", "HTTP1.1", "HTTP2" });

        // network stack.
        PipeStackFactory pipeStack = new PipeInitializer()
                // SSL
                .nextTo(new SslPipeLayer(sslConfig))
                // receive
                .bindReceive(SslSocketTest::readLine)
                // build
                .buildFactory();

        // test.
        try (CobbleSocket socket = new CobbleSocket(config)) {
            NetListen listen = startListen(socket, pipeStack);
            AtomicReference<NetChannel> serverChannelRef = new AtomicReference<>();
            listen.addListener(new NetListener() {
                @Override
                public void accept(NetChannel channel) {
                    serverChannelRef.set(channel);
                }

                @Override
                public void close(NetChannel channel) {
                    serverChannelRef.set(null);
                }
            });

            NetChannel clientChannel = connectTo(socket, pipeStack);
            listen.waitAnyAccept();

            clientChannel.sendData(ByteBufAllocator.DEFAULT.wrap("Hello Server\n".getBytes()));
            NetChannel serverChannel = serverChannelRef.get();

            listen.waitIdle();

            assert serverChannel.getAttribute("Message").equals("Hello Server");
        }
    }

    private static NetListen startListen(CobbleSocket socket, PipeStackFactory pipeStack) throws Exception {
        return socket.listen("127.0.0.1", 5567, pipeStack);
    }

    private static NetChannel connectTo(CobbleSocket socket, PipeStackFactory pipeStack) throws Exception {
        return socket.connect("127.0.0.1", 5567, pipeStack).get();
    }

    private static void readLine(SoChannel<?> channel, ByteBuf rcvByteBuf) {
        String line = rcvByteBuf.readLine();
        rcvByteBuf.markReader();

        System.out.println("rcvChannel " + channel.getChannelID() + ", data=" + line);

        channel.setAttribute("Message", line);
        channel.close();
    }
}