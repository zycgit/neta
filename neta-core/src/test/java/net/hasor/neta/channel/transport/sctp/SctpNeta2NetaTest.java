/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;

import static net.hasor.neta.channel.AbstractSoTest.globalConf;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;

public class SctpNeta2NetaTest {

    private static ProtoInitializer addSctpListProtoStack(ProtoHandler<String, String> last) {
        return ProtoHelper.typed(SctpMessage.class, ByteBuf.class).nextDecoder((ProtoHandler<SctpMessage, ByteBuf>) (context, src, dst) -> {
            while (src.hasMore()) {
                dst.offerMessage(src.takeMessage().getByteBuf());
            }
            return ProtoStatus.Next;
        }).nextDuplex("String", HandlerUtils::doDecoder1, HandlerUtils::doEncoder1).nextDecoder(last).build();
    }

    private boolean checkSupport() {
        try {
            com.sun.nio.sctp.SctpChannel.open();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    public void sctpWrite_Test() throws Throwable {
        if (!checkSupport()) {
            return;
        }

        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = addSctpListProtoStack(new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = addSctpListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        NetManager neta = new NetManager(globalConf());

        SctpSoConfig sctpConfig = new SctpSoConfig();

        // server
        NetListen listen = neta.bind(address, serverProto, sctpConfig);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, sctpConfig);
        NetChannel clientSite = connect.get();
        Future<?> send1 = clientSite.sendData("Hello Server, this message form client.\n");

        // server
        listen.waitAnyAccept();

        // Find the channel accepted by server. 
        // Note: ID allocation depends on implementation, finding it might tricky if we don't know ID.
        // But NetListen usually has a way or we can check Neta context.
        // In the UDP test they did: NetChannel server = (NetChannel) neta.getContext().findChannel(3);
        // ID 1 is Listen, ID 2 is Client, ID 3 is Accepted Server-side channel.
        // Let's retry safely.
        ThreadUtils.sleep(500); // wait reliable connection

        NetChannel server = null;
        // Search for the accepted channel (it's not the listener, nor the client)
        // IDs are usually increasing. 
        // 1=ServerListen, 2=ClientChannel, 3=ServerAcceptedChannel (usually)
        server = (NetChannel) neta.getContext().findChannel(3);

        if (server == null) {
            throw new RuntimeException("Server accepted channel not found ID=3");
        }

        Future<?> send2 = server.sendData("Hello Client, this message form server.\n");

        // wait finish
        int count = 0;
        while ((serverRcvData.isEmpty() || clientRcvData.isEmpty()) && count++ < 50) {
            ThreadUtils.sleep(100);
        }

        if (serverRcvData.isEmpty()) {
            throw new RuntimeException("Server did not receive data");
        }
        if (clientRcvData.isEmpty()) {
            throw new RuntimeException("Client did not receive data");
        }

        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        assert clientRcvData.get(0).equals("Hello Client, this message form server.");

        neta.shutdown();
    }
}
