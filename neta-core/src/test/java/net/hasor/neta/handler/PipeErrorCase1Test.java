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
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.CourierPipeHandler;
import net.hasor.neta.handler.PipeBuilder.PipeStackBuilder;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeErrorCase1Test {
    private EmbeddedChannel createChannel(PipeStackFactory pipeStack) {
        EmbeddedSoContext context = new EmbeddedSoContext();
        return new EmbeddedChannel(true, pipeStack, context);
    }

    @Test
    public void throwError() {
        AtomicBoolean error = new AtomicBoolean(false);

        PipeConfig cfg = new PipeConfig();
        PipeStackBuilder<String, String> empty = new PipeInitializer().empty();
        PipeStackFactory pipeStack = empty//
                .nextTo("S1", cfg, new Error1(), new CourierPipeHandler<>())//
                .nextTo("S2", cfg, new Error2(), new CourierPipeHandler<>())//
                .bindReceive(new PipeReceiveListener<Object>() {
                    @Override
                    public void onReceive(SoChannel<?> channel, Object data) {
                        error.set(false);
                    }

                    @Override
                    public void onError(SoChannel<?> channel, Throwable e) {
                        error.set(e.getMessage().equals("test error."));
                    }
                }).buildFactory();
        EmbeddedChannel channel = createChannel(pipeStack);

        //
        channel.writeRcvUp("hello");

        assert error.get();
    }

    private static class Error1 implements PipeHandler<String, String> {
        @Override
        public PipeStatus doHandler(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
            throw new IllegalArgumentException("test error.");
        }

        @Override
        public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
            assert e.getMessage().equals("test error.");

            return PipeStatus.Next;
        }
    }

    private static class Error2 implements PipeHandler<String, String> {
        @Override
        public PipeStatus doHandler(PipeContext context, PipeRcvQueue<String> src, PipeSndQueue<String> dst) {
            assert false;
            return PipeStatus.Next;
        }

        @Override
        public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
            assert e.getMessage().equals("test error.");

            return PipeStatus.Next;
        }
    }

}