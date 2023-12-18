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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.PipeStackFactory;
import net.hasor.neta.handler.PipeBuilder.PipeStackBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeSndTest extends AbstractPipeTest {
    @Test
    public void nextTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        encoderFinishCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert channel.readSndDown().equals(123);
    }

    @Test
    public void errorTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doThrowHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd(Err) +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoThrow");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("2EncErrThrow,1EncErrNext");
        assert channel.readSndDown() == null;
    }

    @Test
    public void retryTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown().equals(123);
    }

    @Test
    public void againTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown().equals(123);
    }

    @Test
    public void againTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown().equals(123);
    }

    @Test
    public void restartTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRestartHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown().equals(123);
    }

    @Test
    public void exitTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doExitHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUp(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoExit");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown() == null;
    }

    @Test
    public void interruptTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo("L2", doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doInterruptHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo("L3", doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        try {
            channel.writeSndUp(123);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("- Interrupted by L2");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoInterrupt");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSndDown() == null;
    }
}