/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl.tcp;

import static net.hasor.neta.codec.AbstractSoTest.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import net.hasor.neta.codec.ssl.AbstractSslTest;
import net.hasor.neta.codec.ssl.SoSslUtils;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;

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
        ProtoInitializer serverProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(rcvMessage));
        NetManager neta = new NetManager(globalConf());
        neta.bind(address, serverProto, tcpConfig(128, 4096));

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

        // wait finish: first wait for client write to complete, then wait for server to receive
        while (!writeFinish.get()) {
            ThreadUtils.sleep(100);
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (rcvMessage.isEmpty() && System.currentTimeMillis() < deadline) {
            ThreadUtils.sleep(50);
        }
        assert rcvMessage.get(0).equals("Hello Server, this message form client.");

        neta.shutdown();
    }
}
