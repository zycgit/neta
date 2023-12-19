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
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeStackFactory;
import net.hasor.neta.handler.PipeBuilder.PipeStackBuilder;
import net.hasor.neta.handler.frames.TypeFrame;
import net.hasor.neta.handler.frames.TypeRequest;
import net.hasor.neta.handler.frames.TypeResponse;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeEchoTest {
    private EmbeddedChannel createChannel(PipeStackFactory pipeStack) {
        EmbeddedSoContext context = new EmbeddedSoContext();
        return new EmbeddedChannel(true, pipeStack, context);
    }

    @Test
    public void embeddedEcho() {
        //  Data        Frame        Req/Res
        // String -> TypeFrame -> TypeRequest
        // String <- TypeFrame <- TypeResponse
        PipeConfig pipeConfig = new PipeConfig();
        PipeStackBuilder<String, String> empty = new PipeInitializer().empty();
        PipeStackFactory pipeStack = empty
                // String <-> TypeFrame
                .nextTo("TypeFrame", pipeConfig, PipeEchoTest::doDecoder1, PipeEchoTest::doEncoder1)
                // TypeFrame -> TypeRequest and TypeResponse -> TypeFrame
                .nextTo("TypeRequest/Response", pipeConfig, PipeEchoTest::doDecoder2, PipeEchoTest::doEncoder2)
                // create Stack
                .buildFactory();
        EmbeddedChannel channel = createChannel(pipeStack);

        //
        channel.writeRcvUp("hello");
        TypeRequest request = (TypeRequest) channel.readRcvDown();
        assert request.getHeader().equals("TypeFrame>TypeRequest");
        assert request.getMessage().equals("hello");

        channel.writeSndUp(new TypeResponse(request.getHeader(), "echo hello"));
        String response = (String) channel.readSndDown();
        assert response.equals("TypeFrame>TypeRequest>TypeResponse>TypeFrame echo hello");
    }

    /** Decoding the message: String -> TypeFrame */
    public static PipeStatus doDecoder1(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<TypeFrame> dst) {
        String line;
        do {
            line = src.takeMessage();
            if (line != null) {
                dst.offerMessage(new TypeFrame("TypeFrame", line));
            }
        } while (line != null && dst.hasSlot());

        return PipeStatus.Next;
    }

    /** encoded message: TypeFrame -> String */
    public static PipeStatus doEncoder1(PipeContext context, PipeRcvQueue<TypeFrame> src, PipeSndQueue<String> dst) {
        do {
            TypeFrame message = src.takeMessage();
            if (message != null) {
                String header = message.getHeader() + ">TypeFrame ";
                dst.offerMessage(header + message.getMessage());
            }
        } while (src.hasMore());

        return PipeStatus.Next;
    }

    /** Decoding the message: TypeFrame -> TypeRequest */
    public static PipeStatus doDecoder2(PipeContext context, PipeRcvQueue<TypeFrame> src, PipeSndQueue<TypeRequest> dst) {
        TypeFrame frame;
        do {
            frame = src.takeMessage();
            if (frame != null) {
                String header = frame.getHeader() + ">TypeRequest";
                dst.offerMessage(new TypeRequest(header, frame.getMessage()));
            }
        } while (frame != null && dst.hasSlot());
        return PipeStatus.Next;
    }

    /** encoded message: TypeResponse -> TypeFrame */
    public static PipeStatus doEncoder2(PipeContext context, PipeRcvQueue<TypeResponse> src, PipeSndQueue<TypeFrame> dst) {
        TypeResponse response;
        do {
            response = src.takeMessage();
            if (response != null) {
                String header = response.getHeader() + ">TypeResponse";
                dst.offerMessage(new TypeFrame(header, response.getMessage()));
            }
        } while (response != null && dst.hasSlot());
        return PipeStatus.Next;
    }
}