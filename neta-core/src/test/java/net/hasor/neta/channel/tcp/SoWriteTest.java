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
package net.hasor.neta.channel.tcp;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoWriteTest extends AbstractSoTest {
    @Test
    public void serverSayHelloTest_01() throws Throwable {
        // server say Hello
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ctx -> ProtoHelper.standard().build(), tcpConf);
        Socket client = new Socket("127.0.0.1", safePort);

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) server.findChannel(2);
        channel.sendData("Hello this message form server.\n".getBytes());

        Thread.sleep(1000); // wait network transfer

        // client: read echo data
        InputStream soIn = client.getInputStream();
        int available = soIn.available();
        byte[] rcvBytes = new byte[available];
        soIn.read(rcvBytes);

        // test result
        assert "Hello this message form server.\n".equals(new String(rcvBytes));
        server.shutdown();
    }

    @Test
    public void serverSayHelloTest_02() throws Throwable {
        // server say Hello
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ctx -> ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public void onActive(ProtoContext context) throws Throwable {
                context.sendData(ByteBuf.wrap("Hello this message form server.\n".getBytes()));
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }
        }).build(), tcpConf);

        // client connect to Server
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        Thread.sleep(1000); // wait network transfer

        // client: read echo data
        InputStream soIn = client.getInputStream();
        int available = soIn.available();
        byte[] rcvBytes = new byte[available];
        soIn.read(rcvBytes);

        // test result
        assert "Hello this message form server.\n".equals(new String(rcvBytes));
        server.shutdown();
    }

    @Test
    public void serverEchoTest() throws Throwable {
        // server start
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager server = new NetManager(new NetConfig());
        NetListen listen = server.bind(address, new ProtoInitializer() {
            @Override
            public ProtoStack<ByteBuf> config(ProtoContext ctx) {
                return ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                        while (src.hasMore()) {
                            ((NetChannel) context.getChannel()).sendData(src.takeMessage());
                        }
                        return ProtoStatus.Next;
                    }
                }).build();
            }
        }, SoConfig.TCP());

        // client start
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // client: say hello to server.
        OutputStream soOut = client.getOutputStream();
        soOut.write("Hello this message form client.\n".getBytes());
        soOut.flush();
        Thread.sleep(1000); // wait network transfer

        // client: rcv echo hello message
        InputStream soIn = client.getInputStream();
        int available = soIn.available();
        byte[] rcvBytes = new byte[available];
        soIn.read(rcvBytes);

        // test result
        assert "Hello this message form client.\n".equals(new String(rcvBytes));
        server.shutdown();
    }

    @Test
    public void sndTimeoutTest_01() throws Throwable {
        AtomicLong sndErrTime = new AtomicLong(0);
        ProtoInitializer initializer = ctx -> ProtoHelper.standard().nextEncoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                dst.offerMessage(src);
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                if (e instanceof SoWriteTimeoutException) {
                    sndErrTime.set(System.currentTimeMillis());
                }
                return ProtoStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);
        tcpConf.setSoWriteTimeoutMs(1);

        NetManager server = new NetManager(globalConf());
        SoContext context = server.getContext();
        NetListen listen = server.bind(address, initializer, tcpConf);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        Future<?> future = channel.sendData(RandomUtils.nextBytes(1024 * 1024));
        while (!future.isDone()) {
            Thread.sleep(100);
        }

        assert future.getCause() instanceof SoWriteTimeoutException;
        assert channel.isClose();
        assert sndErrTime.get() > 0;

        server.shutdown();
    }

    @Test
    public void sndTimeoutTest_02() throws Throwable {
        AtomicLong sndErrTime = new AtomicLong(0);
        ProtoInitializer initializer = ctx -> ProtoHelper.standard().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                dst.offerMessage(src);
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                if (e instanceof SoWriteTimeoutException) {
                    sndErrTime.set(System.currentTimeMillis());
                }
                return ProtoStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);
        tcpConf.setSoWriteTimeoutMs(1);

        NetManager server = new NetManager(globalConf());
        SoContext context = server.getContext();
        NetListen listen = server.bind(address, initializer, tcpConf);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        Future<?> future = channel.sendData(RandomUtils.nextBytes(1024 * 1024));
        while (!future.isDone()) {
            Thread.sleep(100);
        }

        assert future.getCause() instanceof SoWriteTimeoutException;
        assert channel.isClose();
        assert sndErrTime.get() == 0;

        server.shutdown();
    }

    @Test
    public void sndThrowTest_01() throws Throwable {
        AtomicBoolean sndErr1 = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.standard().nextEncoder("L1", new ProtoHandler<ByteBuf, ByteBuf>() {

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                    throw new IllegalStateException("L1 Throw");
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    sndErr1.set(e.getMessage().equals("L1 Throw"));
                    throw new IllegalArgumentException(); //Additional exceptions,Cause connection closure.
                }
            }).build();
        };

        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 30);
        NetConfig netConfig = globalConf();
        netConfig.setPrintLog(false);

        NetManager server = new NetManager(netConfig);
        SoContext context = server.getContext();
        NetListen listen = server.bind(address, initializer, tcpConf);

        // client: send a lot of pack
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        soOut.write(1);
        soOut.flush();

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);
        ThreadUtils.sleep(500);

        assert channel == null || channel.isClose();
        assert sndErr1.get();

        server.shutdown();
    }

    @Test
    public void sendWaitFinishTest_01() throws Throwable {
        // server say Hello
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 32);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ctx -> ProtoHelper.standard().build(), tcpConf);
        Socket client = new Socket("127.0.0.1", safePort);

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) server.findChannel(2);
        Future<?> future = channel.sendData("Hello this message form server.\n".getBytes());
        while (!future.isDone()) {
            ThreadUtils.sleep(100);
        }

        // client: read echo data
        InputStream soIn = client.getInputStream();
        int available = soIn.available();
        byte[] rcvBytes = new byte[available];
        soIn.read(rcvBytes);

        // test result
        assert "Hello this message form server.\n".equals(new String(rcvBytes));
        server.shutdown();
    }

    //    @Test
    //    public void sndFullTest_01() throws Exception {
    //        // start server
    //        int safePort = safePort();
    //        SoConfig soConfig = crateConfig(2, 30);
    //        soConfig.setSoRcvBuf(32);
    //        soConfig.setNetlog(false);
    //        NetaSocket server = new NetaSocket(soConfig);
    //        SoContext context = server.getContext();
    //        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.empty()); // <-- stacking without handling
    //
    //        // client: send a lot of pack
    //        Socket client = new Socket("127.0.0.1", safePort);
    //        client.setSendBufferSize(32 * 3);
    //        OutputStream soOut = client.getOutputStream();
    //        soOut.write(RandomUtils.nextBytes(32 * 3));
    //        soOut.flush();
    //        listen.waitAnyAccept();
    //
    //        // server: rcvBuffer max is 30, Wait for to fill full
    //        NetChannel channel = (NetChannel) context.findChannel(2);
    //        while (channel.getReceivedBytes() < 30) {
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        // test result
    //        assert channel.getReceivedBytes() == 30; // is full
    //        ThreadUtils.sleep(500);       // wait 0.5s
    //        assert channel.getReceivedBytes() == 30; // server No extra data is received, data well be backpressed.
    //        server.shutdown();
    //    }

    //    @Test
    //    public void rcvFullTest_02() throws Exception {
    //        AtomicBoolean rcvErr = new AtomicBoolean(false);
    //        PipeConfig pipeConfig = new PipeConfig();
    //        pipeConfig.setPipeRcvDownStackSize(3);
    //        PipeBuilder.PipelineBuilder<ByteBuf, ByteBuf> empty = PipeInitializer.builder().pipeConfig(pipeConfig);
    //        PipelineFactory build = empty//
    //                .nextTo("L1", (PipeLayer<ByteBuf, String, String, ByteBuf>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
    //                    // gen message to L2
    //                    rcvDown.offerMessage("msg");
    //                    return PipeStatus.Next;
    //                }).nextTo("L2", (PipeLayer<String, String, String, String>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
    //                    // message keep on L2
    //                    return PipeStatus.Next;
    //                }).bindReceive(new PipeListener<String>() {
    //                    @Override
    //                    public void onReceive(SoChannel<?> channel, String data) {
    //
    //                    }
    //
    //                    @Override
    //                    public void onReceiveError(SoChannel<?> channel, Throwable e) {
    //                        rcvErr.set(e instanceof PipeFullException);
    //                    }
    //                }).build();
    //
    //        // start server
    //        int safePort = safePort();
    //        CobbleSocket server = new CobbleSocket(crateConfig(2, 30));
    //        SoContext context = server.getContext();
    //        NetListen listen = server.listen("127.0.0.1", safePort, build);
    //
    //        // client: send a lot of pack
    //        Socket client = new Socket("127.0.0.1", safePort);
    //        OutputStream soOut = client.getOutputStream();
    //        for (int i = 0; i < 10; i++) {
    //            soOut.write(1);
    //            soOut.flush();
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        listen.waitAnyAccept();
    //        NetChannel channel = (NetChannel) context.findChannel(2);
    //        ThreadUtils.sleep(500);
    //
    //        assert rcvErr.get();
    //        assert !channel.isRcvAvailable();
    //
    //        server.shutdown();
    //    }
}