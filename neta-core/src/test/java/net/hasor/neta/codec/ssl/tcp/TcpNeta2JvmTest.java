/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl.tcp;

import static net.hasor.neta.codec.AbstractSoTest.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.ssl.AbstractSslTest;
import net.hasor.neta.codec.ssl.SoSslUtils;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TcpNeta2JvmTest extends AbstractSslTest {
    @Test
    public void neta_2_jvm() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        // server
        SSLServerSocketFactory sslFactory = SoSslUtils.sslContext(SslProtocol.TLS_v1_2).getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) sslFactory.createServerSocket(safePort);
        AtomicBoolean readFinish = new AtomicBoolean();
        List<String> rcvMessage = new ArrayList<>();
        ThreadUtils.daemonThread(true, (Callable) () -> {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                rcvMessage.add(input.readLine());
                readFinish.set(true);

                socket.close();
                serverSocket.close();
            } catch (Exception e) {
                readFinish.set(true);
            }
        });

        // client
        TcpSoConfig tcpConf = tcpConfig(128, 4096);
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        ProtoInitializer clientProto = SoSslUtils.udpSslSocketProtoStack(sslConf);

        // client say hello
        NetManager neta = new NetManager(globalConf());
        NetChannel client = neta.connectAsync(address, clientProto, tcpConf).get();
        Future<?> send = client.sendData("Hello Server, this message form client.\n");

        // wait finish
        while (!readFinish.get()) {
            ThreadUtils.sleep(100);
        }
        assert rcvMessage.get(0).equals("Hello Server, this message form client.");

        neta.shutdown();
    }
}
