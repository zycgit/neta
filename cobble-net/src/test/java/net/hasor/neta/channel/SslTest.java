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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtil;
import net.hasor.neta.handler.ssl.*;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslTest {
    @Test
    public void main() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ThreadFactory tf1 = ThreadUtils.threadFactory(loader, "IO-Thread-%s", true);
        ThreadFactory tf2 = ThreadUtils.threadFactory(loader, "WORK-Thread-%s", true);

        // 监听处理线程
        SoConfig config = new SoConfig();
        config.setSwapBuf(2, 2);
        config.setLocalBuf(128, 128);
        //        config.setSoReadTimeoutMs(6000);
        //        config.setSoKeepAlive(true);
        //        config.setSoKeepIntervalSec(10);
        //        config.setSoKeepIdleSec(10);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        config.setIoExecutor(Executors.newFixedThreadPool(1, tf1));
        config.setTaskExecutorFactory((cfg, ctxName) -> Executors.newFixedThreadPool(1, tf2));
        //
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setAppProtocol(new String[] { "RSF1.1", "HTTP1.1", "HTTP2" });
        //        sslConfig.setAppProtocolSelector(new SslAppProtocolSelector() {
        //            @Override
        //            public String apply(SSLEngine sslEngine, List<String> strings) {
        //                return null;
        //            }
        //        });
        config.setSslConfig(sslConfig);

        try (CobbleSocket server = new CobbleSocket(config)) {
            SoContext context = server.getContext();
            testSsl(context, context.getConfig().getSslConfig());
        }
    }

    private static void testSsl(SoContext context, SslConfig config) throws Exception {
        ByteBuffer swap = ByteBuffer.allocate(1024);   // 2Byte
        SoResManager rm = context.getResourceManager();     // default rm

        // client
        ByteBuf clientRcvUpstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf clientRcvDownstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf clientSndUpstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf clientSndDownstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        SslContextBasic clientContext = new JdkSslContext(1, context, config, rm, true);

        // server
        ByteBuf serverRcvUpstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf serverRcvDownstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf serverSndUpstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        ByteBuf serverSndDownstream = ByteBufAllocator.DEFAULT.arrayBuffer();
        SslContextBasic serverContext = new JdkSslContext(2, context, config, rm, false);

        //
        String serverMsg = "Hello Client, this message form server.";
        String clientMsg = "Hello Server, this message form client.";
        clientSndUpstream.writeString(clientMsg + "\n", StandardCharsets.US_ASCII);
        serverSndUpstream.writeString(serverMsg + "\n", StandardCharsets.US_ASCII);
        clientSndUpstream.markWriter();
        serverSndUpstream.markWriter();

        for (int i = 0; i < 10; i++) {
            System.out.println("trun " + (i++));
            // client -> server
            clientContext.handRcv(clientRcvUpstream, clientRcvDownstream, clientSndUpstream, clientSndDownstream);
            clientContext.handSnd(clientRcvUpstream, clientRcvDownstream, clientSndUpstream, clientSndDownstream);
            while (clientSndDownstream.hasReadable()) {
                swap.clear();
                clientSndDownstream.read(swap);
                clientSndDownstream.markReader();

                swap.flip();
                serverRcvUpstream.write(swap);
                serverRcvUpstream.markWriter();
            }

            // server -> client
            serverContext.handRcv(serverRcvUpstream, serverRcvDownstream, serverSndUpstream, serverSndDownstream);
            serverContext.handSnd(serverRcvUpstream, serverRcvDownstream, serverSndUpstream, serverSndDownstream);
            while (serverSndDownstream.hasReadable()) {
                swap.clear();
                serverSndDownstream.read(swap);
                serverSndDownstream.markReader();

                swap.flip();
                clientRcvUpstream.write(swap);
                clientRcvUpstream.markWriter();
            }
        }

        String clientRcv = clientRcvDownstream.readLine();
        String serverRcv = serverRcvDownstream.readLine();
        assert StringUtils.equals(clientRcv, serverMsg) && StringUtils.equals(serverRcv, clientMsg);
    }
}