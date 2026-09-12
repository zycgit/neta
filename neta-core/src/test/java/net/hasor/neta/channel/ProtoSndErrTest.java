/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;

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

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
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

        channel.sendError(new SoException("Test"));
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

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
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

        channel.sendError(new SoException("Test"));
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

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
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

        channel.sendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrRetry,2EncErrRetry,2EncErrRetry,1EncErrNext");
        assert output.size() == 1 && ((Throwable) output.get(0)).getMessage().equals("Test");
    }

    @Test
    public void exitTest_1() throws Throwable {
        List<String> decoderFinishCnt = new ArrayList<>();
        List<String> decoderFailedCnt = new ArrayList<>();
        List<String> encoderFinishCnt = new ArrayList<>();
        List<String> encoderFailedCnt = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
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

        channel.sendError(new SoException("Test"));
        assert StringUtils.join(decoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(decoderFailedCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFinishCnt.toArray(), ",").equals("");
        assert StringUtils.join(encoderFailedCnt.toArray(), ",").equals("3EncErrNext,2EncErrExit");
        assert output.isEmpty();
    }
}
