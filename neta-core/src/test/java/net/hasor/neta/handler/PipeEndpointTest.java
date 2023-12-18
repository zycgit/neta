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

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PipeEndpointTest extends AbstractPipeTest {

    @Test
    public void rcvHeapUpTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeConfig pipConf = new PipeConfig();
        pipConf.setPipeRcvDownStackSize(3);
        pipConf.setPipeSndUpStackSize(4);
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().pipeConfig(pipConf);
        PipeStackFactory stack = empty//
                .nextTo("L1", doCopyHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L2", doCopyHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L3", doNotCopyHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpArray(new Object[] { 1, 2, 3 });
        channel.writeRcvUpArray(new Object[] { 4, 5 });
        channel.writeRcvUpArray(new Object[] { 6, 7, 8 });
        try {
            channel.writeRcvUpArray(new Object[] { 9, 10, 11 });
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 1, require 3.");
        }

        assert channel.getPipeStatistical().heapUpOfRcv() == 8;
        assert channel.getPipeStatistical().heapUpOfRcv("L2") == 3;
        assert channel.getPipeStatistical().heapUpOfRcv("L1") == 3;
        assert channel.getPipeStatistical().heapUpOfRcvRoot() == 2;
        assert channel.readRcvDown() == null;

        System.out.println(channel.pipeStack);
    }

    @Test
    public void rcvHeapUpTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeConfig pipConf = new PipeConfig();
        pipConf.setPipeRcvDownStackSize(3);
        pipConf.setPipeSndUpStackSize(4);
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().pipeConfig(pipConf);
        PipeStackFactory stack = empty//
                .nextTo("L1", doCopyHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L2", doCopyHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L3", doCopyHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeRcvUpArray(new Object[] { 1, 2, 3 });
        channel.writeRcvUpArray(new Object[] { 4, 5 });
        channel.writeRcvUpArray(new Object[] { 6, 7, 8 });
        channel.writeRcvUpArray(new Object[] { 9, 10, 11 });

        assert channel.getPipeStatistical().heapUpOfRcv() == 0;
        assert channel.getPipeStatistical().heapUpOfRcv("L2") == 0;
        assert channel.getPipeStatistical().heapUpOfRcv("L1") == 0;
        assert channel.getPipeStatistical().heapUpOfRcvRoot() == 0;
        assert channel.getRcvDownSize() == 11;
    }

    @Test
    public void sndHeapUpTest_1() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeConfig pipConf = new PipeConfig();
        pipConf.setPipeRcvDownStackSize(3);
        pipConf.setPipeSndUpStackSize(4);
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().pipeConfig(pipConf);
        PipeStackFactory stack = empty//
                .nextTo("L1", doCopyHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNotCopyHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L2", doCopyHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L3", doCopyHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpArray(new Object[] { 1, 2, 3 });
        channel.writeSndUpArray(new Object[] { 4, 5 });
        channel.writeSndUpArray(new Object[] { 6, 7, 8 });
        channel.writeSndUpArray(new Object[] { 9, 10, 11 });
        try {
            channel.writeSndUpArray(new Object[] { 12, 13, 14 });
            assert false;
        } catch (Exception e) {
            assert e.getMessage().endsWith("available slot is 1, require 3.");
        }

        assert channel.getPipeStatistical().heapUpOfSnd() == 11;
        assert channel.getPipeStatistical().heapUpOfSnd("L3") == 4;
        assert channel.getPipeStatistical().heapUpOfSnd("L2") == 4;
        assert channel.getPipeStatistical().heapUpOfSnd("L1") == 0;
        assert channel.getPipeStatistical().heapUpOfSndRoot() == 3;
        assert channel.readRcvDown() == null;

        System.out.println(channel.pipeStack);
    }

    @Test
    public void sndHeapUpTest_2() {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        PipeConfig pipConf = new PipeConfig();
        pipConf.setPipeRcvDownStackSize(3);
        pipConf.setPipeSndUpStackSize(4);
        PipeStackBuilder<Integer, Integer> empty = new PipeInitializer().pipeConfig(pipConf);
        PipeStackFactory stack = empty//
                .nextTo("L1", doCopyHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L2", doCopyHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextTo("L3", doCopyHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doCopyHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .buildFactory();

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel channel = new EmbeddedChannel(true, stack, context);

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();
        channel.writeSndUpArray(new Object[] { 1, 2, 3 });
        channel.writeSndUpArray(new Object[] { 4, 5 });
        channel.writeSndUpArray(new Object[] { 6, 7, 8 });
        channel.writeSndUpArray(new Object[] { 9, 10, 11 });
        channel.writeSndUpArray(new Object[] { 12, 13, 14 });

        assert channel.getPipeStatistical().heapUpOfSnd() == 0;
        assert channel.getPipeStatistical().heapUpOfSnd("L3") == 0;
        assert channel.getPipeStatistical().heapUpOfSnd("L2") == 0;
        assert channel.getPipeStatistical().heapUpOfSnd("L1") == 0;
        assert channel.getPipeStatistical().heapUpOfSndRoot() == 0;
        assert channel.readRcvDown() == null;

        System.out.println(channel.pipeStack);
    }
}