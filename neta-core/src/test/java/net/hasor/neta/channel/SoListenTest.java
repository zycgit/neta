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
import net.hasor.neta.handler.PipeInitializer;
import org.junit.Test;

import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoListenTest extends AbstractSoTest {

    @Test
    public void acceptTest_1() throws Exception {
        // start server
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.empty());

        Socket client = new Socket("127.0.0.1", safePort);
        InputStream soInput = client.getInputStream();

        // wait connected
        listen.waitAnyAccept();
        assert listen.getChannelCount() == 1;

        listen.closeNow();
        assert listen.getChannelCount() == 1;
        try {
            Socket badClient = new Socket("127.0.0.1", safePort);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().startsWith("Connection refused");
        }

        server.shutdown();
    }

    @Test
    public void suspendTest_1() throws Exception {
        int safePort = safePort();
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        NetListen listen = server.listen("127.0.0.1", safePort, PipeInitializer.empty());

        listen.suspend();
        Socket testClient1 = new Socket("127.0.0.1", safePort);
        assert listen.getChannelCount() == 0;

        testClient1.setSoTimeout(1000);
        assert testClient1.getInputStream().read() == -1;

        listen.resume();
        Socket testClient2 = new Socket("127.0.0.1", safePort);
        listen.waitAnyAccept();
        assert listen.getChannelCount() == 1;

        testClient2.setSoTimeout(1000);
        try {
            testClient2.getInputStream().read();
            assert false;
        } catch (SocketTimeoutException e) {
            assert true;
        }

        server.shutdown();
    }

    @Test
    public void acceptListener_1() throws Exception {
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        NetListen listen1 = server.listen("127.0.0.1", safePort(), PipeInitializer.empty());
        NetListen listen2 = server.listen("127.0.0.1", safePort(), PipeInitializer.empty());
        int safePort1 = listen1.getListenPort();
        int safePort2 = listen2.getListenPort();

        AtomicInteger atomicListen1 = new AtomicInteger();
        AtomicInteger atomicListen2 = new AtomicInteger();

        listen1.addListener(counter(atomicListen1));
        listen2.addListener(counter(atomicListen2));

        assert atomicListen1.get() == 0;
        assert atomicListen2.get() == 0;
        Socket client1 = new Socket("127.0.0.1", safePort1);
        listen1.waitAnyAccept();
        assert atomicListen1.get() == 1;
        assert atomicListen2.get() == 0;

        assert atomicListen1.get() == 1;
        assert atomicListen2.get() == 0;
        Socket client2 = new Socket("127.0.0.1", safePort2);
        listen2.waitAnyAccept();
        assert atomicListen1.get() == 1;
        assert atomicListen2.get() == 1;

        client1.close();
        listen1.waitIdle();
        assert atomicListen1.get() == 0;
        assert atomicListen2.get() == 1;

        client2.close();
        listen2.waitIdle();
        assert atomicListen1.get() == 0;
        assert atomicListen2.get() == 0;
        server.shutdown();
    }

    @Test
    public void acceptListener_2() throws Exception {
        CobbleSocket server = new CobbleSocket(crateConfig(2, 32));
        NetListen listen = server.listen("127.0.0.1", safePort(), PipeInitializer.empty());
        AtomicInteger atomicListen = new AtomicInteger();

        assert atomicListen.get() == 0;
        assert listen.getChannelCount() == 0;
        Socket client1 = new Socket("127.0.0.1", listen.getListenPort());
        listen.waitAnyAccept();
        assert atomicListen.get() == 0;
        assert listen.getChannelCount() == 1;

        listen.addListener(counter(atomicListen));
        Socket client2 = new Socket("127.0.0.1", listen.getListenPort());
        Thread.sleep(500);

        assert atomicListen.get() == 1;
        assert listen.getChannelCount() == 2;

        server.shutdown();

        assert atomicListen.get() == -1; //because one already existed before we added the counter
        assert listen.getChannelCount() == 0;
    }
}