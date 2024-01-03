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
import net.hasor.neta.handler.PipeInitializer;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoReadTest extends AbstractSoTest {
    @Test
    public void echoTest() throws Exception {
        int safePort = safePort();
        AtomicInteger cnt = new AtomicInteger();

        // echo pipeline
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        server.listen("127.0.0.1", safePort, PipeInitializer.builder((channel, data) -> {
            ((NetChannel) channel).sendData(data); // echo
            cnt.incrementAndGet();// packet ++
        }));

        // send data
        Socket client = new Socket("127.0.0.1", safePort);
        OutputStream soOut = client.getOutputStream();
        byte[] sndBytes = "Hello\n".getBytes();
        soOut.write(sndBytes);
        soOut.flush();

        Thread.sleep(1000); // wait network transfer

        // read echo data
        byte[] rcvBytes = new byte[sndBytes.length];
        InputStream soIn = client.getInputStream();
        soIn.read(rcvBytes);

        assert "Hello\n".equals(new String(rcvBytes));
        assert cnt.get() == 3;
        server.shutdown();
    }

    @Test
    public void rcvFullTest() throws Exception {
        // start server
        int safePort = safePort();
        SoConfig soConfig = crateConfig(2, 30);
        soConfig.setSoRcvBuf(32);
        soConfig.setNetlog(false);
        CobbleSocket server = new CobbleSocket(soConfig);
        SoContext context = server.getContext();
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.empty());

        // client send
        Socket client = new Socket("127.0.0.1", safePort);
        client.setSendBufferSize(32 * 3);
        OutputStream soOut = client.getOutputStream();
        soOut.write(RandomUtils.nextBytes(32 * 3));
        soOut.flush();
        listen.waitAnyAccept();

        // wait full.
        NetChannel channel = (NetChannel) context.findChannel(2);
        while (channel.getReceivedBytes() < 30) {
            ThreadUtils.sleep(100);
        }

        assert channel.getReceivedBytes() == 30;
        ThreadUtils.sleep(500);
        assert channel.getReceivedBytes() == 30;
        ThreadUtils.sleep(500);

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
}