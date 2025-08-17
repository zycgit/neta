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
import net.hasor.cobble.function.Callable;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoShutdownTest extends AbstractSoTest {

    // shutdownInput with accept.
    @Test
    public void rcvLocalShutdownInputTest_01() throws Throwable {
        AtomicBoolean rcvAnyThing = new AtomicBoolean();
        AtomicBoolean rcvError = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                @Override
                public void onActive(ProtoContext context) {
                    ((TcpChannel) context.getChannel()).shutdownInput();// shutdownInput with accept.
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
                    rcvAnyThing.set(true);
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                    rcvError.set(e instanceof TcpInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.listen(address, initializer, tcpConf);

        // start client and snd data
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket client = new Socket("127.0.0.1", safePort);
            OutputStream out = client.getOutputStream();
            out.write("Hello".getBytes());
            out.flush();
        });

        // inputShutdown
        listen.waitAnyAccept();
        TcpChannel channel = (TcpChannel) listen.findChannel(2);
        assert channel.isShutdownInput();
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
    public void rcvLocalShutdownInputTest_02() throws Throwable {
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
                    rcvError.set(e instanceof TcpInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.listen(address, initializer, tcpConf);

        // start client
        Socket client = new Socket("127.0.0.1", safePort);
        whiteHole(client);
        ThreadUtils.sleep(300);

        //
        listen.waitAnyAccept();
        TcpChannel channel = (TcpChannel) listen.findChannel(2);
        channel.shutdownInput();
        assert channel.isShutdownInput();
        ThreadUtils.sleep(300);

        assert rcvAnyThing.get();
        assert rcvError.get();

        try {
            OutputStream out = client.getOutputStream();
            out.write(RandomUtils.nextBytes(4096));
            out.flush();
            assert false;
        } catch (Exception e) {
            assert e.getMessage().contains("Broken pipe");
        }

        //
        assert !channel.isClose();
        assert channel.isShutdownInput();
        assert !client.isClosed();
        assert client.isConnected();

        server.shutdown();
    }

    // in onMessage shutdownInput using Encoder
    @Test
    public void rcvLocalShutdownInputTest_03() throws Throwable {
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
                    rcvError.set(e instanceof TcpInputCloseException);
                    return ProtoStatus.Next;
                }
            }).build();
        };

        // start listen
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.listen(address, initializer, tcpConf);

        // start client
        Socket client = new Socket("127.0.0.1", safePort);
        whiteHole(client);
        ThreadUtils.sleep(300);

        //
        listen.waitAnyAccept();
        TcpChannel channel = (TcpChannel) listen.findChannel(2);
        channel.shutdownInput();
        assert channel.isShutdownInput();
        ThreadUtils.sleep(300);

        assert !rcvAnyThing.get();
        assert !rcvError.get();

        try {
            OutputStream out = client.getOutputStream();
            out.write(RandomUtils.nextBytes(4096));
            out.flush();
            assert false;
        } catch (Exception e) {
            assert e.getMessage().contains("Broken pipe");
        }

        server.shutdown();
    }

    // shutdownInput with api.
    //    @Test
    //    public void rcvLocalShutdownInputTest_04() throws Throwable {
    //        AtomicBoolean rcvAnyThing = new AtomicBoolean();
    //        AtomicBoolean rcvError = new AtomicBoolean(false);
    //        ProtoInitializer initializer = ctx -> {
    //            return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
    //                @Override
    //                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown) {
    //                    rcvAnyThing.set(true);
    //                    return ProtoStatus.Next;
    //                }
    //
    //                @Override
    //                public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
    //                    rcvError.set(e instanceof TcpInputCloseException);
    //                    return ProtoStatus.Next;
    //                }
    //            }).build();
    //        };
    //
    //        // start listen
    //        int safePort = safePort();
    //        TcpSoConfig tcpConf = tcpConfig(8, 30);
    //
    //        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
    //        NetManager server = new NetManager(globalConf());
    //        NetListen listen = server.listen(address, initializer, tcpConf);
    //
    //        // start client
    //        Socket client = new Socket("127.0.0.1", safePort);
    //        listen.waitAnyAccept();
    //
    //        TcpChannel channel = (TcpChannel) listen.findChannel(2);
    //        channel.shutdownInput();
    //        ThreadUtils.sleep(300);
    //        assert channel.isShutdownInput();
    //
    //        assert !rcvAnyThing.get();
    //        assert rcvError.get();
    //
    //        try {
    //            OutputStream out = client.getOutputStream();
    //            out.write(RandomUtils.nextBytes(4096 * 128));
    //            out.flush();
    //            assert false;
    //        } catch (Exception e) {
    //            System.out.println(e.getMessage());
    //            assert e.getMessage().contains("Broken pipe") || e.getMessage().contains("Connection refused");
    //        }
    //
    //        ThreadUtils.sleep(1000);
    //        assert !rcvAnyThing.get();
    //
    //        server.shutdown();
    //    }

    @Test
    public void rcvRemoteShutdownOutputTest_01() throws Throwable {
        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(8, 30);

        NetManager server = new NetManager(globalConf());
        NetListen listen = server.listen(address, ctx -> ProtoHelper.builder().build(), tcpConf);

        // connect to server
        Socket client = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        NetChannel channel = listen.findChannel(2);

        // Will be considered to remotely close the link
        client.shutdownOutput();
        assert !client.isClosed();

        ThreadUtils.sleep(300);
        assert channel.isClose();
        assert listen.findChannel(2) == null;
        assert client.getInputStream().read() == -1;

        server.shutdown();
    }
}