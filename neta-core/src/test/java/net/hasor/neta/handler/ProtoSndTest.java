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
public class ProtoSndTest extends AbstractStackTest {
    @Test
    public void nextTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        encoderFinishCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert channel.readSnd().equals(123);
    }

    @Test
    public void errorTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doThrowHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd(Err) +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoThrow");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("2EncErrThrow,1EncErrNext");
        assert channel.readSnd() == null;
    }

    @Test
    public void retryTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd().equals(123);
    }

    @Test
    public void againTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd().equals(123);
    }

    @Test
    public void againTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd().equals(123);
    }

    @Test
    public void restartTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRestartHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd().equals(123);
    }

    @Test
    public void exitTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doExitHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Encoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoExit");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd() == null;
    }

    @Test
    public void interruptTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
                .nextDuplex("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L2", doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doInterruptHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
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
            channel.send(123);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("- Interrupted by L2");
        }
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoInterrupt");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd() == null;
    }

    @Test
    public void blackTest_1() {
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = ctx -> {
            ProtoConfig protoConf1 = new ProtoConfig();
            protoConf1.setRcvDownSlotSize(3);
            protoConf1.setSndUpSlotSize(3);

            ProtoConfig protoConf2 = new ProtoConfig();
            protoConf2.setRcvDownSlotSize(2);
            protoConf2.setSndUpSlotSize(2);
            return ProtoHelper.embedded(Integer.class, Integer.class)//
                    .nextEncoder("COPY1", protoConf1, doCopyUsingBlackHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                    .nextEncoder("COPY2", protoConf2, doCopyUsingBlackHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);

        // do Decoder
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.send(1, 2, 3); // RCV -> SND -> NET
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("2EncDoBack,1EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd().equals(1);
        assert channel.readSnd().equals(2);
        assert channel.readSnd().equals(3);
    }

    @Test
    public void skipTest_1() {
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = (ctx) -> {
            ProtoConfig protoConf = new ProtoConfig();
            protoConf.setRcvDownSlotSize(2);
            protoConf.setSndUpSlotSize(2);
            return ProtoHelper.embedded(Integer.class, Integer.class, protoConf)//
                    .nextEncoder("COPY1", protoConf, doCopyHandler("Enc1", encoderFinishCnt, encoderFailedCnt)) //
                    .nextEncoder("COPY2", protoConf, doCopyHandler("Enc2", encoderFinishCnt, encoderFailedCnt)) //
                    .nextEncoder("SKIP", protoConf, doCopyAndSkipHandler("Enc3", encoderFinishCnt, encoderFailedCnt)) //
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.send(1, 2); // SKIP next copy
        channel.send(3, 4); // in ProtoStack rcv up

        try {
            channel.send(5, 6);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 0, require 2.");
        }

        assert channel.getStatistical().heapUpOfSnd() == 4;
        assert channel.getStatistical().heapUpOfSnd("SKIP") == 2;
        assert channel.getStatistical().heapUpOfSnd("COPY2") == 0;
        assert channel.getStatistical().heapUpOfSnd("COPY1") == 0;
        assert channel.getStatistical().heapUpOfSndRoot() == 2;
        assert channel.readSnd() == null;
    }

    @Test
    public void skipTest_2() {
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        EmbeddedInitializer initializer = (ctx) -> {
            ProtoConfig protoConf = new ProtoConfig();
            protoConf.setRcvDownSlotSize(2);
            protoConf.setSndUpSlotSize(2);
            return ProtoHelper.embedded(Integer.class, Integer.class, protoConf)//
                    .nextEncoder("COPY1", protoConf, doCopyHandler("Enc1", encoderFinishCnt, encoderFailedCnt)) //
                    .nextEncoder("COPY2", protoConf, doCopyHandler("Enc2", encoderFinishCnt, encoderFailedCnt)) //
                    .nextEncoder("SKIP", protoConf, doCopyAndSkipHandler("Enc3", encoderFinishCnt, encoderFailedCnt)) //
                    .build();
        };

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
        channel.send(1, 2); // SKIP next copy

        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("Enc3Skip,Enc1DoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.readSnd() == null;
    }
}