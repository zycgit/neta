/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.tcp;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.Test;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SendAndCloseTest extends AbstractSoTest {
    @Test
    public void fast_1() throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort());

        // server fast close
        NetConfig netConfig = new NetConfig();
        netConfig.setPrintLog(true);
        NetManager server = new NetManager(netConfig);
        server.bind(address, ctx -> {
            ctx.addLastEncoder(new ProtoHandler<Object, Object>() {
                @Override
                public void onActive(ProtoContext c) throws Throwable {
                    c.sendData("Hello Word".getBytes()).onCompleted(f -> {
                        ctx.getChannel().close();
                    });

                }

                @Override
                public ProtoStatus onMessage(ProtoContext c, ProtoRcvQueue<Object> src, ProtoSndQueue<Object> dst) {
                    dst.offerMessage(src.takeMessage(src.queueSize()));
                    return ProtoStatus.Next;
                }
            });
        }, TcpSoConfig.TCP());

        // client
        Socket client = new Socket(address.getHostString(), address.getPort());
        InputStream in = client.getInputStream();
        byte[] byteArray = new byte[1024];
        int read1 = in.read(byteArray);
        assert StringUtils.equals(new String(byteArray, 0, read1), "Hello Word");
        assert in.read() == -1; // The client Socket will only receive end-of-stream (read returns -1).
        server.shutdown();
    }

    @Test
    public void safeClose_1() throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort());

        // server fast close
        NetConfig netConfig = new NetConfig();
        netConfig.setPrintLog(true);
        NetManager server = new NetManager(netConfig);
        NetListen listen = server.bind(address, ctx -> {
        }, TcpSoConfig.TCP());

        listen.onAccept(c -> {
            ((NetChannel) c).sendData("Hello Word".getBytes()).onCompleted(f -> {
                c.close();
            });
        });

        // client
        Socket client = new Socket(address.getHostString(), address.getPort());
        InputStream in = client.getInputStream();
        byte[] byteArray = new byte[1024];
        int read1 = in.read(byteArray);
        assert StringUtils.equals(new String(byteArray, 0, read1), "Hello Word");
        assert in.read() == -1; // The client Socket will only receive end-of-stream (read returns -1).
        server.shutdown();
    }
}
