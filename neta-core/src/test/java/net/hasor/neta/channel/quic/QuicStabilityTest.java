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
package net.hasor.neta.channel.quic;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * Stability tests for QUIC transport: concurrency, rapid send, bidirectional, reconnect.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStabilityTest extends AbstractSoTest {

    /** Multiple clients connect concurrently to the same server. */
    @Test
    public void multiClientConcurrent() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);

        int clientCount = 5;
        CountDownLatch latch = new CountDownLatch(clientCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < clientCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
                    Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
                    NetChannel ch = connect.get();
                    ch.sendData("Concurrent-" + idx + "\n");
                } catch (Throwable t) {
                    t.printStackTrace();
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);
        assert errors.get() == 0 : "no errors expected, got: " + errors.get();

        waitFor(() -> serverRcvData.size() >= clientCount, 5000);
        assert serverRcvData.size() == clientCount : "expected " + clientCount + " messages, got: " + serverRcvData.size();

        neta.shutdown();
    }

    /** Rapid send - many messages sent in tight loop. */
    @Test
    public void rapidSend() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        int msgCount = 50;
        for (int i = 0; i < msgCount; i++) {
            clientChannel.sendData("Rapid-" + i + "\n");
        }

        waitFor(() -> serverRcvData.size() >= msgCount, 10000);
        assert serverRcvData.size() == msgCount : "expected " + msgCount + " messages, got: " + serverRcvData.size();

        // Verify order
        for (int i = 0; i < msgCount; i++) {
            assert serverRcvData.get(i).equals("Rapid-" + i) : "order mismatch at " + i + ": " + serverRcvData.get(i);
        }

        neta.shutdown();
    }

    /** Bidirectional rapid send - both sides send many messages simultaneously. */
    @Test
    public void bidirectionalRapid() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();
        List<String> clientRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        NetListen listen = neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();

        int msgCount = 20;

        // Client -> Server
        for (int i = 0; i < msgCount; i++) {
            clientChannel.sendData("FromClient-" + i + "\n");
        }

        // Wait for server-side channel
        listen.waitAnyAccept();
        NetChannel serverChannel = findServerChannel(neta, listen);
        assert serverChannel != null : "server channel should exist";

        // Server -> Client
        for (int i = 0; i < msgCount; i++) {
            serverChannel.sendData("FromServer-" + i + "\n");
        }

        waitFor(() -> serverRcvData.size() >= msgCount && clientRcvData.size() >= msgCount, 10000);
        assert serverRcvData.size() == msgCount : "server expected " + msgCount + ", got: " + serverRcvData.size();
        assert clientRcvData.size() == msgCount : "client expected " + msgCount + ", got: " + clientRcvData.size();

        neta.shutdown();
    }

    /** Connect, send, close, reconnect cycle. */
    @Test
    public void reconnectCycle() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);

        int cycles = 3;
        for (int c = 0; c < cycles; c++) {
            final int expected = c;
            ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));
            Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
            NetChannel ch = connect.get();
            ch.sendData("Cycle-" + c + "\n");
            waitFor(() -> serverRcvData.size() > expected, 3000);
            ch.close();
            ThreadUtils.sleep(200);
            assert ch.isClose() : "channel should be closed after close()";
        }

        assert serverRcvData.size() == cycles : "expected " + cycles + " messages, got: " + serverRcvData.size();

        neta.shutdown();
    }

}
