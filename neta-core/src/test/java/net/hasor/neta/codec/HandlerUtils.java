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
package net.hasor.neta.codec;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.handler.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent conveyor belt.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class HandlerUtils {

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

    public static ProtoInitializer addListProtoStack(ProtoHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ctx -> ProtoHelper.typed(ByteBuf.class, ByteBuf.class)
                // bytes <-> String
                .nextDuplex("String", HandlerUtils::doDecoder1, HandlerUtils::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }
}