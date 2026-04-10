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
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoStackTest extends AbstractStackTest {
    public static ProtoHandler<Integer, Integer> recordHandler(String tag, List<String> record) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public void onInit(String name, int poolSize, ProtoContext context) {
                record.add(tag + "-OnInit");
            }

            @Override
            public void onActive(ProtoContext context) {
                record.add(tag + "-OnActive");
            }

            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                record.add(tag + "-OnEvent");
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

    private static ProtoBuildContext asBuildContext(ProtoContext context) {
        return (ProtoBuildContext) context;
    }

    @Test
    public void initAddTest_0() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder("test", new ProtoHandler<Integer, Integer>() {
                @Override
                public void onInit(String name, int poolSize, ProtoContext context) {
                    record.add("s1-OnInit");
                    ProtoHandler<Integer, Integer> decode = recordHandler("s2", record);
                    asBuildContext(context).addLastDecoder("s2", decode);
                }

                @Override
                public void onActive(ProtoContext context) {
                    record.add("s1-OnActive");
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                    record.add("s1-OnMessage");
                    dst.offerMessage(src.takeMessage(src.queueSize()));
                    return ProtoStatus.Next;
                }
            });

        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);

        assert record.size() == 6;
        assert record.get(0).equals("s1-OnInit");
        assert record.get(1).equals("s2-OnInit");
        assert record.get(2).equals("s1-OnActive");
        assert record.get(3).equals("s2-OnActive");
        assert record.get(4).equals("s1-OnMessage");
        assert record.get(5).equals("s2-OnMessage");
        neta.shutdown();
    }

    @Test
    public void activeAddTest_0() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder("test", new ProtoHandler<Integer, Integer>() {
                @Override
                public void onInit(String name, int poolSize, ProtoContext context) {
                    record.add("s1-OnInit");
                }

                @Override
                public void onActive(ProtoContext context) {
                    record.add("s1-OnActive");
                    ProtoHandler<Integer, Integer> decode = recordHandler("s2", record);
                    asBuildContext(context).addLastDecoder("s2", decode);
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                    record.add("s1-OnMessage");
                    dst.offerMessage(src.takeMessage(src.queueSize()));
                    return ProtoStatus.Next;
                }
            });

        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);

        assert record.size() == 5;
        assert record.get(0).equals("s1-OnInit");
        assert record.get(1).equals("s1-OnActive");
        assert record.get(2).equals("s2-OnActive");
        assert record.get(3).equals("s1-OnMessage");
        assert record.get(4).equals("s2-OnMessage");
        neta.shutdown();
    }

    @Test
    public void msgAddTest_0() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder("test", new ProtoHandler<Integer, Integer>() {
                @Override
                public void onInit(String name, int poolSize, ProtoContext context) {
                    record.add("s1-OnInit");
                }

                @Override
                public void onActive(ProtoContext context) {
                    record.add("s1-OnActive");
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                    record.add("s1-OnMessage");

                    ProtoHandler<Integer, Integer> decode = recordHandler("s2", record);
                    asBuildContext(context).addLastDecoder("s2", decode);
                    dst.offerMessage(src.takeMessage(src.queueSize()));
                    return ProtoStatus.Next;
                }
            });

        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);

        assert record.size() == 4;
        assert record.get(0).equals("s1-OnInit");
        assert record.get(1).equals("s1-OnActive");
        assert record.get(2).equals("s1-OnMessage");
        assert record.get(3).equals("s2-OnMessage");
        neta.shutdown();
    }

    @Test
    public void eventAddTest_0() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder("test", new ProtoHandler<Integer, Integer>() {
                @Override
                public void onInit(String name, int poolSize, ProtoContext context) {
                    record.add("s1-OnInit");
                }

                @Override
                public void onActive(ProtoContext context) {
                    record.add("s1-OnActive");
                }

                @Override
                public boolean onEvent(ProtoContext context, SoEvent event) {
                    record.add("s1-OnEvent");
                    ProtoHandler<Integer, Integer> decode = recordHandler("s2", record);
                    asBuildContext(context).addLastDecoder("s2", decode);
                    return true;
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                    record.add("s1-OnMessage");

                    dst.offerMessage(src.takeMessage(src.queueSize()));
                    return ProtoStatus.Next;
                }
            });
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.fireEvent(ProtoStackTest.class, this);

        channel.receiveData(1);

        assert record.size() == 6;
        assert record.get(0).equals("s1-OnInit");
        assert record.get(1).equals("s1-OnActive");
        assert record.get(2).equals("s1-OnEvent");
        assert record.get(3).equals("s2-OnEvent");
        assert record.get(4).equals("s1-OnMessage");
        assert record.get(5).equals("s2-OnMessage");
        neta.shutdown();
    }
}