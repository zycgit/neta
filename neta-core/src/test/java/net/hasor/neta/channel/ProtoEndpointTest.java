/// *
// * Copyright 2008-2009 the original author or authors.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package net.hasor.neta.handler;
//import net.hasor.neta.channel.NetManager;
//import net.hasor.neta.channel.ProtoInitializer;
//import net.hasor.neta.channel.virtual.VrtChannel;
//import net.hasor.neta.channel.virtual.VrtSoConfig;
//import net.hasor.neta.channel.virtual.VrtSocketAddress;
//import net.hasor.neta.handler.frames.TypeRequest;
//import org.junit.Test;
//
//import java.util.ArrayDeque;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Queue;
//
/// **
// * @author 赵永春 (zyc@hasor.net)
// * @version : 2022-11-01
// */
//public class ProtoEndpointTest extends AbstractStackTest {
//
//    @Test
//    public void rcvHeapUpTest_1() throws Throwable {
//        Queue<TypeRequest> queue = new ArrayDeque<>();
//        EventBus bus = new ProtoEventBus();
//        bus.subscribe(EventBus.TOPIC_CHANNEL, d -> queue.offer((TypeRequest) d));
//
//        ProtoInitializer initializer = (ctx) -> {
//            List<String> ignore = new ArrayList<>();
//            ProtoConfig protoConf = new ProtoConfig();
//            protoConf.setRcvDownSlotSize(2);
//            protoConf.setSndUpSlotSize(2);
//            return ProtoHelper.typed(Integer.class, Integer.class, protoConf)//
//                    .nextDecoder("COPY", protoConf, doCopyHandler("Dec1", ignore, ignore))       // rcv +1
//                    .nextDecoder("NO_COPY", protoConf, doNotCopyHandler("Dec2", ignore, ignore)) // rcv +1
//                    .build(bus);
//        };
//
//        NetManager neta = new NetManager();
//        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
//
//        channel.triggerReceive(1, 2); // in "COPY" rcvDown
//        channel.triggerReceive(3, 4); // in ProtoStack rcv up
//
//        try {
//            channel.triggerReceive(5, 6);
//            assert false;
//        } catch (Throwable e) {
//            assert e.getMessage().endsWith("available slot is 0, require 2.");
//        }
//
//        assert channel.getStatistical().heapUpOfRcv() == 4;
//        assert channel.getStatistical().heapUpOfRcv("COPY") == 2;
//        assert channel.getStatistical().heapUpOfRcv("NO_COPY") == 0;
//        assert channel.getStatistical().heapUpOfRcvRoot() == 2;
//        assert queue.poll() == null;
//    }
//
//    @Test
//    public void rcvHeapUpTest_2() {
//        EmbeddedInitializer initializer = (ctx) -> {
//            List<String> ignore = new ArrayList<>();
//            ProtoConfig protoConf = new ProtoConfig();
//            protoConf.setRcvDownSlotSize(2);
//            protoConf.setSndUpSlotSize(2);
//            return ProtoHelper.typed(Integer.class, Integer.class, protoConf)//
//                    .nextDecoder("COPY1", protoConf, doCopyHandler("Dec1", ignore, ignore)) // rcv +1
//                    .nextDecoder("COPY2", protoConf, doCopyHandler("Dec2", ignore, ignore)) // rcv +1
//                    .build();
//        };
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//        channel.receive(1, 2);
//        channel.receive(3);
//        channel.receive(4);
//        channel.receive(5);
//
//        assert channel.getStatistical().heapUpOfRcv() == 0;
//        assert channel.getStatistical().heapUpOfRcv("COPY1") == 0;
//        assert channel.getStatistical().heapUpOfRcv("COPY2") == 0;
//        assert channel.getStatistical().heapUpOfRcvRoot() == 0;
//        assert channel.getRcvQueueSize() == 5;
//    }
//
//    @Test
//    public void rcvHeapUpTest_3() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        channel.receive(1, 2);
//        channel.receive(3, 4);
//        channel.receive(5, 6, 7);
//        channel.receive(8, 9, 10);
//        assert channel.getRcvQueueSize() == 10;
//    }
//
//    @Test
//    public void rcvHeapUpTest_4() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        Exception e = new Exception("Error");
//        channel.receiveError(e);
//
//        assert channel.getRcvError() == e;
//    }
//
//    @Test
//    public void sndHeapUpTest_1() {
//        EmbeddedInitializer initializer = (ctx) -> {
//            List<String> ignore = new ArrayList<>();
//            ProtoConfig protoConf = new ProtoConfig();
//            protoConf.setRcvDownSlotSize(2);
//            protoConf.setSndUpSlotSize(2);
//            return ProtoHelper.typed(Integer.class, Integer.class, protoConf)//
//                    .nextEncoder("COPY", protoConf, doCopyHandler("Enc1", ignore, ignore))       // snd +1
//                    .nextEncoder("NO_COPY", protoConf, doNotCopyHandler("Enc2", ignore, ignore)) // snd +1
//                    .build();
//        };
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//        channel.send(1, 2); // in "COPY" rcvDown
//        try {
//            channel.send(3, 4);
//            assert false;
//        } catch (Exception e) {
//            assert e.getMessage().endsWith("available slot is 0, require 2.");
//        }
//
//        assert channel.getStatistical().heapUpOfSnd() == 2;
//        assert channel.getStatistical().heapUpOfRcv("COPY") == 0;
//        assert channel.getStatistical().heapUpOfRcv("NO_COPY") == 0;
//        assert channel.getStatistical().heapUpOfSndRoot() == 2;
//        assert channel.readSnd() == null;
//    }
//
//    @Test
//    public void sndHeapUpTest_2() {
//        EmbeddedInitializer initializer = (ctx) -> {
//            List<String> ignore = new ArrayList<>();
//            ProtoConfig protoConf = new ProtoConfig();
//            protoConf.setRcvDownSlotSize(2);
//            protoConf.setSndUpSlotSize(2);
//            return ProtoHelper.typed(Integer.class, Integer.class, protoConf)//
//                    .nextEncoder("COPY1", protoConf, doCopyHandler("Dec1", ignore, ignore)) //
//                    .nextEncoder("COPY2", protoConf, doCopyHandler("Dec2", ignore, ignore)) //
//                    .build();
//        };
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//        channel.send(1, 2);
//        channel.send(3);
//        channel.send(4);
//        channel.send(5);
//
//        assert channel.getStatistical().heapUpOfSnd() == 0;
//        assert channel.getStatistical().heapUpOfSnd("COPY1") == 0;
//        assert channel.getStatistical().heapUpOfSnd("COPY2") == 0;
//        assert channel.getStatistical().heapUpOfSndRoot() == 0;
//        assert channel.getSndQueueSize() == 5;
//    }
//
//    @Test
//    public void sndHeapUpTest_3() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        channel.send(1, 2);
//        channel.send(3, 4);
//        channel.send(5, 6, 7);
//        channel.send(8, 9, 10);
//        assert channel.getSndQueueSize() == 10;
//    }
//
//    @Test
//    public void sndHeapUpTest_4() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        Exception e = new Exception("Error");
//        channel.sendError(e);
//
//        assert channel.getSndError() == e;
//    }
//
//    @Test
//    public void rcvToSendTest_1() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class)//
//                .nextDuplex(doProtoLayer(true, false)).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        channel.receive(123);
//        assert channel.readRcv().equals(123);
//        assert channel.readSnd().equals(888);
//    }
//
//    @Test
//    public void rcvToSendTest_2() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class)//
//                .nextDuplex(doProtoLayer(false, true)).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        channel.receive(123);
//        assert channel.readRcv().equals(123);
//        assert channel.readSnd().equals(999);
//    }
//
//    @Test
//    public void rcvToSendTest_3() {
//        EmbeddedInitializer initializer = (ctx) -> ProtoHelper.typed(Integer.class, Integer.class)//
//                .nextDuplex(doProtoLayer(true, true)).build();
//
//        EmbeddedSoContext context = new EmbeddedSoContext();
//        EmbeddedChannel channel = new EmbeddedChannel(true, initializer, context);
//
//        channel.receive(123);
//        assert channel.readRcv().equals(123);
//        assert channel.readSnd().equals(888);
//        assert channel.readSnd().equals(999);
//    }
//}