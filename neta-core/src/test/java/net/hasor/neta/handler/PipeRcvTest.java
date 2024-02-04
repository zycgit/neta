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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeRcvTest extends AbstractPipeTest {
    @Test
    public void nextTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        encoderFinishCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoNext,3DecDoNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert channel.readRcv().equals(123);
    }

    @Test
    public void errorTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doThrowHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoThrow");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("2DecErrThrow,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv() == null;
    }

    @Test
    public void retryTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv().equals(123);
    }

    @Test
    public void againTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoAgain,2DecDoNext,3DecDoNext,1DecDoAgain,2DecDoNext,3DecDoNext,1DecDoAgain,2DecDoNext,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv().equals(123);
    }

    @Test
    public void againTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext,1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext,1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv().equals(123);
    }

    @Test
    public void restartTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRestartHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoRestart,1DecDoNext,2DecDoRestart,1DecDoNext,2DecDoRestart,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");

        assert channel.readRcv().equals(123);
    }

    @Test
    public void exitTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doExitHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.receive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoExit");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv() == null;
    }

    @Test
    public void interruptTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> PipeHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L2", doInterruptHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L3", doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        try {
            channel.receive(123);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("- Interrupted by L2");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoInterrupt");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv() == null;
    }

    @Test
    public void blackTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> {
            PipeConfig pipConf1 = new PipeConfig();
            pipConf1.setPipeRcvDownStackSize(3);
            pipConf1.setPipeSndUpStackSize(3);

            PipeConfig pipConf2 = new PipeConfig();
            pipConf2.setPipeRcvDownStackSize(2);
            pipConf2.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class)//
                    .nextDecoder("COPY1", pipConf1, doCopyUsingBlackHandler("1Dec", decoderFinishCnt, decoderFailedCnt))//
                    .nextDecoder("COPY2", pipConf2, doCopyUsingBlackHandler("2Dec", decoderFinishCnt, decoderFailedCnt))//
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        channel.receive(1, 2, 3); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoBack,2DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv().equals(1);
        assert channel.readRcv().equals(2);
        assert channel.readRcv().equals(3);
    }

    @Test
    public void skipTest_1() {
        EmbeddedInitializer initializer = (ctx) -> {
            List<String> ignore = new ArrayList<>();
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextDecoder("SKIP", pipConf, doCopyAndSkipHandler("Dec1", ignore, ignore)) // rcv +1
                    .nextDecoder("COPY", pipConf, doCopyHandler("Dec2", ignore, ignore)) // rcv +1
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.receive(1, 2); // SKIP next copy
        channel.receive(3, 4); // in pipline rcv up

        try {
            channel.receive(5, 6);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 0, require 2.");
        }

        assert channel.getPipeStatistical().heapUpOfRcv() == 4;
        assert channel.getPipeStatistical().heapUpOfRcv("SKIP") == 2;
        assert channel.getPipeStatistical().heapUpOfRcv("COPY") == 0;
        assert channel.getPipeStatistical().heapUpOfRcvRoot() == 2;
        assert channel.readRcv() == null;
    }

    @Test
    public void skipTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = (ctx) -> {
            PipeConfig pipConf = new PipeConfig();
            pipConf.setPipeRcvDownStackSize(2);
            pipConf.setPipeSndUpStackSize(2);
            return PipeHelper.embedded(Integer.class, Integer.class, pipConf)//
                    .nextDecoder("SKIP", pipConf, doCopyAndSkipHandler("Dec1", decoderFinishCnt, decoderFailedCnt)) // rcv +1
                    .nextDecoder("COPY1", pipConf, doCopyHandler("Dec2", decoderFinishCnt, decoderFailedCnt)) // rcv +1
                    .nextDecoder("COPY2", pipConf, doCopyHandler("Dec3", decoderFinishCnt, decoderFailedCnt)) // rcv +1
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.receive(1, 2); // SKIP next copy

        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("Dec1Skip,Dec3DoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert channel.readRcv() == null;
    }
}