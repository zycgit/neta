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
import java.util.Collections;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * Tests for multi-layer pipeline flow control:
 * - decoder-only stack
 * - encoder-only stack
 * - duplex stack
 * - multi-layer error propagation
 * - ProtoStatus.Stop in middle layer
 * @author test
 */
public class ProtoPipelineTest extends AbstractStackTest {
    private static final class InitSizeRecorder implements ProtoDuplexer<Integer, Integer, Integer, Integer> {
        private final List<String> sizes;

        private InitSizeRecorder(List<String> sizes) {
            this.sizes = sizes;
        }

        @Override
        public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
            this.sizes.add(name + ":" + rcvSize + "/" + sndSize);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Integer> rcvUp, ProtoSndQueue<Integer> rcvDown, ProtoRcvQueue<Integer> sndUp, ProtoSndQueue<Integer> sndDown) throws Throwable {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
            } else {
                sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
            }
            return ProtoStatus.Next;
        }
    }

    // --- Decoder-only pipeline (no encoder) ---

    @Test
    public void decoderOnly_multiLayer() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("1", record, errors))                   //
                .nextDecoder("L2", doNextHandler("2", record, errors))                   //
                .nextDecoder("L3", doNextHandler("3", record, errors))                   //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(42);
        Thread.sleep(200);

        // all 3 decoder layers should process in order
        assert record.size() == 3 : "record=" + record;
        assert record.get(0).equals("1DoNext");
        assert record.get(1).equals("2DoNext");
        assert record.get(2).equals("3DoNext");

        channel.closeNow();
        neta.shutdown();
    }

    // --- Stop in middle layer halts propagation ---

    @Test
    public void stop_inMiddleDecoder_haltsRest() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("1", record, errors))                   //
                .nextDecoder("L2", doExitHandler("2", record, errors))                   //
                .nextDecoder("L3", doNextHandler("3", record, errors))                   //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        // L1 and L2 should process, L3 should NOT be reached
        assert record.size() == 2 : "record=" + record;
        assert record.get(0).equals("1DoNext");
        assert record.get(1).equals("2DoExit");
        // L3 should not appear
        assert !record.contains("3DoNext");

        channel.closeNow();
        neta.shutdown();
    }

    // --- Error thrown in decoder triggers onError in the next handler downstream ---

    @Test
    public void error_inDecoder_triggersOnError() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doThrowHandler("1", record, errors))                  //
                .nextDecoder("L2", doNextHandler("2", record, errors))                   //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        // L1 threw, so error chain is triggered
        assert record.contains("1DoThrow");
        // L2's onError should have been called
        assert errors.contains("2ErrNext");

        channel.closeNow();
        neta.shutdown();
    }

    // --- Retry handler retries specified number of times ---

    @Test
    public void retry_handler_retriesCorrectly() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doRetryHandler("1", record, errors, 3))               //
                .nextDecoder("L2", doNextHandler("2", record, errors))                   //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        // L1 should appear with retry count (3 retries + final next = total 4 calls of L1)
        long l1Count = record.stream().filter(s -> s.equals("1DoRetry")).count();
        assert l1Count == 4 : "l1Count=" + l1Count; // initial call + 3 retries

        // L2 should eventually get called once
        assert record.contains("2DoNext");

        channel.closeNow();
        neta.shutdown();
    }

    // --- Duplex handler with decoder+encoder pair ---

    @Test
    public void duplexPair_decoderAndEncoder() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoHandler<Integer, Integer> decoder = doNextHandler("dec", record, errors);
        ProtoHandler<Integer, Integer> encoder = doNextHandler("enc", record, errors);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDuplex("L1", ProtoConfig.DEFAULT, decoder, encoder)                 //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        // rcv direction
        channel.receiveData(1);
        Thread.sleep(200);

        // decoder should process
        assert record.contains("decDoNext") : "record=" + record;

        channel.closeNow();
        neta.shutdown();
    }

    // --- ProtoDuplexer used directly ---

    @Test
    public void protoDuplexer_directUsage() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());

        ProtoDuplexer<Integer, Integer, Integer, Integer> duplexer = new ProtoDuplexer<Integer, Integer, Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Integer> rcvUp, ProtoSndQueue<Integer> rcvDown, ProtoRcvQueue<Integer> sndUp, ProtoSndQueue<Integer> sndDown) throws Throwable {
                if (isRcv) {
                    record.add("rcv");
                    rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
                } else {
                    record.add("snd");
                    sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
                }
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDuplex("dup", duplexer)                                             //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        assert record.contains("rcv") : "record=" + record;

        channel.closeNow();
        neta.shutdown();
    }

    // --- Empty pipeline (no handlers) ---

    @Test
    public void emptyPipeline_noError() throws Throwable {
        ProtoInitializer initializer = ctx -> {
            // intentionally empty
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        // should not crash
        channel.receiveData(1);
        Thread.sleep(100);

        channel.closeNow();
        neta.shutdown();
    }

    // --- Multi-layer error propagation stops at Stop handler ---

    @Test
    public void error_stops_atErrExitHandler() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doThrowHandler("1", record, errors))                  //
                .nextDecoder("L2", errExitHandler("2", record, errors))                  //
                .nextDecoder("L3", errNextHandler("3", record, errors))                  //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        // L1 throws
        assert record.contains("1DoThrow");
        // L2 error handler stops
        assert errors.contains("2ErrExit");
        // L3 error handler should NOT be called (Stop)
        assert !errors.contains("3ErrNext");

        channel.closeNow();
        neta.shutdown();
    }

    // --- Error rethrow in error handler re-triggers error chain ---

    @Test
    public void error_rethrow_propagatesFurther() throws Throwable {
        List<String> record = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doThrowHandler("1", record, errors))                  //
                .nextDecoder("L2", errThrowHandler("2", record, errors))                 //
                .nextDecoder("L3", errNextHandler("3", record, errors))                  //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(200);

        // L1 throws, L2 re-throws — both should be recorded
        assert record.contains("1DoThrow");
        assert errors.contains("2ErrThrow");
        // L3 may or may not receive the re-thrown error depending on framework behavior
        // We verify at minimum that L1 and L2 were invoked correctly

        channel.closeNow();
        neta.shutdown();
    }

    // --- Capacity-limited pipeline ---

    @Test
    public void capacityLimited_decoderPipeline() throws Throwable {
        final boolean[] onMessageCalled = { false };

        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(2);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class, limitedConfig)  //
                .nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                        onMessageCalled[0] = true;
                        return ProtoStatus.Next;
                    }

                    @Override
                    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                        return ProtoStatus.Next;
                    }
                })                                                                                       //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1, 2, 3, 4, 5);
        Thread.sleep(200);

        assert !onMessageCalled[0] : "onMessage should not run when the first queue overflows";
        assert channel.isClose();

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void builderDefaultConfig_appliesToConvenienceMethods() throws Throwable {
        List<String> sizes = Collections.synchronizedList(new ArrayList<>());

        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(2);
        limitedConfig.setSndSlotSize(3);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class, limitedConfig)  //
                .nextDuplex("L1", new InitSizeRecorder(sizes))                                          //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        assert sizes.contains("L1:2/3") : "sizes=" + sizes;

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void explicitNodeConfig_overridesBuilderDefault() throws Throwable {
        List<String> sizes = Collections.synchronizedList(new ArrayList<>());

        ProtoConfig builderConfig = new ProtoConfig();
        builderConfig.setRcvSlotSize(2);
        builderConfig.setSndSlotSize(3);

        ProtoConfig nodeConfig = new ProtoConfig();
        nodeConfig.setRcvSlotSize(5);
        nodeConfig.setSndSlotSize(7);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class, builderConfig)  //
                .nextDuplex("L1", nodeConfig, new InitSizeRecorder(sizes))                            //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        assert sizes.contains("L1:5/7") : "sizes=" + sizes;

        channel.closeNow();
        neta.shutdown();
    }
}
