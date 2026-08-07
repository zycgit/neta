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
package net.hasor.neta.channel.transport.virtual;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Tests for the direct-drive test API on {@link VrtChannel}:
 * {@code receiveDataAndReturning} and {@code sendDataAndReturning}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-08-07
 */
public class VrtChannelDirectTest {

    @Test
    public void receiveDataAndReturning_echo() throws Throwable {
        ProtoInitializer proto = ProtoHelper.typed(String.class, String.class).nextDecoder(new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
                while (src.hasMore()) {
                    dst.offerMessage("Echo " + src.takeMessage());
                }
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager();
        try {
            VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), proto, VrtSoConfig.asDefault());

            // a subscription must NOT observe data produced by the direct entry point
            List<Object> subRcv = new ArrayList<>();
            channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> subRcv.add(data.getData()));

            Object[] out = channel.receiveDataAndReturning("Hello Direct");
            assert out.length == 1;
            assert "Echo Hello Direct".equals(out[0]);
            assert subRcv.isEmpty();
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void receiveDataAndReturning_noOutput() throws Throwable {
        ProtoInitializer proto = ProtoHelper.typed(String.class, String.class).nextDecoder(new ProtoHandler<String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<String> src, ProtoSndQueue<String> dst) {
                while (src.hasMore()) {
                    src.takeMessage();
                }
                return ProtoStatus.Stop;
            }
        }).build();
        NetManager neta = new NetManager();
        try {
            VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), proto, VrtSoConfig.asDefault());
            Object[] out = channel.receiveDataAndReturning("pass");
            assert out != null;
            assert out.length == 0;
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void sendDataAndReturning_passthrough() throws Throwable {
        ProtoInitializer proto = ProtoHelper.typed(String.class, String.class).build();
        NetManager neta = new NetManager();
        try {
            VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(3), proto, VrtSoConfig.asDefault());

            List<Object> subRcv = new ArrayList<>();
            channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> subRcv.add(data.getData()));

            Object[] out = channel.sendDataAndReturning("Hello Send");
            assert out.length == 1;
            assert "Hello Send".equals(out[0]);
            assert subRcv.isEmpty();
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void sendDataAndReturning_duplex() throws Throwable {
        ProtoInitializer proto = ProtoHelper.typed(String.class, String.class).nextDuplex(new net.hasor.neta.channel.ProtoDuplex<String, String, String, String>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv, //
                    ProtoRcvQueue<String> rcvUp, ProtoSndQueue<String> rcvDown,//
                    ProtoRcvQueue<String> sndUp, ProtoSndQueue<String> sndDown) {
                if (!isRcv) {
                    while (sndUp.hasMore()) {
                        sndDown.offerMessage("Encoded " + sndUp.takeMessage());
                    }
                }
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager();
        try {
            VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(4), proto, VrtSoConfig.asDefault());
            Object[] out = channel.sendDataAndReturning("Payload");
            assert out.length == 1;
            assert "Encoded Payload".equals(out[0]);
        } finally {
            neta.shutdown();
        }
    }
}
