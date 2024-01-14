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
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.handler.PipeBuilder.PipelineBuilder;
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoReadTest extends AbstractSoTest {
    @Test
    public void echoTest() throws Exception {
        int safePort = safePort();
        AtomicInteger cnt = new AtomicInteger();

        // echo anything from remote
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        server.listen("127.0.0.1", safePort, PipeInitializer.builder((channel, data) -> {
            try {
                ((NetChannel) channel).sendData(data); // echo
                cnt.incrementAndGet();// packet ++
            } catch (Exception e) {
                throw ExceptionUtils.toRuntime(e);
            }
        }));

        // client: send data to server
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        byte[] sndBytes = "Hello\n".getBytes();
        soOut.write(sndBytes);
        soOut.flush();
        Thread.sleep(1000); // wait network transfer

        // client: read echo data
        byte[] rcvBytes = new byte[sndBytes.length];
        InputStream soIn = client.getInputStream();
        soIn.read(rcvBytes);

        // test result
        assert "Hello\n".equals(new String(rcvBytes));
        assert cnt.get() == 3;
        server.shutdown();
    }

    @Test
    public void rcvFullTest_01() throws Exception {
        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setSoRcvBuf(32);
        soConfig.setNetlog(false);
        CobbleSocket server = new CobbleSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.empty()); // <-- stacking without handling

        // client: send a lot of pack
        Socket client = new Socket("127.0.0.1", safePort);
        client.setSendBufferSize(32 * 3);
        OutputStream soOut = client.getOutputStream();
        soOut.write(RandomUtils.nextBytes(32 * 3));
        soOut.flush();
        listen.waitAnyAccept();

        // server: rcvBuffer max is 30, Wait for to fill full
        NetChannel channel = (NetChannel) context.findChannel(2);
        while (channel.getReceivedBytes() < 30) {
            ThreadUtils.sleep(100);
        }

        // test result
        assert channel.getReceivedBytes() == 30; // is full
        ThreadUtils.sleep(500);       // wait 0.5s
        assert channel.getReceivedBytes() == 30; // server No extra data is received, data well be backpressed.
        server.shutdown();
    }

    @Test
    public void rcvFullTest_02() throws Exception {
        AtomicBoolean rcvErr = new AtomicBoolean(false);
        PipeConfig pipeConfig = new PipeConfig();
        pipeConfig.setPipeRcvDownStackSize(3);
        PipelineBuilder<ByteBuf, ByteBuf> empty = PipeInitializer.embedded().pipeConfig(pipeConfig);
        PipelineFactory build = empty//
                .nextTo("L1", (PipeLayer<ByteBuf, String, String, ByteBuf>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
                    // gen message to L2
                    rcvDown.offerMessage("msg");
                    return PipeStatus.Next;
                }).nextTo("L2", (PipeLayer<String, String, String, String>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
                    // message keep on L2
                    return PipeStatus.Next;
                }).bindReceive(new PipeListener<String>() {
                    @Override
                    public void onReceive(SoChannel<?> channel, String data) {

                    }

                    @Override
                    public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                        rcvErr.set(e instanceof PipeFullException);
                    }
                }).build();

        // start server
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(2, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, build);

        // client: send a lot of pack
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        for (int i = 0; i < 10; i++) {
            soOut.write(1);
            soOut.flush();
            ThreadUtils.sleep(100);
        }

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);
        ThreadUtils.sleep(500);

        assert rcvErr.get();
        assert !channel.isRcvAvailable();

        server.shutdown();
    }

    @Test
    public void rcvCounterTest() throws Exception {
        // start server
        MessageDigest serverDigest = MessageDigest.getInstance("MD5");
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.builder((channel, data) -> {
            int len = data.readableBytes();
            byte[] bytes = new byte[len];
            data.readBytes(bytes);
            data.markReader();
            serverDigest.digest(bytes);
        }));

        // client send
        MessageDigest clientDigest = MessageDigest.getInstance("MD5");
        Socket client = new Socket("127.0.0.1", safePort);
        byte[] nextBytes = RandomUtils.nextBytes(32 * 3);
        OutputStream soOut = client.getOutputStream();
        soOut.write(nextBytes);
        clientDigest.digest(nextBytes);
        soOut.flush();
        listen.waitAnyAccept();

        // wait full.
        NetChannel channel = (NetChannel) context.findChannel(2);
        while (channel.getReceivedBytes() < 32 * 3) {
            ThreadUtils.sleep(100);
        }

        assert toMd5(clientDigest).equals(toMd5(serverDigest));
        server.shutdown();
    }

    @Test
    public void rcvReadTimeoutTest_01() throws Exception {
        // start server
        AtomicLong rcvErrTime = new AtomicLong(0);
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.builder(new PipeListener<ByteBuf>() {
            @Override
            public void onReceive(SoChannel<?> channel, ByteBuf data) {

            }

            @Override
            public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
            }
        }));

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        long curTime = System.currentTimeMillis();
        channel.setReadTimeout(500, TimeUnit.MILLISECONDS);

        Thread.sleep(1000);
        assert (rcvErrTime.get() - curTime) > 500;
        assert (rcvErrTime.get() - curTime) < 800; // multit hreaded, maybe process scheduling, wait a 300ms

        server.shutdown();
    }

    @Test
    public void rcvReadTimeoutTest_02() throws Exception {
        // start server
        AtomicLong rcvErrTime = new AtomicLong(0);
        int safePort = safePort();
        SoConfig soConfig = crateConfig(8, 30);
        soConfig.setSoReadTimeoutMs(500);
        CobbleSocket server = new CobbleSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.builder(new PipeListener<ByteBuf>() {
            @Override
            public void onReceive(SoChannel<?> channel, ByteBuf data) {

            }

            @Override
            public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
            }
        }));

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        long curTime = System.currentTimeMillis();
        channel.setReadTimeout();// using "soConfig.setSoReadTimeoutMs(500);"

        Thread.sleep(1000);
        assert (rcvErrTime.get() - curTime) > 500;
        assert (rcvErrTime.get() - curTime) < 800; // multit hreaded, maybe process scheduling, wait a 300ms

        server.shutdown();
    }

    @Test
    public void waitReceiveTest_01() throws Exception {
        // start server
        AtomicLong rcvErrTime = new AtomicLong(0);
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.builder(new PipeListener<ByteBuf>() {
            @Override
            public void onReceive(SoChannel<?> channel, ByteBuf data) {

            }

            @Override
            public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
            }
        }));

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        try {
            channel.waitReceive(500, TimeUnit.MILLISECONDS);
            assert false;
        } catch (Exception e) {
            assert e instanceof SoReadTimeoutException;
        }

        assert rcvErrTime.get() == 0;

        server.shutdown();
    }

    @Test
    public void waitReceiveTest_02() throws Exception {
        // start server
        AtomicLong rcvErrTime = new AtomicLong(0);
        int safePort = safePort();
        SoConfig soConfig = crateConfig(8, 30);
        soConfig.setSoReadTimeoutMs(500);
        CobbleSocket server = new CobbleSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.builder(new PipeListener<ByteBuf>() {
            @Override
            public void onReceive(SoChannel<?> channel, ByteBuf data) {

            }

            @Override
            public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
            }
        }));

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) context.findChannel(2);
        try {
            channel.waitReceive(); //using "soConfig.setSoReadTimeoutMs(500);"
            assert false;
        } catch (Exception e) {
            assert e instanceof SoReadTimeoutException;
        }

        assert rcvErrTime.get() == 0;

        server.shutdown();
    }

    @Test
    public void rcvThrowTest_01() throws Exception {
        AtomicBoolean rcvErr1 = new AtomicBoolean(false);

        PipelineBuilder<ByteBuf, ByteBuf> empty = PipeInitializer.builder();
        PipelineFactory build = empty//
                .nextTo("L1", (PipeLayer<ByteBuf, String, String, ByteBuf>) (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
                    if (isRcv) {
                        throw new IllegalStateException();
                    } else {
                        return PipeStatus.Next;
                    }
                }).bindReceive(new PipeListener<String>() {
                    @Override
                    public void onReceive(SoChannel<?> channel, String data) {

                    }

                    @Override
                    public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                        rcvErr1.set(e instanceof IllegalStateException);
                        throw new IllegalArgumentException();
                    }
                }).build();

        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setNetlog(false);
        CobbleSocket server = new CobbleSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, build);

        // client: send a lot of pack
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        soOut.write(1);
        soOut.flush();

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);
        ThreadUtils.sleep(500);

        assert channel.isClose();
        assert rcvErr1.get();

        server.shutdown();
    }
}