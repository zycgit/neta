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
import net.hasor.cobble.RandomUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeInitializer;
import net.hasor.neta.codec.LimitFramePipeHandler;
import net.hasor.neta.handler.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class AbstractSslTest {
    public static PipeInitializer createPipeline(SslConfig sslConf, PipeHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFramePipeHandler limitFrame = new LimitFramePipeHandler(2);
        return new PipeHelper()
                // limitFrame
                .nextDuplex("LIMIT", new PipeDuplexHandler<>(limitFrame, limitFrame))
                // SSL
                .nextDuplex("SSL", new SslPipeLayer(sslConf))
                // bytes <-> String
                .nextHandler("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }

    public static PipeInitializer createPipeline(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFramePipeHandler limitFrame = new LimitFramePipeHandler(2);
        return new PipeHelper()
                // limitFrame
                .nextDuplex("LIMIT", new PipeDuplexHandler<>(limitFrame, limitFrame))
                // SSL
                .nextDuplex("SSL", new SslPipeLayer(sslConf))
                // bytes <-> String
                .nextHandler("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    public static void transfer(EmbeddedTransfer transfer, int turn, int copyPacket) {
        // mock network transfer
        for (int i = 0; i < turn; i++) {
            transfer.transferToServer(copyPacket); // copy client to server
            transfer.transferToClient(copyPacket); // copy server to client
        }
    }

    /** Decoding the message: ByteBuf -> String */
    public static PipeStatus doDecoder1(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
        List<ByteBuf> bufArray = src.peekMessage(src.queueSize());
        if (bufArray == null || bufArray.size() == 0) {
            return PipeStatus.Next;
        }

        List<ByteBuf> temp = new ArrayList<>();
        boolean hasLine = false;
        for (ByteBuf buf : bufArray) {
            temp.add(buf);
            if (buf.hasLine()) {
                hasLine = true;
                break;
            }
        }
        if (!hasLine) {
            return PipeStatus.Next;
        }

        ByteBuf tmpBuf = ByteBufAllocator.DEFAULT.arrayBuffer();
        int lastIndex = temp.size() - 1;
        for (int i = 0; i < temp.size(); i++) {
            ByteBuf buf = temp.get(i);

            if (i != lastIndex) {
                buf.read(tmpBuf);
                buf.markReader();
                src.skipMessage(1);
            } else {
                int expect = buf.expect('\n', StandardCharsets.US_ASCII);
                buf.read(tmpBuf, expect + 1);
                buf.markReader();
                if (!buf.hasReadable()) {
                    src.skipMessage(1);
                }
            }
        }
        tmpBuf.markWriter();

        //
        String line = tmpBuf.readLine();
        if (line != null) {
            dst.offerMessage(line);
        }
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

    protected Object[] biasedArray(Object[] data) {
        List<Object> list = new ArrayList<>(Arrays.asList(data));
        ArrayList<Object> result = new ArrayList<>();
        int cnt = list.size();

        for (int i = 0; i < cnt; i++) {
            int idx = RandomUtils.nextInt(0, list.size() - 1);
            result.add(list.remove(idx));
        }
        return result.toArray();
    }
}