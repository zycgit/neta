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
package net.hasor.neta.handler.codec.ssl;
import net.hasor.cobble.RandomUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.*;
import net.hasor.neta.handler.codec.LimitFrameHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class AbstractSslTest {
    public static EmbeddedInitializer createProtoStack(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFrameHandler limitFrame = new LimitFrameHandler(2);
        return ctx -> ProtoHelper.embedded(ByteBuf.class, ByteBuf.class)
                // limitFrame
                .nextDuplex("LIMIT", new ProtoDuplexerHandler<>(limitFrame, limitFrame))
                // SSL
                .nextDuplex("SSL", new SslProtoDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
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
    public static ProtoStatus doDecoder1(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<String> dst) {
        List<ByteBuf> bufArray = src.peekMessage(src.queueSize());
        if (bufArray == null || bufArray.size() == 0) {
            return ProtoStatus.Next;
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
            return ProtoStatus.Next;
        }

        ByteBuf tmpBuf = ByteBufAllocator.DEFAULT.buffer();
        int lastIndex = temp.size() - 1;
        for (int i = 0; i < temp.size(); i++) {
            ByteBuf buf = temp.get(i);

            if (i != lastIndex) {
                buf.readBuffer(tmpBuf);
                buf.markReader();
                src.skipMessage(1);
            } else {
                int expect = buf.expect('\n', StandardCharsets.US_ASCII);
                buf.readBuffer(tmpBuf, expect + 1);
                buf.markReader();
                if (buf.readableBytes() <= 0) {
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
        return ProtoStatus.Next;
    }

    /** encoded message: String -> ByteBuf */
    public static ProtoStatus doEncoder1(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<ByteBuf> dst) {
        String message;
        do {
            message = src.takeMessage();
            if (message != null) {
                byte[] bytes = message.getBytes();
                if (bytes.length > 0) {
                    dst.offerMessage(ByteBuf.wrap(bytes));
                }
            }
        } while (message != null);
        return ProtoStatus.Next;
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