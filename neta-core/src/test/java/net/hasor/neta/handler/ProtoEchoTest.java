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
package net.hasor.neta.handler;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.frames.TypeFrame;
import net.hasor.neta.handler.frames.TypeRequest;
import net.hasor.neta.handler.frames.TypeResponse;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoEchoTest {
    private EmbeddedChannel createChannel(EmbeddedInitializer initializer) {
        EmbeddedSoContext context = new EmbeddedSoContext();
        return new EmbeddedChannel(true, initializer, context);
    }

    @Test
    public void embeddedEcho() {
        //  Data        Frame        Req/Res
        // String -> TypeFrame -> TypeRequest
        // String <- TypeFrame <- TypeResponse
        ProtoConfig protoConf = new ProtoConfig();
        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.embedded(String.class, String.class)//
                .nextDuplex("TypeFrame", protoConf, ProtoEchoTest::doDecoder1, ProtoEchoTest::doEncoder1)
                // TypeFrame -> TypeRequest and TypeResponse -> TypeFrame
                .nextDuplex("TypeRequest/Response", protoConf, ProtoEchoTest::doDecoder2, ProtoEchoTest::doEncoder2)
                // build
                .build();
        EmbeddedChannel channel = createChannel(initializer);

        //
        channel.receive("hello");
        TypeRequest request = (TypeRequest) channel.readRcv();
        assert request.getHeader().equals("TypeFrame>TypeRequest");
        assert request.getMessage().equals("hello");

        channel.send(new TypeResponse(request.getHeader(), "echo hello"));
        String response = (String) channel.readSnd();
        assert response.equals("TypeFrame>TypeRequest>TypeResponse>TypeFrame echo hello");
    }

    /** Decoding the message: String -> TypeFrame */
    public static ProtoStatus doDecoder1(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<TypeFrame> dst) {
        String line;
        do {
            line = src.takeMessage();
            if (line != null) {
                dst.offerMessage(new TypeFrame("TypeFrame", line));
            }
        } while (line != null && dst.hasSlot());

        return ProtoStatus.Next;
    }

    /** encoded message: TypeFrame -> String */
    public static ProtoStatus doEncoder1(ProtoContext context, ProtoRcvQueue<TypeFrame> src, ProtoSndQueue<String> dst) {
        do {
            TypeFrame message = src.takeMessage();
            if (message != null) {
                String header = message.getHeader() + ">TypeFrame ";
                dst.offerMessage(header + message.getMessage());
            }
        } while (src.hasMore());

        return ProtoStatus.Next;
    }

    /** Decoding the message: TypeFrame -> TypeRequest */
    public static ProtoStatus doDecoder2(ProtoContext context, ProtoRcvQueue<TypeFrame> src, ProtoSndQueue<TypeRequest> dst) {
        TypeFrame frame;
        do {
            frame = src.takeMessage();
            if (frame != null) {
                String header = frame.getHeader() + ">TypeRequest";
                dst.offerMessage(new TypeRequest(header, frame.getMessage()));
            }
        } while (frame != null && dst.hasSlot());
        return ProtoStatus.Next;
    }

    /** encoded message: TypeResponse -> TypeFrame */
    public static ProtoStatus doEncoder2(ProtoContext context, ProtoRcvQueue<TypeResponse> src, ProtoSndQueue<TypeFrame> dst) {
        TypeResponse response;
        do {
            response = src.takeMessage();
            if (response != null) {
                String header = response.getHeader() + ">TypeResponse";
                dst.offerMessage(new TypeFrame(header, response.getMessage()));
            }
        } while (response != null && dst.hasSlot());
        return ProtoStatus.Next;
    }
}