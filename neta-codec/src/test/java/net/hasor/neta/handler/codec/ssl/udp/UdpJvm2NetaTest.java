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
package net.hasor.neta.handler.codec.ssl.udp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.handler.codec.ssl.*;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;

import static net.hasor.neta.handler.codec.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpJvm2NetaTest extends AbstractSslTest {

    @Test
    public void jvm_2_neta() throws Exception {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.DTLS_v1_2);

        // server
        List<String> rcvMessage = new ArrayList<>();
        ProtoInitializer serverProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(rcvMessage));
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, serverProto, udpConfig(4096, 4096));

        // client
        DatagramChannel udpClient = DatagramChannel.open();
        udpClient.connect(address);

        // ssl
        SSLContext sslContext = SoSslUtils.sslContext(SslProtocol.DTLS_v1_2);
        SSLEngine sslEngine = sslContext.createSSLEngine("127.0.0.1", safePort);
        sslEngine.setUseClientMode(true);
        sslHandshake(sslEngine, udpClient, address);

        // snd data
        SSLSession sslSession = sslEngine.getSession();
        ByteBuffer sslOutAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslOutNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());
        sslOutAppBuffer.put("Hello Server, this message form client.\n".getBytes());
        sslOutAppBuffer.flip();
        sslEngine.wrap(sslOutAppBuffer, sslOutNetBuffer).getHandshakeStatus();
        sslOutNetBuffer.flip();
        udpClient.send(sslOutNetBuffer, address);

        // wait finish
        ThreadUtils.sleep(500);
        assert rcvMessage.get(0).equals("Hello Server, this message form client.");
        neta.shutdown();
        udpClient.close();
    }

    private static void sslHandshake(SSLEngine sslEngine, DatagramChannel udpClient, InetSocketAddress address) throws IOException {
        SSLSession sslSession = sslEngine.getSession();
        ByteBuffer sslOutAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslOutNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());
        ByteBuffer sslInAppBuffer = ByteBuffer.allocate(sslSession.getApplicationBufferSize());
        ByteBuffer sslInNetBuffer = ByteBuffer.allocate(sslSession.getPacketBufferSize());

        sslEngine.beginHandshake();
        while (true) {
            SSLEngineResult.HandshakeStatus result = sslEngine.getHandshakeStatus();
            if (result.name().equals("NEED_UNWRAP")) {
                sslInNetBuffer.clear();
                udpClient.read(sslInNetBuffer);
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
                udpClient.send(sslOutNetBuffer, address);
            } else if (result.name().equals("NEED_TASK")) {
                sslEngine.getDelegatedTask().run();
                result = sslEngine.getHandshakeStatus();
            }

            if (result.name().equals("FINISHED")) {
                return;
            }
        }
    }
}