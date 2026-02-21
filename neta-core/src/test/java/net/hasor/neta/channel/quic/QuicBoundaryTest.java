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
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import org.junit.Test;

/**
 * Boundary tests for QUIC transport: empty data, single byte, immediate send/close, double close.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicBoundaryTest extends AbstractSoTest {

    /** Empty data send. */
    @Test
    public void sendEmptyData() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer proto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, proto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, proto, quicConf);
        NetChannel ch = connect.get();

        // Sending empty ByteBuf should not crash
        ch.sendData(ByteBuf.wrap(new byte[0]));
        ThreadUtils.sleep(500);

        assert !ch.isClose() : "channel should still be open after sending empty data";

        neta.shutdown();
    }

    /** Single byte send. */
    @Test
    public void sendSingleByte() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        AtomicReference<byte[]> receivedRef = new AtomicReference<>();

        ProtoInitializer serverProto = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                data.markReader();
                receivedRef.set(bytes);
            }
            return ProtoStatus.Next;
        }).build();

        ProtoInitializer clientProto = ProtoHelper.standard().build();

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel ch = connect.get();

        ch.sendData(ByteBuf.wrap(new byte[] { 42 }));

        waitFor(() -> receivedRef.get() != null, 3000);
        byte[] received = receivedRef.get();
        assert received != null : "should receive single byte";
        assert received.length == 1 : "length should be 1";
        assert received[0] == 42 : "value should be 42";

        neta.shutdown();
    }

    /** Send data immediately after connect (before server accept completes). */
    @Test
    public void sendImmediatelyAfterConnect() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel ch = connect.get();

        // Send immediately without any delay
        ch.sendData("Immediate\n");

        waitFor(() -> !serverRcvData.isEmpty(), 5000);
        assert serverRcvData.get(0).equals("Immediate") : "data mismatch: " + serverRcvData.get(0);

        neta.shutdown();
    }

    /** Close channel immediately after send. */
    @Test
    public void sendThenCloseImmediately() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new CopyOnWriteArrayList<>();

        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(new ArrayList<>()));

        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, serverProto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, quicConf);
        NetChannel ch = connect.get();

        ch.sendData("Before close\n");
        ch.close();

        ThreadUtils.sleep(1000);
        assert ch.isClose() : "channel should be closed";
        // Data might or might not arrive - just ensure no crash

        neta.shutdown();
    }

    /** Close already-closed channel (idempotent). */
    @Test
    public void doubleClose() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        ProtoInitializer proto = ProtoHelper.standard().build();
        NetManager neta = new NetManager(globalConf());
        QuicSoConfig quicConf = quicConfig();

        neta.bind(address, proto, quicConf);
        Future<NetChannel> connect = neta.connectAsync(address, proto, quicConf);
        NetChannel ch = connect.get();

        ch.close();
        ThreadUtils.sleep(200);
        // Second close should not throw
        ch.close();
        ThreadUtils.sleep(200);

        assert ch.isClose() : "channel should be closed";

        neta.shutdown();
    }
}
