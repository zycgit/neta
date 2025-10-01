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
import net.hasor.neta.channel.SoException;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoSndErrTest extends AbstractStackTest {
    @Test
    public void nextTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrNext,1EncErrNext");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void errorTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errThrowHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv(Err)/snd +1
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrThrow");
        assert output.isEmpty();
    }

    @Test
    public void retryTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrNext");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void againTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrNext,1EncErrAgain,3EncErrNext,2EncErrNext,1EncErrAgain,3EncErrNext,2EncErrNext,1EncErrAgain");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void againTest_2() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errAgainHandler("1Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRetryHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain,3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain,3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrAgain");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void restartTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errRestartHandler("2Enc", encoderFinishCnt, encoderFailedCnt, 2))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRestart,3EncErrNext,2EncErrRestart,3EncErrNext,2EncErrRestart,1EncErrNext");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void exitTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errExitHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrExit");
        assert output.isEmpty();
    }

    @Test
    public void interruptTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ctx -> ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDuplex("L1", doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L2", doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), errInterruptHandler("2Enc", encoderFinishCnt, encoderFailedCnt))//
                .nextDuplex("L3", doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), errNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt))//
                .build();

        // use VrtChannel test decoder/encoder
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> output = new ArrayList<>();
        channel.subscribe(data -> {
            output.add(data.getError());
        });

        channel.onSendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrInterrupt");
        assert output.isEmpty();
    }
}