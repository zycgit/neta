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
package net.hasor.neta.channel;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoEventTest extends AbstractStackTest {
    public static ProtoHandler<Integer, Integer> theHandler(String tag, List<String> record) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public void onInit(ProtoContext context) {
                record.add(tag + "-OnInit");
            }

            @Override
            public void onActive(ProtoContext context) {
                record.add(tag + "-OnActive");
            }

            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                record.add(tag + "-OnUserEvent");
                return true;
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                record.add(tag + "-OnMessage");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                record.add(tag + "-OnError");
                return ProtoStatus.Next;
            }
        };
    }

    @Test
    public void eventTest_0() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder(theHandler("dec1", record));
            ctx.addLastDecoder(theHandler("dec2", record));

            ctx.addLastEncoder(theHandler("enc1", record));
            ctx.addLastEncoder(theHandler("enc2", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.fireUserEvent(ProtoEventTest.class, this);

        assert record.size() == 12;
        assert record.get(0).equals("dec1-OnInit");
        assert record.get(1).equals("dec2-OnInit");
        assert record.get(2).equals("enc1-OnInit");
        assert record.get(3).equals("enc2-OnInit");
        assert record.get(4).equals("dec1-OnActive");
        assert record.get(5).equals("dec2-OnActive");
        assert record.get(6).equals("enc1-OnActive");
        assert record.get(7).equals("enc2-OnActive");
        //
        assert record.get(8).equals("dec1-OnUserEvent");
        assert record.get(9).equals("dec2-OnUserEvent");
        assert record.get(10).equals("enc2-OnUserEvent");
        assert record.get(11).equals("enc1-OnUserEvent");
        neta.shutdown();
    }

    @Test
    public void eventTest_1() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder(theHandler("dec1", record));
            ctx.addLastDecoder(theHandler("dec2", record));

            ctx.addLastDecoder("s1", (context, src, dst) -> {
                context.fireUserEvent(ProtoEventTest.class, this);
                return ProtoStatus.Next;
            });

            ctx.addLastEncoder(theHandler("enc1", record));
            ctx.addLastEncoder(theHandler("enc2", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.onReceive(1);

        assert record.size() == 14;
        assert record.get(8).equals("dec1-OnMessage");
        assert record.get(9).equals("dec2-OnMessage");
        assert record.get(10).equals("enc2-OnUserEvent");
        assert record.get(11).equals("enc1-OnUserEvent");
        assert record.get(12).equals("enc2-OnMessage");
        assert record.get(13).equals("enc1-OnMessage");
        neta.shutdown();
    }
}