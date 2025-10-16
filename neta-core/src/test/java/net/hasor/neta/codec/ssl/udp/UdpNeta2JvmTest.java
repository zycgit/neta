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
package net.hasor.neta.codec.ssl.udp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import net.hasor.neta.codec.ssl.AbstractSslTest;
import net.hasor.neta.codec.ssl.SoSslUtils;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;

import static net.hasor.neta.codec.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpNeta2JvmTest extends AbstractSslTest {

    private static void sslHandshake(SSLEngine sslEngine, DatagramChannel udpServer) throws IOException {
        SocketAddress address = null;
        SSLSession sslSession = sslEngine.getSession();
        ByteBuffer sslOutAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslOutNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());
        ByteBuffer sslInAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslInNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());

        int i = 0;
        sslEngine.beginHandshake();
        while (true) {
            i++;
            SSLEngineResult.HandshakeStatus result = sslEngine.getHandshakeStatus();
            if (result.name().equals("NEED_UNWRAP")) {
                sslInNetBuffer.clear();
                address = udpServer.receive(sslInNetBuffer);
                sslInNetBuffer.flip();
                result = sslEngine.unwrap(sslInNetBuffer, sslInAppBuffer).getHandshakeStatus();
            } else if (result.name().equals("NEED_UNWRAP_AGAIN")) {
                result = sslEngine.unwrap(sslInNetBuffer, sslInAppBuffer).getHandshakeStatus();
            } else if (result.name().equals("NEED_WRAP")) {
                sslOutNetBuffer.clear();
                SSLEngineResult wrap = sslEngine.wrap(sslOutAppBuffer, sslOutNetBuffer);
                if (wrap.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    sslOutNetBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
                    continue;
                }

                result = wrap.getHandshakeStatus();
                sslOutNetBuffer.flip();
                udpServer.send(sslOutNetBuffer, address);
            } else if (result.name().equals("NEED_TASK")) {
                sslEngine.getDelegatedTask().run();
                result = sslEngine.getHandshakeStatus();
            }

            if (result.name().equals("FINISHED")) {
                return;
            }
        }
    }

    @Test
    public void jvm_2_neta() throws Exception {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.DTLS_v1_2);

        // server
        DatagramChannel udpServer = DatagramChannel.open();
        udpServer.bind(address);
        udpServer.configureBlocking(true);

        // client
        List<String> rcvMessage = new ArrayList<>();
        ProtoInitializer serverProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(rcvMessage));
        NetManager neta = new NetManager(globalConf());
        NetChannel channel = neta.connectSync(address, serverProto, udpConfig(4096, 4096));

        // ssl
        SSLContext sslContext = SoSslUtils.sslContext(SslProtocol.DTLS_v1_2);
        SSLEngine sslEngine = sslContext.createSSLEngine("127.0.0.1", safePort);
        sslEngine.setUseClientMode(false);
        ThreadUtils.sleep(300);
        sslHandshake(sslEngine, udpServer);

        // snd data
        channel.sendData("Hello Server, this message form client.\n");

        // readData
        SSLSession sslSession = sslEngine.getSession();
        ByteBuffer sslInAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslInNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());

        udpServer.receive(sslInNetBuffer);
        sslInNetBuffer.flip();
        sslEngine.unwrap(sslInNetBuffer, sslInAppBuffer).getHandshakeStatus();

        sslInAppBuffer.flip();
        ByteBuf wrap = ByteBuf.wrap(sslInAppBuffer);
        assert wrap.readLine().equals("Hello Server, this message form client.");
        neta.shutdown();
        udpServer.close();
    }
}