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
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeNextTest {
    @Test
    public void nextTest_1() {
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory pipeStack = empty                              //
                .nextTo(PipeNextTest::courier, PipeNextTest::increment) // snd +1
                .nextTo(PipeNextTest::courier, PipeNextTest::increment) // snd +1
                .nextTo(PipeNextTest::courier, PipeNextTest::increment) // snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, pipeStack, context);
        EmbeddedChannel client = new EmbeddedChannel(false, pipeStack, context);

        //
        client.writeSndUp(0);
        assert client.readSndDown().equals(3); // send use increment, result +3
        server.writeRcvUp(3);
        assert server.readRcvDown().equals(3); // rcv use courier, result no change
    }

    @Test
    public void nextTest_2() {
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory pipeStack = empty      //
                .nextTo(PipeNextTest::duplexer) // snd +1
                .nextTo(PipeNextTest::duplexer) // snd +1
                .nextTo(PipeNextTest::duplexer) // snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeStack, context);

        //
        channel.writeSndUp(0);
        assert channel.readSndDown().equals(3); // send use increment, result +3
        channel.writeRcvUp(0);
        assert channel.readRcvDown().equals(3); // rcv use courier, result no change
    }

    private static PipeStatus increment(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
        dst.offerMessage(src.takeMessage() + 1);
        return PipeStatus.Next;
    }

    private static PipeStatus courier(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
        dst.offerMessage(src.takeMessage());
        return PipeStatus.Next;
    }

    private static PipeStatus duplexer(PipeContext context, boolean isRcv, //
            PipeRcvQueue<Integer> rcvUp, PipeSndQueue<Integer> rcvDown,    //
            PipeRcvQueue<Integer> sndUp, PipeSndQueue<Integer> sndDown) {
        if (isRcv) {
            rcvDown.offerMessage(rcvUp.takeMessage() + 1);
        } else {
            sndDown.offerMessage(sndUp.takeMessage() + 1);
        }
        return PipeStatus.Next;
    }

    @Test
    public void nextTest_3() {
        AtomicBoolean secondFinish = new AtomicBoolean(false);
        AtomicBoolean thrdFinish = new AtomicBoolean(false);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory pipeStack = empty  //
                .nextTo(nextTest_3_first()) //
                .nextTo(nextTest_3_second(secondFinish))//
                .nextTo(nextTest_3_thrd(thrdFinish))  //
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(false, pipeStack, context);

        //
        channel.writeRcvUp(0);
        assert channel.readRcvDown() == null;
        assert secondFinish.get();
        assert thrdFinish.get();
    }

    private static PipeLayer<Integer, Integer, Integer, Integer> nextTest_3_first() {
        return (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage() + 1);
            } else {
                sndDown.offerMessage(sndUp.takeMessage() + 1);
            }
            return PipeStatus.Next;
        };
    }

    private static PipeLayer<Integer, Integer, Integer, Integer> nextTest_3_second(AtomicBoolean secondFinish) {
        return new PipeLayer<Integer, Integer, Integer, Integer>() {

            @Override
            public PipeStatus doLayer(PipeContext context, boolean isRcv,       //
                    PipeRcvQueue<Integer> rcvUp, PipeSndQueue<Integer> rcvDown, //
                    PipeRcvQueue<Integer> sndUp, PipeSndQueue<Integer> sndDown) {
                throw new IndexOutOfBoundsException("test Failed");
            }

            @Override
            public PipeStatus doError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHandler eh) {
                assert e.getMessage().equals("test Failed");
                secondFinish.set(true);
                return PipeStatus.Next;
            }
        };
    }

    private static PipeLayer<Integer, Integer, Integer, Integer> nextTest_3_thrd(AtomicBoolean thrdFinish) {
        return new PipeLayer<Integer, Integer, Integer, Integer>() {
            @Override
            public PipeStatus doLayer(PipeContext context, boolean isRcv,       //
                    PipeRcvQueue<Integer> rcvUp, PipeSndQueue<Integer> rcvDown, //
                    PipeRcvQueue<Integer> sndUp, PipeSndQueue<Integer> sndDown) {
                assert false;
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHandler eh) {
                assert e.getMessage().equals("test Failed");
                thrdFinish.set(true);
                return PipeStatus.Next;
            }
        };
    }
}