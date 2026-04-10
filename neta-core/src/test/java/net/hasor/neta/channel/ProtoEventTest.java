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
public class ProtoEventTest extends AbstractStackTest {
    public static ProtoDuplexer<Integer, Integer, Integer, Integer> theDuplexer(String tag, List<String> record) {
        return new ProtoDuplexer<Integer, Integer, Integer, Integer>() {
            @Override
            public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
                record.add(tag + "-OnInit");
            }

            @Override
            public void onActive(ProtoContext context) {
                record.add(tag + "-OnActive");
            }

            @Override
            public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
                record.add(tag + "-OnEvent-" + (isRcv ? "rcv" : "snd"));
                return true;
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Integer> rcvUp, ProtoSndQueue<Integer> rcvDown, ProtoRcvQueue<Integer> sndUp, ProtoSndQueue<Integer> sndDown) {
                if (isRcv) {
                    record.add(tag + "-OnMessage-rcv");
                    rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
                } else {
                    record.add(tag + "-OnMessage-snd");
                    sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
                }
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                record.add(tag + "-OnError-" + (isRcv ? "rcv" : "snd"));
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
            }
        };
    }

    public static ProtoHandler<Integer, Integer> theHandler(String tag, List<String> record) {
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
        channel.fireEvent(ProtoEventTest.class, this);

        assert record.size() == 10;
        assert record.get(0).equals("dec1-OnInit");
        assert record.get(1).equals("dec2-OnInit");
        assert record.get(2).equals("enc1-OnInit");
        assert record.get(3).equals("enc2-OnInit");
        assert record.get(4).equals("dec1-OnActive");
        assert record.get(5).equals("dec2-OnActive");
        assert record.get(6).equals("enc1-OnActive");
        assert record.get(7).equals("enc2-OnActive");
        //
        assert record.get(8).equals("dec1-OnEvent");
        assert record.get(9).equals("dec2-OnEvent");
        neta.shutdown();
    }

    @Test
    public void eventTest_0_sndOnly() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder(theHandler("dec1", record));
            ctx.addLastDecoder(theHandler("dec2", record));

            ctx.addLastEncoder(theHandler("enc1", record));
            ctx.addLastEncoder(theHandler("enc2", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.notifyEvent(false, null, ProtoEventTest.class, this);

        assert record.size() == 10;
        assert record.get(8).equals("enc2-OnEvent");
        assert record.get(9).equals("enc1-OnEvent");
        neta.shutdown();
    }

    @Test
    public void eventTest_1() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder(theHandler("dec1", record));
            ctx.addLastDecoder(theHandler("dec2", record));

            ctx.addLastDecoder("s1", (context, src, dst) -> {
                context.fireEvent(ProtoEventTest.class, this);
                return ProtoStatus.Next;
            });

            ctx.addLastEncoder(theHandler("enc1", record));
            ctx.addLastEncoder(theHandler("enc2", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.receiveData(1);

        // s1 fires fireEvent from within RCV — event travels RCV direction only (toward enc1/enc2
        // wrappers, which are transparent in RCV mode). Encoders receive no Event.
        // After all decoders complete, doSndLife runs enc2→enc1.
        assert record.size() == 12;
        assert record.get(8).equals("dec1-OnMessage");
        assert record.get(9).equals("dec2-OnMessage");
        assert record.get(10).equals("enc2-OnMessage");    // doSndLife after last decoder
        assert record.get(11).equals("enc1-OnMessage");
        neta.shutdown();
    }

    @Test
    public void eventTest_2_nestedRcvOnlyForward() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLastDecoder(theHandler("dec1", record));
            ctx.addLastDecoder("mid", (context, src, dst) -> {
                record.add("mid-OnMessage");
                context.fireEvent(ProtoEventTest.class, this);
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            });
            ctx.addLastDecoder(theHandler("dec2", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.receiveData(1);

        assert record.contains("dec1-OnMessage");
        assert record.contains("mid-OnMessage");
        assert record.contains("dec2-OnEvent");
        assert record.stream().filter("dec2-OnEvent"::equals).count() == 1;
        assert !record.contains("dec1-OnEvent");
        assert !record.contains("mid-OnEvent");
        neta.shutdown();
    }

    @Test
    public void eventTest_3_reverseFromRcvUsesSndDirection() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLast("a", theDuplexer("a", record));
            ctx.addLast("mid", new ProtoDuplexer<Integer, Integer, Integer, Integer>() {
                @Override
                public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
                }

                @Override
                public void onActive(ProtoContext context) {
                }

                @Override
                public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
                    record.add("mid-OnEvent-" + (isRcv ? "rcv" : "snd"));
                    return true;
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Integer> rcvUp, ProtoSndQueue<Integer> rcvDown, ProtoRcvQueue<Integer> sndUp, ProtoSndQueue<Integer> sndDown) throws Throwable {
                    if (isRcv) {
                        context.fireEventReverse(String.class, "upstream");
                        rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
                    } else {
                        sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
                    }
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                    return ProtoStatus.Next;
                }

                @Override
                public void onClose(ProtoContext context) {
                }
            });
            ctx.addLast("c", theDuplexer("c", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.receiveData(1);

        assert record.contains("a-OnEvent-snd");
        assert !record.contains("c-OnEvent-rcv");
        assert !record.contains("mid-OnEvent-rcv");
        neta.shutdown();
    }

    @Test
    public void eventTest_4_explicitRcvFromSndUsesForwardDirection() throws Throwable {
        List<String> record = new ArrayList<>();

        ProtoInitializer initializer = (ctx) -> {
            ctx.addLast("a", theDuplexer("a", record));
            ctx.addLast("mid", new ProtoDuplexer<Integer, Integer, Integer, Integer>() {
                @Override
                public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
                }

                @Override
                public void onActive(ProtoContext context) {
                }

                @Override
                public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
                    record.add("mid-OnEvent-" + (isRcv ? "rcv" : "snd"));
                    return true;
                }

                @Override
                public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Integer> rcvUp, ProtoSndQueue<Integer> rcvDown, ProtoRcvQueue<Integer> sndUp, ProtoSndQueue<Integer> sndDown) throws Throwable {
                    if (isRcv) {
                        rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
                    } else {
                        context.fireEventRcv(String.class, "force-rcv");
                        sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
                    }
                    return ProtoStatus.Next;
                }

                @Override
                public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                    return ProtoStatus.Next;
                }

                @Override
                public void onClose(ProtoContext context) {
                }
            });
            ctx.addLast("c", theDuplexer("c", record));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        channel.sendData(1).get();

        assert record.contains("c-OnEvent-rcv");
        assert !record.contains("a-OnEvent-snd");
        assert !record.contains("mid-OnEvent-snd");
        neta.shutdown();
    }
}