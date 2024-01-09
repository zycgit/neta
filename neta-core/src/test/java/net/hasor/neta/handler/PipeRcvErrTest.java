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
import net.hasor.neta.channel.PipelineFactory;
import net.hasor.neta.handler.PipeBuilder.PipelineBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeRcvErrTest extends AbstractPipeTest {
    @Test
    public void nextTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(errNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrNext,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError().getMessage().equals("Test");
    }

    @Test
    public void errorTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo(errThrowHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        try {
            channel.writeRcvUpError(new IllegalStateException("Test"));
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Test");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrThrow");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError() == null;
    }

    @Test
    public void retryTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrRetry,2DecErrRetry,2DecErrRetry,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError().getMessage().equals("Test");
    }

    @Test
    public void againTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrAgain,2DecErrNext,3DecErrNext,1DecErrAgain,2DecErrNext,3DecErrNext,1DecErrAgain,2DecErrNext,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError().getMessage().equals("Test");
    }

    @Test
    public void againTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrAgain,2DecErrRetry,2DecErrRetry,2DecErrRetry,3DecErrNext,1DecErrAgain,2DecErrRetry,2DecErrRetry,2DecErrRetry,3DecErrNext,1DecErrAgain,2DecErrRetry,2DecErrRetry,2DecErrRetry,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError().getMessage().equals("Test");
    }

    @Test
    public void restartTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errRestartHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrRestart,1DecErrNext,2DecErrRestart,1DecErrNext,2DecErrRestart,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError().getMessage().equals("Test");
    }

    @Test
    public void exitTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo(errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errExitHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo(errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpError(new IllegalStateException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrExit");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError() == null;
    }

    @Test
    public void interruptTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipelineBuilder<Integer, Integer> empty = PipeInitializer.embedded();
        PipelineFactory pipeline = empty//
                .nextTo("L1", errNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo("L2", errInterruptHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextTo("L3", errNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, pipeline, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        try {
            channel.writeRcvUpError(new IllegalStateException("Test"));
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Test");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("1DecErrNext,2DecErrInterrupt");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.getRcvError() == null;
    }
}