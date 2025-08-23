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
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoRcvTest extends AbstractStackTest {
    @Test
    public void nextTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        encoderFinishCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoNext,3DecDoNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert queue.poll().getData().equals(123);
    }

    @Test
    public void errorTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doThrowHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoThrow");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("2DecErrThrow,3DecErrNext");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getError() instanceof IllegalArgumentException;
    }

    @Test
    public void retryTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getData().equals(123);
    }

    @Test
    public void againTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoAgain,2DecDoNext,3DecDoNext,1DecDoAgain,2DecDoNext,3DecDoNext,1DecDoAgain,2DecDoNext,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getData().equals(123);
    }

    @Test
    public void againTest_2() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doAgainHandler("1Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRetryHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext,1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext,1DecDoAgain,2DecDoRetry,2DecDoRetry,2DecDoRetry,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getData().equals(123);
    }

    @Test
    public void restartTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doRestartHandler("2Dec", decoderFinishCnt, decoderFailedCnt, 2), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoRestart,1DecDoNext,2DecDoRestart,1DecDoNext,2DecDoRestart,3DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getData().equals(123);
    }

    @Test
    public void exitTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doExitHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        channel.triggerReceive(123); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoExit");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll() == null;
    }

    @Test
    public void interruptTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L2", doInterruptHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L3", doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build(bus);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();
        encoderFinishCnt.clear();
        encoderFailedCnt.clear();

        assert !channel.isClose();
        channel.triggerReceive(123);
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoInterrupt");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert channel.isClose();
    }

    @Test
    public void blackTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();

        Queue<PlayLod> queue = new ArrayDeque<>();
        EventBus bus = new ProtoEventBus();
        bus.subscribe(EventBus.TOPIC_CHANNEL, queue::offer);
        ProtoInitializer initializer = ctx -> {
            ProtoConfig protoConf1 = new ProtoConfig();
            protoConf1.setRcvDownSlotSize(3);
            protoConf1.setSndUpSlotSize(3);

            ProtoConfig protoConf2 = new ProtoConfig();
            protoConf2.setRcvDownSlotSize(2);
            protoConf2.setSndUpSlotSize(2);
            return ProtoHelper.typed(Integer.class, Integer.class)//
                    .nextDecoder("COPY1", protoConf1, doCopyUsingBlackHandler("1Dec", decoderFinishCnt, decoderFailedCnt))//
                    .nextDecoder("COPY2", protoConf2, doCopyUsingBlackHandler("2Dec", decoderFinishCnt, decoderFailedCnt))//
                    .build(bus);
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // do Decoder
        decoderFinishCnt.clear();
        decoderFailedCnt.clear();

        channel.triggerReceive(1, 2, 3); // RCV -> SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("1DecDoNext,2DecDoBack,2DecDoNext");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert queue.poll().getData().equals(1);
        assert queue.poll().getData().equals(2);
        assert queue.poll().getData().equals(3);
    }
}