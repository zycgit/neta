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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoSndTest extends AbstractStackTest {
    @Test
    public void nextTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoNext");
        assert output.size() == 1 && output.get(0).equals(123);
    }

    @Test
    public void errorTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doThrowHandler("2Enc", encoderFinishCnt, encoderFailedCnt))// rcv/snd(Err) +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asDefault());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoThrow");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("2EncErrThrow,1EncErrNext");
        assert output.size() == 1 && output.get(0) instanceof IllegalArgumentException;
    }

    @Test
    public void retryTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.size() == 1 && output.get(0).equals(123);
    }

    @Test
    public void againTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain,3EncDoNext,2EncDoNext,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.size() == 1 && output.get(0).equals(123);
    }

    @Test
    public void againTest_2() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain,3EncDoNext,2EncDoRetry,2EncDoRetry,2EncDoRetry,1EncDoAgain");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.size() == 1 && output.get(0).equals(123);
    }

    @Test
    public void restartTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doRestartHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,3EncDoNext,2EncDoRestart,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.size() == 1 && output.get(0).equals(123);
    }

    @Test
    public void exitTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doExitHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(123);// SND -> NET
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoExit");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.isEmpty();
    }

    @Test
    public void interruptTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L2", doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doInterruptHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L3", doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        Future<?> future = channel.sendData(123);
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("3EncDoNext,2EncDoInterrupt");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.isEmpty() && future.getCause().getMessage().equals("Interrupted by L2");
    }

    @Test
    public void blackTest_1() throws Throwable {
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoConfig protoConf1 = new ProtoConfig();
            protoConf1.setRcvDownSlotSize(3);
            protoConf1.setSndUpSlotSize(3);

            ProtoConfig protoConf2 = new ProtoConfig();
            protoConf2.setRcvDownSlotSize(2);
            protoConf2.setSndUpSlotSize(2);
            return ProtoHelper.typed(Integer.class, Integer.class)//
                    .nextEncoder("COPY1", protoConf1, doCopyUsingBlackHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                    .nextEncoder("COPY2", protoConf2, doCopyUsingBlackHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                    .build();
        };

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getData());
        });

        channel.sendData(new Object[] { 1, 2, 3 }); // RCV -> SND -> NET
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("2EncDoBack,1EncDoNext,2EncDoNext,1EncDoNext");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("");
        assert output.size() == 3 && Objects.deepEquals(output.toArray(), new Object[] { 1, 2, 3 });
    }
}