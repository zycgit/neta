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
package net.hasor.neta.handler.ssl;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeStackFactory;
import net.hasor.neta.codec.LimitFramePipeHandler;
import net.hasor.neta.handler.*;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslPemHandshakeTest {
    @Test
    public void sslHandshakeTest_1() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setSsllog(true);

        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        PipeConfig pipeConfig = new PipeConfig();
        LimitFramePipeHandler limitFrame = new LimitFramePipeHandler(2);
        PipeStackFactory pipeStack = new PipeInitializer()
                // limitFrame
                .nextTo("LIMIT", pipeConfig, new PipeDuplexLayer<>(limitFrame, limitFrame))
                // SSL
                .nextTo("SSL", pipeConfig, new SslPipeLayer(sslConfig))
                // bytes <-> String
                .nextTo("String", pipeConfig, SslPemHandshakeTest::doDecoder1, SslPemHandshakeTest::doEncoder1)
                // create Stack
                .buildFactory();

        //
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, pipeStack, context);
        EmbeddedChannel client = new EmbeddedChannel(false, pipeStack, context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.writeSndUp("Hello Server, this message form client.\n");
        server.writeSndUp("Hello Client, this message form server.\n");

        // mock network transfer
        for (int i = 0; i < 10; i++) {
            System.out.println("trun " + (i++));
            transfer.transferToServer(); // copy client to server
            transfer.transferToClient(); // copy server to client
        }

        String clientRcv = client.readRcvDown();
        String serverRcv = server.readRcvDown();
        assert clientRcv.equals("Hello Client, this message form server.");
        assert serverRcv.equals("Hello Server, this message form client.");
    }

    /** Decoding the message: ByteBuf -> String */
    public static PipeStatus doDecoder1(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
        ByteBuf byteBuf = src.takeMessage();
        if (byteBuf == null) {
            return PipeStatus.Next;
        }
        String line;
        do {
            line = byteBuf.readLine();
            if (line != null) {
                dst.offerMessage(line);
            }
        } while (line != null && dst.hasSlot());

        byteBuf.markReader();
        return PipeStatus.Next;
    }

    /** encoded message: String -> ByteBuf */
    public static PipeStatus doEncoder1(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<ByteBuf> dst) {
        String message;
        do {
            message = src.takeMessage();
            if (message != null) {
                byte[] bytes = message.getBytes();
                if (bytes.length > 0) {
                    dst.offerMessage(ByteBufAllocator.DEFAULT.wrap(bytes));
                }
            }
        } while (message != null);
        return PipeStatus.Next;
    }
}