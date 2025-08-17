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
package net.hasor.neta.handler.codec.ssl.tcp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.handler.codec.ssl.*;
import org.junit.Test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hasor.neta.handler.codec.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TcpJvm2NetaTest extends AbstractSslTest {
    @Test
    public void jvm_2_neta() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);

        // server
        List<String> rcvMessage = new ArrayList<>();
        ProtoInitializer serverProto = SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(rcvMessage));
        NetManager neta = new NetManager(globalConf());
        neta.listen(address, serverProto, tcpConfig(128, 4096));

        // client
        AtomicBoolean writeFinish = new AtomicBoolean();
        ThreadUtils.daemonThread(true, (Callable) () -> {
            try {
                SSLSocketFactory socketFactory = SoSslUtils.sslContext(SslProtocol.TLS_v1_2).getSocketFactory();
                SSLSocket socket = (SSLSocket) socketFactory.createSocket("127.0.0.1", safePort);
                OutputStream out = socket.getOutputStream();
                out.write("Hello Server, this message form client.\n".getBytes());
                out.flush();

                out.close();
                socket.close();
            } finally {
                writeFinish.set(true);
            }
        });

        // wait finish
        while (!writeFinish.get()) {
            ThreadUtils.sleep(100);
        }
        assert rcvMessage.get(0).equals("Hello Server, this message form client.");

        neta.shutdown();
    }
}