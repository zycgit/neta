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
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.bytebuf.ByteBuf;
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
        // echo anything from remote
        AtomicInteger cnt = new AtomicInteger();

        int safePort = safePort();
        NetaSocket server = new NetaSocket(crateConfig(2, 32));
        NetListen listen = server.listen("127.0.0.1", safePort, new PipeInitializer() {
            @Override
            public Pipeline<ByteBuf> config(PipeContext ctx) {
                return PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
                    @Override
                    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) throws Throwable {
                        NetChannel netChannel = ((NetChannel) context.getChannel());
                        while (src.hasMore()) {
                            netChannel.sendData(src.takeMessage()); // echo
                            cnt.incrementAndGet();// packet ++
                        }
                        return PipeStatus.Next;
                    }
                }).build();
            }
        });

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
    public void rcvBufSizeTest() throws Exception {
        // echo anything from remote
        AtomicInteger cnt = new AtomicInteger();

        int safePort = safePort();
        NetaSocket server = new NetaSocket(crateConfig(2, 32));
        NetListen listen = server.listen("127.0.0.1", safePort, new PipeInitializer() {
            @Override
            public Pipeline<ByteBuf> config(PipeContext ctx) {
                return PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
                    @Override
                    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) throws Throwable {
                        while (src.hasMore()) {
                            src.takeMessage();
                            cnt.incrementAndGet();// packet ++
                        }
                        return PipeStatus.Next;
                    }
                }).build();
            }
        });

        // client: send data to server
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            OutputStream soOut = client.getOutputStream();
            byte[] sndBytes = new byte[] { 1, 2, 3, 4, 5, 6 };
            soOut.write(sndBytes);
            soOut.flush();
            soOut.close();
        });

        listen.waitAnyAccept();
        listen.waitIdle();
        assert cnt.get() == 3;
        server.shutdown();
    }

    @Test
    public void rcvBackPressedTest_01() throws Exception {
        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setSoRcvBuf(32);
        soConfig.setNetlog(false);
        NetaSocket server = new NetaSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, new PipeInitializer() {
            @Override
            public Pipeline<ByteBuf> config(PipeContext ctx) {
                return PipeHelper.builder().build();// <-- stacking without handling
            }
        });

        // client: send a lot of bytes
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            client.setSendBufferSize(32 * 3);
            OutputStream soOut = client.getOutputStream();
            soOut.write(RandomUtils.nextBytes(32 * 3));
            soOut.flush();
        });

        // server: rcvBuffer max is 30, Wait for to fill full
        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);
        while (channel.getRcvBytes() < 30) {
            ThreadUtils.sleep(100);
        }
        assert channel.getRcvBytes() == 32; // is full ( swapSize = 2, bufSize = 30)

        // after 1s,server No extra data is received, data well be backpressed.
        ThreadUtils.sleep(1000);
        assert channel.getRcvBytes() == 32;
        assert channel.getRcvBufferUsed() == 30;

        server.shutdown();
    }

    @Test
    public void rcvBackPressedTest_02() throws Exception {
        AtomicBoolean rcvErr = new AtomicBoolean(false);
        PipeConfig pipeConfig = new PipeConfig();
        pipeConfig.setPipeRcvDownStackSize(3);

        PipeBuilder<String, ByteBuf> builder = PipeHelper.builder(pipeConfig)//
                .nextDecoder("L1", pipeConfig, (PipeHandler<ByteBuf, String>) (context, rcvUp, rcvDown) -> {
                    while (rcvUp.hasMore() && rcvDown.hasSlot()) {
                        ByteBuf byteBuf = rcvUp.peekMessage();
                        while (byteBuf.hasLine()) {
                            rcvDown.offerMessage(byteBuf.readLine());
                        }
                        byteBuf.markReader();
                        if (!byteBuf.hasReadable()) {
                            rcvUp.skipMessage(1);
                        }
                    }
                    // gen message to L2
                    return PipeStatus.Next;
                }).nextDecoder("L2", pipeConfig, (PipeHandler<String, String>) (context, rcvUp, rcvDown) -> {
                    return PipeStatus.Next; //No data consumption
                }).nextDecoder(new PipeHandler<String, String>() {
                    @Override
                    public PipeStatus onMessage(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
                        return PipeStatus.Next;
                    }

                    @Override
                    public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                        rcvErr.set(e instanceof PipeFullException);
                        return PipeStatus.Next;
                    }
                });

        // start server
        int safePort = safePort();
        NetaSocket server = new NetaSocket(new SoConfig());
        NetListen listen = server.listen("127.0.0.1", safePort, context -> builder.build());

        // client: send a lot of line
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            OutputStream soOut = client.getOutputStream();
            for (int i = 0; i < 10; i++) {
                soOut.write("Hello\n".getBytes());
                soOut.flush();
                ThreadUtils.sleep(100);
            }
        });

        listen.waitAnyAccept();
        while (!rcvErr.get()) {
            ThreadUtils.sleep(100);// wait full
        }

        NetChannel channel = (NetChannel) server.findChannel(2);
        assert channel.getRcvSlotSize() == 0;

        server.shutdown();
    }

    @Test
    public void rcvCounterTest() throws Exception {
        // eval dm5
        MessageDigest serverDigest = MessageDigest.getInstance("MD5");
        PipeInitializer initializer = ctx -> PipeHelper.builder().nextDecoder((PipeHandler<ByteBuf, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                int len = data.readableBytes();
                byte[] bytes = new byte[len];
                data.readBytes(bytes);
                data.markReader();
                serverDigest.digest(bytes);
            }
            return PipeStatus.Next;
        }).build();

        // start server
        int safePort = safePort();
        NetaSocket server = new NetaSocket(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // client send data
        MessageDigest clientDigest = MessageDigest.getInstance("MD5");
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            byte[] nextBytes = RandomUtils.nextBytes(4096);
            OutputStream soOut = client.getOutputStream();
            soOut.write(nextBytes);
            clientDigest.digest(nextBytes);
            soOut.flush();
            soOut.close();
        });

        listen.waitAnyAccept();
        listen.waitIdle();

        // wait full.
        assert toMd5(clientDigest).equals(toMd5(serverDigest));
        server.shutdown();
    }

    @Test
    public void rcvReadTimeoutTest_01() throws Exception {
        AtomicLong rcvErrTime = new AtomicLong(0);
        PipeInitializer initializer = ctx -> PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
            @Override
            public PipeStatus onMessage(PipeContext context1, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) {
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
                return PipeStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        NetaSocket server = new NetaSocket(new SoConfig());
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) server.findChannel(2);
        long curTime = System.currentTimeMillis();
        channel.setReadTimeout(500, TimeUnit.MILLISECONDS);

        Thread.sleep(1000);
        assert (rcvErrTime.get() - curTime) > 500;
        assert (rcvErrTime.get() - curTime) < 800; // multit hreaded, maybe process scheduling, wait a 300ms

        server.shutdown();
    }

    @Test
    public void rcvReadTimeoutTest_02() throws Exception {
        AtomicLong rcvErrTime = new AtomicLong(0);
        PipeInitializer initializer = ctx -> PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
            @Override
            public PipeStatus onMessage(PipeContext context1, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) {
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
                return PipeStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        SoConfig soConfig = new SoConfig();
        soConfig.setSoReadTimeoutMs(100);
        NetaSocket server = new NetaSocket(soConfig);
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) server.findChannel(2);
        long curTime = System.currentTimeMillis();
        channel.setReadTimeout();

        Thread.sleep(300);
        assert (rcvErrTime.get() - curTime) > 100;
        assert (rcvErrTime.get() - curTime) < 200; // multit hreaded, maybe process scheduling, wait a 300ms

        server.shutdown();
    }

    @Test
    public void waitReceiveTest_01() throws Exception {
        AtomicLong rcvErrTime = new AtomicLong(0);
        PipeInitializer initializer = ctx -> PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
            @Override
            public PipeStatus onMessage(PipeContext context1, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) {
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
                return PipeStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        NetaSocket server = new NetaSocket(new SoConfig());
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) server.findChannel(2);
        long startTime = System.currentTimeMillis();
        try {
            channel.waitReceive(500, TimeUnit.MILLISECONDS);
            assert false;
        } catch (Exception e) {
            assert e instanceof SoReadTimeoutException;
            startTime = System.currentTimeMillis() - startTime;
        }

        assert startTime >= 500;
        assert rcvErrTime.get() == 0;

        server.shutdown();
    }

    @Test
    public void waitReceiveTest_02() throws Exception {
        AtomicLong rcvErrTime = new AtomicLong(0);
        PipeInitializer initializer = ctx -> PipeHelper.builder().nextDecoder(new PipeHandler<ByteBuf, ByteBuf>() {
            @Override
            public PipeStatus onMessage(PipeContext context1, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) {
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                if (e instanceof SoReadTimeoutException) {
                    rcvErrTime.set(System.currentTimeMillis());
                }
                return PipeStatus.Next;
            }
        }).build();

        // start server
        int safePort = safePort();
        SoConfig soConfig = new SoConfig();
        soConfig.setSoReadTimeoutMs(100);
        NetaSocket server = new NetaSocket(soConfig);
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output
        NetChannel channel = (NetChannel) server.findChannel(2);
        long startTime = System.currentTimeMillis();
        try {
            channel.waitReceive(); //using "soConfig.setSoReadTimeoutMs(100);"
            assert false;
        } catch (Exception e) {
            assert e instanceof SoReadTimeoutException;
            startTime = System.currentTimeMillis() - startTime;
        }

        assert startTime >= 100;
        assert rcvErrTime.get() == 0;

        server.shutdown();
    }

    @Test
    public void rcvThrowTest_01() throws Exception {
        AtomicBoolean rcvErr1 = new AtomicBoolean(false);
        PipeInitializer initializer = ctx -> {
            return PipeHelper.builder().nextDecoder("L1", new PipeHandler<ByteBuf, String>() {
                @Override
                public PipeStatus onMessage(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) throws Throwable {
                    throw new IllegalStateException("L1 Throw");
                }
            }).nextDecoder(new PipeHandler<String, String>() {
                @Override
                public PipeStatus onMessage(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) throws Throwable {
                    return PipeStatus.Next;
                }

                @Override
                public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) throws Throwable {
                    rcvErr1.set(e.getMessage().equals("L1 Throw")); //exception is handled
                    return PipeStatus.Next;
                }
            }).build();
        };

        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setNetlog(false);
        NetaSocket server = new NetaSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // client: send a lot of pack
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        soOut.write(1);
        soOut.flush();

        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);
        ThreadUtils.sleep(500);

        assert !channel.isClose();
        assert rcvErr1.get();

        server.shutdown();
    }

    @Test
    public void rcvThrowTest_02() throws Exception {
        AtomicBoolean rcvErr1 = new AtomicBoolean(false);
        PipeInitializer initializer = ctx -> {
            return PipeHelper.builder().nextDecoder("L1", new PipeHandler<ByteBuf, String>() {
                @Override
                public PipeStatus onMessage(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
                    throw new IllegalStateException("L1 Throw");
                }
            }).nextDecoder(new PipeHandler<String, String>() {
                @Override
                public PipeStatus onMessage(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
                    return PipeStatus.Next;
                }

                @Override
                public PipeStatus onError(PipeContext context, Throwable e, PipeExceptionHolder eh) {
                    rcvErr1.set(e.getMessage().equals("L1 Throw"));
                    throw new IllegalArgumentException(); //Additional exceptions,Cause connection closure.
                }
            }).build();
        };

        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setNetlog(false);
        NetaSocket server = new NetaSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

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