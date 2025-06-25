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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoShutdownTest extends AbstractSoTest {

    // shutdownInput with accept.
    @Test
    public void rcvLocalShutdownInputTest_01() throws Exception {
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        AtomicBoolean rcvError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                @Override
                public void onActive(ProtoContext context) {
                    context.getChannel().shutdownInput();// shutdownInput with accept.
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
                    rcvAnyThing.set(true);
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    rcvError.set(e instanceof SoInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // start client and snd data
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            OutputStream out = client.getOutputStream();
            out.write("Hello".getBytes());
            out.flush();
        });

        // inputShutdown
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);
        assert channel.isShutdownInput();
        assert channel.getRcvHandlerStatus() == SoHandlerStatus.IDLE;
        assert !rcvAnyThing.get();
        assert !rcvError.get();

        // wait a monet
        ThreadUtils.sleep(500);
        assert !rcvAnyThing.get();
        assert !rcvError.get();

        server.shutdown();
    }

    // in onMessage shutdownInput using Decoder
    @Test
    public void rcvLocalShutdownInputTest_02() throws Exception {
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        AtomicBoolean rcvError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
                    while (rcvUp.hasMore()) {
                        ByteBuf data = rcvUp.takeMessage();
                        data.skipReadableBytes(data.readableBytes());
                        data.markReader();
                        rcvAnyThing.set(true);
                    }
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    rcvError.set(e instanceof SoInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // start client
        Socket client = new Socket("127.0.0.1", safePort);
        whiteHole(client);
        ThreadUtils.sleep(300);

        //
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);
        channel.shutdownInput();
        assert channel.isShutdownInput();
        assert channel.getRcvHandlerStatus() != SoHandlerStatus.IDLE;//shutdownInput are async
        ThreadUtils.sleep(300);
        assert channel.getRcvHandlerStatus() == SoHandlerStatus.IDLE;

        assert rcvAnyThing.get();
        assert rcvError.get();

        try {
            OutputStream out = client.getOutputStream();
            out.write(RandomUtils.nextBytes(4096));
            out.flush();
            assert false;
        } catch (Exception e) {
            assert e.getMessage().contains("Broken pipe (Write failed)");
        }

        server.shutdown();
    }

    // in onMessage shutdownInput using Encoder
    @Test
    public void rcvLocalShutdownInputTest_03() throws Exception {
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        AtomicBoolean rcvError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextEncoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
                    while (rcvUp.hasMore()) {
                        ByteBuf data = rcvUp.takeMessage();
                        data.skipReadableBytes(data.readableBytes());
                        data.markReader();
                        rcvAnyThing.set(true);
                    }
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    rcvError.set(e instanceof SoInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // start client
        Socket client = new Socket("127.0.0.1", safePort);
        whiteHole(client);
        ThreadUtils.sleep(300);

        //
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);
        channel.shutdownInput();
        assert channel.isShutdownInput();
        assert channel.getRcvHandlerStatus() != SoHandlerStatus.IDLE;//shutdownInput are async
        ThreadUtils.sleep(300);
        assert channel.getRcvHandlerStatus() == SoHandlerStatus.IDLE;

        assert !rcvAnyThing.get();
        assert !rcvError.get();

        try {
            OutputStream out = client.getOutputStream();
            out.write(RandomUtils.nextBytes(4096));
            out.flush();
            assert false;
        } catch (Exception e) {
            assert e.getMessage().contains("Broken pipe (Write failed)");
        }

        server.shutdown();
    }

    // shutdownInput with api.
    @Test
    public void rcvLocalShutdownInputTest_04() throws Exception {
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        AtomicBoolean rcvError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
                    rcvAnyThing.set(true);
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    rcvError.set(e instanceof SoInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // start client
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        NetChannel channel = listen.findChannel(2);
        channel.shutdownInput();
        ThreadUtils.sleep(300);
        assert channel.isShutdownInput();

        assert !rcvAnyThing.get();
        assert rcvError.get();

        try {
            OutputStream out = client.getOutputStream();
            out.write(RandomUtils.nextBytes(4096 * 128));
            out.flush();
            assert false;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            assert e.getMessage().contains("Broken pipe (Write failed)") || e.getMessage().contains("Connection refused");
        }

        ThreadUtils.sleep(1000);
        assert !rcvAnyThing.get();

        server.shutdown();
    }

    @Test
    public void rcvRemoteShutdownOutputTest_01() throws Exception {
        // start server
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, ctx -> ProtoHelper.builder().build());

        // connect to server
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);

        // Will be considered to remotely close the link
        client.shutdownOutput();
        ThreadUtils.sleep(300);
        assert channel.isClose();
        assert listen.findChannel(2) == null;

        server.shutdown();
    }

    @Test
    public void rcvRemoteShutdownOutputTest_02() throws Exception {
        // start server
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, ctx -> ProtoHelper.builder().build());

        // connect to server
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);
        channel.ignoreReadEofFlag();

        client.shutdownOutput();
        ThreadUtils.sleep(300);
        assert !channel.isClose();//This will not be considered closed because ignoreReadEofFlag is set
        assert listen.findChannel(2) != null;

        server.shutdown();
    }

    @Test
    public void sndLocalShutdownOutputTest_01() throws Exception {
        AtomicBoolean sndError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public void onActive(ProtoContext context) {
                context.getChannel().shutdownOutput();// shutdownOutput with accept.
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
                sndError.set(e instanceof SoOutputCloseException);
                return ProtoStatus.Next;
            }
        }).build();

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server.
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);

        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert channel.isShutdownOutput();
        assert !sndError.get();//the output channel is closed at accept

        // send data well be error.
        byte[] sendData = "Hello".getBytes();
        Future<?> future = channel.sendData(ByteBuf.wrap(sendData));
        future.await();
        assert future.getCause() == SoOutputCloseException.INSTANCE;
        assert !sndError.get();//the output channel is closed at accept

        server.shutdown();
    }

    @Test
    public void sndLocalShutdownOutputTest_02() throws Exception {
        AtomicBoolean sndError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
                sndError.set(e instanceof SoOutputCloseException);
                return ProtoStatus.Next;
            }
        }).build();

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server.
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);

        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert !channel.isShutdownOutput();
        assert !sndError.get();

        channel.shutdownOutput();

        // en error.
        ThreadUtils.sleep(200);
        assert !sndError.get();//No data is being written.

        //
        byte[] sendData = "Hello".getBytes();
        Future<?> future = channel.sendData(ByteBuf.wrap(sendData));
        future.await();
        assert future.getCause() == SoOutputCloseException.INSTANCE;
        assert !sndError.get();//the output channel is closed at accept

        server.shutdown();
    }

    @Test
    public void sndLocalShutdownOutputTest_03() throws Exception {
        AtomicBoolean sndError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
                sndError.set(e instanceof SoOutputCloseException);
                return ProtoStatus.Next;
            }
        }).build();

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(2, 8192));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // client reading...
        Socket client = new Socket("127.0.0.1", safePort);
        client.setReceiveBufferSize(2);
        blackHole(client, new AtomicBoolean());

        // server writing
        listen.waitAnyAccept();
        NetChannel channel = (NetChannel) context.findChannel(2);

        AtomicBoolean sending = new AtomicBoolean(false);
        ThreadUtils.daemonThread(true, (Callable) () -> {
            while (!channel.isClose() && !channel.isShutdownOutput()) {
                channel.sendData(ByteBuf.wrap(RandomUtils.nextBytes(1024)));
                sending.set(true);
            }
        });
        while (!sending.get()) {
            Thread.yield();
        }
        ThreadUtils.sleep(100);

        // test shutdownOutput
        assert !channel.isShutdownOutput();
        assert !sndError.get();

        channel.shutdownOutput();
        ThreadUtils.sleep(500);

        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert channel.isShutdownOutput();
        //assert sndError.get(); //The test passes but the hit probability is too low

        server.shutdown();
    }

    @Test
    public void sndLocalShutdownOutputTest_04() throws Exception {
        // start server.
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        ProtoInitializer initializer = ctx -> ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                rcvAnyThing.set(true);
                return ProtoStatus.Next;
            }
        }).build();

        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // connect to server.
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        // server close rcv channel keep output.
        NetChannel channel = (NetChannel) context.findChannel(2);
        channel.shutdownOutput(); // close output keep input.

        // only send data.
        OutputStream out = client.getOutputStream();
        out.write("Hello".getBytes());
        out.flush();
        ThreadUtils.sleep(500);

        assert rcvAnyThing.get();

        server.shutdown();
    }

    @Test
    public void sndRemoteShutdownInputTest_01() throws Exception {
        // start server
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(8, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, ctx -> ProtoHelper.builder().build());

        // connect to server -> send data -> close
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();

        NetChannel channel = listen.findChannel(2);
        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert !channel.isShutdownOutput();

        client.shutdownInput();
        Thread.sleep(100);

        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert !channel.isShutdownOutput();
        //
        Future<?> future = channel.sendData(RandomUtils.nextBytes(4096));
        while (!future.isDone()) {
            Thread.sleep(50);
        }

        assert future.isDone();
        assert future.getCause().getMessage().equals("Connection reset by peer");
        assert channel.isClose();

        server.shutdown();
    }

    @Test
    public void sndRemoteShutdownInputTest_02() throws Exception {
        AtomicBoolean sndError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                sndError.set(e instanceof SoOutputCloseException);
                return ProtoStatus.Next;
            }
        }).build();

        // start listen
        int safePort = safePort();
        NetManager server = new NetManager(crateConfig(2, 30));
        NetListen listen = server.listen("127.0.0.1", safePort, initializer);

        // client reading...
        Socket client = new Socket("127.0.0.1", safePort);
        AtomicBoolean signal = new AtomicBoolean(false);
        blackHole(client, signal);

        // server writing
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);
        whiteHole(channel);
        ThreadUtils.sleep(100); //wait all thread start.
        assert signal.get();

        // test shutdownOutput
        channel.shutdownOutput();
        ThreadUtils.sleep(100);

        assert channel.getSndHandlerStatus() == SoHandlerStatus.IDLE;
        assert channel.isShutdownOutput();
        //assert sndError.get(); //The test passes but the hit probability is too low

        signal.set(false);
        ThreadUtils.sleep(100);
        assert !signal.get();

        server.shutdown();
    }
}