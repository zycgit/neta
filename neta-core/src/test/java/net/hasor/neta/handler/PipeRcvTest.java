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
import net.hasor.neta.channel.PipeStackFactory;
import net.hasor.neta.handler.PipeBuilder.PipeStackBuilder;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeRcvTest extends AbstractPipeTest {
    @Test
    public void nextTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        encoderFinishCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 3;
        assert encoderFinishCnt.get() == 3;
        assert channel.readRcvDown().equals(123);
    }

    @Test
    public void errorTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doThrowHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 2;
        assert decoderFailedCnt.get() == 2;
        assert encoderFinishCnt.get() == 3;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown() == null;
    }

    @Test
    public void retryTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))            //
                .nextTo(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))            //
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 5;
        assert decoderFailedCnt.get() == 0;
        assert encoderFinishCnt.get() == 3;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown().equals(123);
    }

    @Test
    public void againTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))            //
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 9;
        assert decoderFailedCnt.get() == 0;
        assert encoderFinishCnt.get() == 3;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown().equals(123);
    }

    @Test
    public void againTest_2() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))            //
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 15;
        assert decoderFailedCnt.get() == 0;
        assert encoderFinishCnt.get() == 3;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown().equals(123);
    }

    @Test
    public void restartTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doRestartHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))            //
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 7;
        assert decoderFailedCnt.get() == 0;
        assert encoderFinishCnt.get() == 3;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown().equals(123);
    }

    @Test
    public void exitTest_1() {
        AtomicInteger decoderFinishCnt = new AtomicInteger(0);
        AtomicInteger decoderFailedCnt = new AtomicInteger(0);
        AtomicInteger encoderFinishCnt = new AtomicInteger(0);
        AtomicInteger encoderFailedCnt = new AtomicInteger(0);

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doExitHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.set(0);
        decoderFailedCnt.set(0);
        encoderFinishCnt.set(0);
        encoderFailedCnt.set(0);
        channel.writeRcvUp(123); // RCV -> SND -> NET
        assert decoderFinishCnt.get() == 2;
        assert decoderFailedCnt.get() == 0;
        assert encoderFinishCnt.get() == 2;
        assert encoderFailedCnt.get() == 0;
        assert channel.readRcvDown() == null;
    }
}