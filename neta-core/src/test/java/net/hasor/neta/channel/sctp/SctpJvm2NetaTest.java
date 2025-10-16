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
package net.hasor.neta.channel.sctp;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hasor.neta.channel.AbstractSoTest.globalConf;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

public class SctpJvm2NetaTest {
    private boolean checkSupport() {
        try {
            com.sun.nio.sctp.SctpChannel.open();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    public void jvm2Neta() throws Throwable {
        if (!checkSupport()) {
            return;
        }

        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());

        AtomicBoolean sctpRead = new AtomicBoolean(false);
        ProtoInitializer initializer = ProtoHelper.typed(SctpMessage.class, ByteBuf.class).nextDecoder((ProtoHandler<SctpMessage, String>) (context, src, dst) -> {
            while (src.hasMore()) {
                SctpMessage msg = src.takeMessage();
                ByteBuf data = msg.getByteBuf();
                int len = data.readableBytes();
                byte[] bytes = new byte[len];
                data.readBytes(bytes);
                // The frame decoder usually splits lines or we just read raw bytes.
                // Assuming Neta SCTP handler delivers ByteBuf payload.
                String rcv = new String(bytes);
                if (StringUtils.equals(rcv, "Hello SCTP")) {
                    sctpRead.set(true);
                }
                data.markReader();
            }
            return ProtoStatus.Next;
        }).build();

        SctpSoConfig sctpConf = new SctpSoConfig();
        sctpConf.setSoReadTimeoutMs(-1);
        neta.bind(address, initializer, sctpConf);

        try {
            // Native Java SCTP Client
            SctpChannel socket = SctpChannel.open();
            socket.connect(address);

            byte[] sendData = "Hello SCTP".getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.wrap(sendData);
            MessageInfo info = MessageInfo.createOutgoing(null, 0); // stream 0
            socket.send(buf, info);
            ThreadUtils.sleep(200);
            socket.close();

            int i = 100 * 50; // max 5 sec
            while (!sctpRead.get()) {
                ThreadUtils.sleep(100);
                i--;
                if (i <= 0) {
                    break;
                }
            }
            if (!sctpRead.get()) {
                throw new RuntimeException("Test timed out - did not receive data");
            }
        } finally {
            neta.shutdown();
        }
    }
}
