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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import org.junit.Test;

/**
 * Stress tests for QUIC transport: high-throughput single/multi-connection and multi-stream concurrency.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStressTest extends AbstractSoTest {

    /** Stress test: many messages through a single connection. */
    @Test
    public void stressSingleConnection() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicInteger serverRcvCount = new AtomicInteger(0);

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                data.markReader();
                serverRcvCount.incrementAndGet();
            }
            return ProtoStatus.Next;
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel ch = connect.get();

        int totalMessages = 200;
        for (int i = 0; i < totalMessages; i++) {
            ch.sendData(ByteBuf.wrap(("Stress" + i + "\n").getBytes()));
        }

        waitFor(() -> serverRcvCount.get() >= totalMessages, 15000);
        assert serverRcvCount.get() >= totalMessages : "expected >= " + totalMessages + " messages, got: " + serverRcvCount.get();

        neta.shutdown();
    }

    /** Stress test: multiple connections each sending messages. */
    @Test
    public void stressMultiConnection() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicInteger serverRcvCount = new AtomicInteger(0);

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                data.markReader();
                serverRcvCount.incrementAndGet();
            }
            return ProtoStatus.Next;
        }).build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);

        int clientCount = 3;
        int msgsPerClient = 10;
        CountDownLatch doneLatch = new CountDownLatch(clientCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int c = 0; c < clientCount; c++) {
            final int clientIdx = c;
            new Thread(() -> {
                try {
                    ProtoInitializer clientProto = ProtoHelper.standard().build();
                    Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
                    NetChannel ch = connect.get();
                    for (int m = 0; m < msgsPerClient; m++) {
                        ch.sendData(ByteBuf.wrap(("C" + clientIdx + "M" + m + "\n").getBytes()));
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        doneLatch.await(15, TimeUnit.SECONDS);
        assert errors.get() == 0 : "no errors expected";

        int expectedTotal = clientCount * msgsPerClient;
        waitFor(() -> serverRcvCount.get() >= expectedTotal, 15000);
        assert serverRcvCount.get() >= expectedTotal : "expected >= " + expectedTotal + ", got: " + serverRcvCount.get();

        neta.shutdown();
    }

    /** Multiple streams sending data concurrently through same connection. */
    @Test
    public void multiStreamConcurrent() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicInteger serverRcvCount = new AtomicInteger(0);

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                src.takeMessage().markReader();
                serverRcvCount.incrementAndGet();
            }
            return ProtoStatus.Next;
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel clientChannel = connect.get();
        QuicConnection clientConn = ((QuicChannel) clientChannel).getQuicConnection();

        // Send on multiple streams concurrently
        int streamCount = 5;
        int msgsPerStream = 10;
        CountDownLatch latch = new CountDownLatch(streamCount);

        for (int s = 0; s < streamCount; s++) {
            final long streamId = s * 4; // client-initiated bidirectional: 0, 4, 8, 12, 16
            new Thread(() -> {
                try {
                    for (int m = 0; m < msgsPerStream; m++) {
                        clientConn.sendStreamData(streamId, ("S" + streamId + "M" + m).getBytes(), false);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);

        int expectedTotal = streamCount * msgsPerStream;
        waitFor(() -> serverRcvCount.get() >= expectedTotal, 10000);
        assert serverRcvCount.get() >= expectedTotal : "expected >= " + expectedTotal + ", got: " + serverRcvCount.get();

        // Verify all streams are tracked
        Set<Long> openStreams = clientConn.getOpenStreams();
        assert openStreams.size() == streamCount : "expected " + streamCount + " open streams, got: " + openStreams.size();

        neta.shutdown();
    }
}
