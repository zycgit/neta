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
public class PipeSndErrTest extends AbstractPipeTest {
    @Test
    public void nextTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrNext,1EncErrNext");
        assert channel.getSndError().getMessage().equals("Test");
    }

    @Test
    public void errorTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errThrowHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        try {
            channel.writeSndUpError(new IllegalStateException("Test"));
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Test");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrThrow");
        assert channel.getSndError() == null;
    }

    @Test
    public void retryTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrNext");
        assert channel.getSndError().getMessage().equals("Test");
    }

    @Test
    public void againTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrNext,1EncErrAgain,3EncErrNext,2EncErrNext,1EncErrAgain,3EncErrNext,2EncErrNext,1EncErrAgain");
        assert channel.getSndError().getMessage().equals("Test");
    }

    @Test
    public void againTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain,3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain,3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain");
        assert channel.getSndError().getMessage().equals("Test");
    }

    @Test
    public void restartTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRestartHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRestart,3EncErrNext,2EncErrRestart,3EncErrNext,2EncErrRestart,1EncErrNext");
        assert channel.getSndError().getMessage().equals("Test");
    }

    @Test
    public void exitTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().empty();
        PipeStackFactory stack = empty//
                .nextTo(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errExitHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrExit");
        assert channel.getSndError() == null;
    }
}