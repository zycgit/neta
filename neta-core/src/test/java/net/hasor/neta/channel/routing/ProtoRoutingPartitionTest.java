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
package net.hasor.neta.channel.routing;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Assert;
import org.junit.Test;

public class ProtoRoutingPartitionTest extends AbstractStackTest {
    @Test
    public void partitionPipelineShouldIsolateStateAndExposeLifecycleControl() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(1, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);

            managed.channel.receiveData(new PartitionMessage(1, "A", false));
            managed.channel.receiveData(new PartitionMessage(2, "X", false));

            Assert.assertNotNull(controlRef[0]);
            Assert.assertEquals(2, controlRef[0].partitionSize());
            Assert.assertTrue(controlRef[0].contains(PartitionKey.newKey("1")));
            Assert.assertTrue(controlRef[0].contains(PartitionKey.newKey("2")));

            Assert.assertTrue(controlRef[0].closePartition(PartitionKey.newKey("1")));
            Assert.assertFalse(controlRef[0].contains(PartitionKey.newKey("1")));
            Assert.assertEquals(1, controlRef[0].partitionSize());

            managed.channel.receiveData(new PartitionMessage(1, "B", true));
            managed.channel.receiveData(new PartitionMessage(2, "Y", true));

            Assert.assertEquals(2, outbound.size());
            Assert.assertEquals("B", outbound.get(0).body());
            Assert.assertEquals("XY", outbound.get(1).body());

            controlRef[0].closeAllPartitions();
            Assert.assertEquals(0, controlRef[0].partitionSize());
            Assert.assertFalse(controlRef[0].contains(PartitionKey.newKey("2")));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldFreezeNewCreationAndResumeLater() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(2, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);

            managed.channel.receiveData(new PartitionMessage(1, "A", false));
            controlRef[0].lockCreation();

            managed.channel.receiveData(new PartitionMessage(1, "B", true));
            managed.channel.receiveData(new PartitionMessage(2, "DROP", true));

            Assert.assertEquals(1, outbound.size());
            Assert.assertEquals("AB", outbound.get(0).body());
            Assert.assertEquals(1, controlRef[0].partitionSize());
            Assert.assertFalse(controlRef[0].contains(PartitionKey.newKey("2")));

            controlRef[0].unlockCreation();
            managed.channel.receiveData(new PartitionMessage(2, "OK", true));

            Assert.assertEquals(2, outbound.size());
            Assert.assertEquals("OK", outbound.get(1).body());
            Assert.assertTrue(controlRef[0].contains(PartitionKey.newKey("2")));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldRequestCloseAndCommitOnNextOwnerRound() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(20, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);
            PartitionKey key1 = PartitionKey.newKey("1");

            managed.channel.receiveData(new PartitionMessage(1, "A", false));

            Assert.assertTrue(controlRef[0].requestClose(key1));
            Assert.assertTrue(controlRef[0].contains(key1));
            Assert.assertTrue(controlRef[0].isClose(key1));

            managed.channel.receiveData(new PartitionMessage(2, "B", true));

            Assert.assertFalse(controlRef[0].contains(key1));
            Assert.assertFalse(controlRef[0].isClose(key1));
            Assert.assertEquals(1, outbound.size());
            Assert.assertEquals(2, outbound.get(0).partitionId());
            Assert.assertEquals("B", outbound.get(0).body());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldRequestCloseAllAndCommitOnNextOwnerRound() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(21, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            managed.channel.receiveData(new PartitionMessage(1, "A", false));
            managed.channel.receiveData(new PartitionMessage(2, "B", false));

            Assert.assertEquals(2, controlRef[0].partitionSize());

            controlRef[0].requestCloseAll();

            Assert.assertTrue(controlRef[0].isClose(PartitionKey.newKey("1")));
            Assert.assertTrue(controlRef[0].isClose(PartitionKey.newKey("2")));

            managed.channel.fireEvent(Integer.class, 0);

            Assert.assertEquals(0, controlRef[0].partitionSize());
            Assert.assertFalse(controlRef[0].isClose(PartitionKey.newKey("1")));
            Assert.assertFalse(controlRef[0].isClose(PartitionKey.newKey("2")));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldIgnoreUnmatchedMessagesAndOnlyCallPolicyOnFirstCreation() throws Throwable {
        final int[] policyCalls = { 0 };
        final int[] branchCalls = { 0 };
        ManagedPartitionChannel managed = openChannel(3, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            partition.policy((context, control, triggerKind, partitionKey, trigger) -> {
                policyCalls[0]++;
                return ProtoPartitionPolicy.ReceivePolicy.Accept;
            });
            partition.byInitializer(ctx -> ctx.addLastDecoder("counter", new CountingHandler(branchCalls)));
        }).build());

        try {
            managed.channel.receiveData(new PartitionMessage(0, "IGNORED", false));
            managed.channel.receiveData(new PartitionMessage(1, "A", false), new PartitionMessage(1, "B", false), new PartitionMessage(1, "C", false));
            managed.channel.receiveData(new PartitionMessage(0, "IGNORED", true));
            managed.channel.receiveData(new PartitionMessage(1, "D", false));

            Assert.assertEquals(1, policyCalls[0]);
            Assert.assertEquals(2, branchCalls[0]);
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldRouteUnmatchedMessagesIntoDefaultPartition() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(10, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new DefaultAwareMessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
            partition.byDefault(ctx -> ctx.addLastDecoder("default-collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);

            managed.channel.receiveData(new PartitionMessage(0, "DEFAULT", true));
            managed.channel.receiveData(new PartitionMessage(1, "MATCHED", true));

            Assert.assertEquals(2, outbound.size());
            Assert.assertNotNull(controlRef[0]);
            Assert.assertTrue(controlRef[0].contains(PartitionKey.defaultKey()));
            Assert.assertEquals(0, outbound.get(0).partitionId());
            Assert.assertEquals("DEFAULT", outbound.get(0).body());
            Assert.assertEquals(1, outbound.get(1).partitionId());
            Assert.assertEquals("MATCHED", outbound.get(1).body());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldRouteUnmatchedEventsIntoDefaultPartition() throws Throwable {
        ManagedPartitionChannel managed = openChannel(11, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new DefaultAwareMessagePartitionSelector(), partition -> {
            partition.byInitializer(ctx -> ctx.addLast("event-echo", new EventEchoHandler()));
            partition.byDefault(ctx -> ctx.addLast("default-event", new EventEchoHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);

            managed.channel.fireEvent(Integer.class, 0);
            managed.channel.fireEvent(Integer.class, 2);

            Assert.assertEquals(2, outbound.size());
            Assert.assertEquals(0, outbound.get(0).partitionId());
            Assert.assertEquals("EVENT-0", outbound.get(0).body());
            Assert.assertEquals(2, outbound.get(1).partitionId());
            Assert.assertEquals("EVENT-2", outbound.get(1).body());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldStopMatchedEventWhenBranchReturnsFalse() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.configDuplexer(null, ctx -> ctx.addLastDecoder("event-stop", new EventStopHandler()), null);

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(2), ctx -> {
        }, new VrtSoConfig());
        ProtoContext context = extractProtoContext(channel);
        ProtoQueue<PartitionMessage> rcvUp = new ProtoQueue<>(1);
        rcvUp.offerMessage(new PartitionMessage(1, "A", false));

        duplexer.onInit("partition", 1, 1, context);
        duplexer.onMessage(context, true, rcvUp, new ProtoQueue<PartitionMessage>(1), ProtoQueue.emptyRcv(), new ProtoQueue<PartitionMessage>(1));

        boolean result = duplexer.onEvent(context, SoEventObject.of(null, Integer.class, 1), true);
        Assert.assertFalse(result);
    }

    @Test
    public void partitionPipelineShouldPassThroughUnmatchedEventWhenDefaultPartitionIsAbsent() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.configDuplexer(null, ctx -> ctx.addLastDecoder("collector", new CollectingHandler()), null);

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(12), ctx -> {
        }, new VrtSoConfig());
        ProtoContext context = extractProtoContext(channel);

        duplexer.onInit("partition", 1, 1, context);
        boolean result = duplexer.onEvent(context, SoEventObject.of(null, Integer.class, 0), true);
        Assert.assertTrue(result);
    }

    @Test
    public void partitionPipelineShouldPassThroughUnmatchedMessagesWhenDefaultPartitionIsAbsent() throws Throwable {
        List<String> downstream = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(4);

        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()))).nextDecoder("downstream", limitedConfig, new DownstreamCollectHandler(downstream)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(13), initializer, new VrtSoConfig());
        channel.receiveData(new PartitionMessage(0, "P", false), new PartitionMessage(0, "T", true));

        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals("P", downstream.get(0));
        Assert.assertEquals("T", downstream.get(1));
    }

    @Test
    public void partitionPipelineShouldFlushPendingOutputAfterDownstreamRecovery() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.configDuplexer(null, ctx -> ctx.addLastDecoder("splitter", new SplitOutputHandler()), null);

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(3), ctx -> {
        }, new VrtSoConfig());
        ProtoContext context = extractProtoContext(channel);
        ProtoQueue<PartitionMessage> rcvUp = new ProtoQueue<>(2);
        ProtoQueue<PartitionMessage> rcvDown = new ProtoQueue<>(1);

        rcvUp.offerMessage(new PartitionMessage(1, "A", false));
        rcvUp.offerMessage(new PartitionMessage(1, "B", false));

        duplexer.onInit("partition", 4, 1, context);

        ProtoStatus firstStatus = duplexer.onMessage(context, true, rcvUp, rcvDown, ProtoQueue.emptyRcv(), new ProtoQueue<PartitionMessage>(1));
        Assert.assertEquals(ProtoStatus.Next, firstStatus);
        Assert.assertEquals(1, rcvDown.queueSize());
        Assert.assertEquals("A-1", rcvDown.peekMessage().body());
        Assert.assertEquals(1, rcvUp.queueSize());

        Assert.assertEquals("A-1", rcvDown.takeMessage().body());

        ProtoStatus secondStatus = duplexer.onMessage(context, true, ProtoQueue.emptyRcv(), rcvDown, ProtoQueue.emptyRcv(), new ProtoQueue<PartitionMessage>(1));
        Assert.assertEquals(ProtoStatus.Next, secondStatus);
        Assert.assertEquals(1, rcvDown.queueSize());
        Assert.assertEquals("A-2", rcvDown.peekMessage().body());
        Assert.assertEquals(1, rcvUp.queueSize());
    }

    @Test
    public void partitionPipelineShouldRestartRootDispatchAfterWritableRecovery() throws Throwable {
        List<String> downstream = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(1);

        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("splitter", new SplitOutputHandler()))).nextDecoder("collector", limitedConfig, new DownstreamCollectHandler(downstream)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(4), initializer, new VrtSoConfig());
        channel.receiveData(new PartitionMessage(1, "A", false));

        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals("A-1", downstream.get(0));
        Assert.assertEquals("A-2", downstream.get(1));
    }

    @Test
    public void partitionPipelineShouldRouteEventsAndAllowEventDrivenClose() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(6, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new EventClosingCollectHandler(controlRef)));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            List<PartitionMessage> outbound = subscribeOutbound(managed.channel);

            managed.channel.receiveData(new PartitionMessage(1, "A", false));
            managed.channel.fireEvent(Integer.class, 1);
            managed.channel.receiveData(new PartitionMessage(1, "B", true));

            Assert.assertEquals(1, outbound.size());
            Assert.assertEquals(1, outbound.get(0).partitionId());
            Assert.assertEquals("B", outbound.get(0).body());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldBatchSamePartitionMessagesInSingleBranchPass() throws Throwable {
        final List<Integer> batchSizes = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("batch-recorder", new BatchRecordHandler(batchSizes)))).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(7), initializer, new VrtSoConfig());
        channel.receiveData(new PartitionMessage(1, "A", false), new PartitionMessage(1, "B", false), new PartitionMessage(1, "C", false));

        Assert.assertEquals(1, batchSizes.size());
        Assert.assertEquals(Integer.valueOf(3), batchSizes.get(0));
    }

    @Test
    public void partitionPipelineShouldPassThroughSendDirectionWithoutRouting() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.configDuplexer(null, ctx -> {
            ctx.addLastDecoder("collector", new CollectingHandler());
        }, null);

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(8), ctx -> {
        }, new VrtSoConfig());
        ProtoContext context = extractProtoContext(channel);
        ProtoQueue<PartitionMessage> sndUp = new ProtoQueue<>(2);
        ProtoQueue<PartitionMessage> sndDown = new ProtoQueue<>(1);

        sndUp.offerMessage(new PartitionMessage(1, "A", false));
        sndUp.offerMessage(new PartitionMessage(2, "B", false));

        duplexer.onInit("partition", 2, 2, context);

        ProtoStatus status = duplexer.onMessage(context, false, new ProtoQueue<PartitionMessage>(1), new ProtoQueue<PartitionMessage>(1), sndUp, sndDown);
        Assert.assertEquals(ProtoStatus.Next, status);
        Assert.assertEquals(1, sndDown.queueSize());
        Assert.assertEquals("A", sndDown.peekMessage().body());
        Assert.assertEquals(1, sndUp.queueSize());
    }

    @Test
    public void partitionPipelineShouldIgnoreNewPartitionEventWhileCreationFrozen() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        ManagedPartitionChannel managed = openChannel(9, ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextEncoder("pass-through", new PassThroughEncoder()).build());

        try {
            controlRef[0].lockCreation();
            managed.channel.fireEvent(Integer.class, 3);
            Assert.assertEquals(0, controlRef[0].partitionSize());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldPassThroughUnmatchedMessagesWhenDefaultPartitionInitializerIsEmpty() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        List<String> downstream = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(4);

        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new DefaultAwareMessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
            partition.byDefault(ctx -> {
            });
        }).nextDecoder("downstream", limitedConfig, new DownstreamCollectHandler(downstream)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(14), initializer, new VrtSoConfig());
        channel.receiveData(new PartitionMessage(0, "P", false), new PartitionMessage(0, "T", true));

        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals("P", downstream.get(0));
        Assert.assertEquals("T", downstream.get(1));
        Assert.assertNotNull(controlRef[0]);
        Assert.assertFalse(controlRef[0].contains(PartitionKey.defaultKey()));
    }

    @Test
    public void partitionPipelineShouldPassThroughUnmatchedEventsWhenDefaultPartitionInitializerIsEmpty() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        List<Integer> eventTrace = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new DefaultAwareMessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
            partition.byDefault(ctx -> {
            });
        }).nextDecoder("event-record", new EventRecordHandler(eventTrace)).build();

        ManagedPartitionChannel managed = openChannel(15, initializer);
        try {
            managed.channel.fireEvent(Integer.class, 0);

            Assert.assertEquals(1, eventTrace.size());
            Assert.assertEquals(Integer.valueOf(0), eventTrace.get(0));
            Assert.assertNotNull(controlRef[0]);
            Assert.assertFalse(controlRef[0].contains(PartitionKey.defaultKey()));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldNotPassMatchedEventThroughWhenPartitionCreationFails() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        List<Integer> eventTrace = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextDecoder("event-record", new EventRecordHandler(eventTrace)).build();

        ManagedPartitionChannel managed = openChannel(16, initializer);
        try {
            controlRef[0].lockCreation();
            managed.channel.fireEvent(Integer.class, 3);

            Assert.assertTrue(eventTrace.isEmpty());
            Assert.assertEquals(0, controlRef[0].partitionSize());
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldEmitUnmatchedMessagesEventWhenNewPartitionIsLocked() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        final List<UnmatchedEventSnapshot> droppedTrace = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
        }).nextDecoder("unmatched-record", new UnmatchedMessagesRecordHandler(droppedTrace)).build();

        ManagedPartitionChannel managed = openChannel(17, initializer);
        try {
            managed.channel.receiveData(new PartitionMessage(1, "A", false));
            controlRef[0].lockCreation();
            managed.channel.receiveData(new PartitionMessage(2, "DROP-1", false), new PartitionMessage(2, "DROP-2", true));

            Assert.assertEquals(1, droppedTrace.size());
            Assert.assertEquals(PartitionKey.newKey(2), droppedTrace.get(0).getPartitionKey());
            Assert.assertEquals(2, droppedTrace.get(0).getMessages().size());
            Assert.assertEquals("DROP-1", droppedTrace.get(0).getMessages().get(0).body());
            Assert.assertEquals("DROP-2", droppedTrace.get(0).getMessages().get(1).body());
            Assert.assertFalse(controlRef[0].contains(PartitionKey.newKey(2)));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldEmitUnmatchedMessagesEventWhenDefaultPartitionIsLocked() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        final List<UnmatchedEventSnapshot> droppedTrace = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new DefaultAwareMessagePartitionSelector(), partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()));
            partition.byDefault(ctx -> ctx.addLastDecoder("default-collector", new CollectingHandler()));
        }).nextDecoder("unmatched-record", new UnmatchedMessagesRecordHandler(droppedTrace)).build();

        ManagedPartitionChannel managed = openChannel(18, initializer);
        try {
            controlRef[0].lockCreation();
            managed.channel.receiveData(new PartitionMessage(0, "DEFAULT-1", false), new PartitionMessage(0, "DEFAULT-2", true));

            Assert.assertEquals(1, droppedTrace.size());
            Assert.assertEquals(PartitionKey.defaultKey(), droppedTrace.get(0).getPartitionKey());
            Assert.assertEquals(2, droppedTrace.get(0).getMessages().size());
            Assert.assertEquals("DEFAULT-1", droppedTrace.get(0).getMessages().get(0).body());
            Assert.assertEquals("DEFAULT-2", droppedTrace.get(0).getMessages().get(1).body());
            Assert.assertFalse(controlRef[0].contains(PartitionKey.defaultKey()));
        } finally {
            managed.close();
        }
    }

    @Test
    public void partitionPipelineShouldReleaseByteBufAfterUnmatchedEventCallbackReturns() throws Throwable {
        final ProtoPartitionControl[] controlRef = new ProtoPartitionControl[1];
        final List<ByteBuf> observedBuffers = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(ByteBuf.class, ByteBuf.class).nextPartition("partition", new ProtoPartitionSelector() {
            @Override
            public PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data) {
                if (kind != PartitionDataKind.Message || !(data instanceof ByteBuf)) {
                    return null;
                }
                return PartitionKey.newKey(1);
            }
        }, partition -> {
            controlRef[0] = partition.control();
            partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new ByteBufPassThroughHandler()));
        }).nextDecoder("unmatched-record", new ByteBufUnmatchedRecordHandler(observedBuffers)).build();

        ManagedPartitionChannel managed = openChannel(19, initializer);
        ByteBuf buffer = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        try {
            controlRef[0].lockCreation();
            managed.channel.receiveData(buffer);

            Assert.assertEquals(1, observedBuffers.size());
            Assert.assertSame(buffer, observedBuffers.get(0));
            Assert.assertEquals(0, buffer.refCnt());
        } finally {
            managed.close();
        }
    }

    private static ManagedPartitionChannel openChannel(int port, ProtoInitializer initializer) throws Throwable {
        NetManager manager = new NetManager();
        VrtChannel channel = (VrtChannel) manager.connectSync(new VrtSocketAddress(port), initializer, new VrtSoConfig());
        return new ManagedPartitionChannel(manager, channel);
    }

    private static List<PartitionMessage> subscribeOutbound(VrtChannel channel) {
        List<PartitionMessage> outbound = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> outbound.add((PartitionMessage) data.getData()));
        return outbound;
    }

    private static ProtoContext extractProtoContext(SoChannel<?> channel) throws Exception {
        Field field = NetChannel.class.getDeclaredField("protoCtx");
        field.setAccessible(true);
        return (ProtoContext) field.get(channel);
    }

    private static class ManagedPartitionChannel {
        private final NetManager manager;
        private final VrtChannel channel;

        private ManagedPartitionChannel(NetManager manager, VrtChannel channel) {
            this.manager = manager;
            this.channel = channel;
        }

        private void close() throws Throwable {
            this.manager.shutdown();
        }
    }

    private static class MessagePartitionSelector implements ProtoPartitionSelector {
        @Override
        public PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data) {
            switch (kind) {
                case Event:
                    Object eventData = ((SoEvent) data).getData();
                    if (!(eventData instanceof Number)) {
                        return null;
                    }
                    int partitionId = ((Number) eventData).intValue();
                    return partitionId <= 0 ? null : PartitionKey.newKey(partitionId);
                case Message:
                    PartitionMessage message = (PartitionMessage) data;
                    return message == null || message.partitionId() <= 0 ? null : PartitionKey.newKey(message.partitionId());
                default:
                    return null;
            }
        }
    }

    private static class DefaultAwareMessagePartitionSelector extends MessagePartitionSelector {
        @Override
        public PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data) {
            PartitionKey routeKey = super.route(context, kind, data);
            if (routeKey != null) {
                return routeKey;
            }

            switch (kind) {
                case Event:
                    Object eventData = ((SoEvent) data).getData();
                    if (eventData instanceof Number && ((Number) eventData).intValue() == 0) {
                        return PartitionKey.defaultKey();
                    }
                    return null;
                case Message:
                    PartitionMessage message = (PartitionMessage) data;
                    return message != null && message.partitionId() == 0 ? PartitionKey.defaultKey() : null;
                default:
                    return null;
            }
        }
    }

    private static class CollectingHandler implements ProtoHandler<PartitionMessage, Object> {
        private final StringBuilder builder = new StringBuilder();

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                PartitionMessage item = src.takeMessage();
                builder.append(item.body());
                if (item.isTerminal()) {
                    PartitionKey holder = PartitionKey.findKey(context);
                    Integer partitionId = this.resolvePartitionId(holder);
                    context.sendData(new PartitionMessage(partitionId == null ? 0 : partitionId, builder.toString(), true)).get();
                    builder.setLength(0);
                }
            }
            return ProtoStatus.Next;
        }

        private Integer resolvePartitionId(PartitionKey holder) {
            if (holder == null || holder.getKey() == null || PartitionKey.defaultKey().equals(holder)) {
                return null;
            }
            return Integer.valueOf(String.valueOf(holder));
        }
    }

    private static class EventClosingCollectHandler extends CollectingHandler {
        private final ProtoPartitionControl[] controlRef;

        private EventClosingCollectHandler(ProtoPartitionControl[] controlRef) {
            this.controlRef = controlRef;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event.getData();
            if (!(eventData instanceof Integer) || this.controlRef[0] == null) {
                return true;
            }

            PartitionKey currentKey = PartitionKey.findKey(context);
            return currentKey == null || this.controlRef[0].requestClose(currentKey);
        }
    }

    private static class EventEchoHandler implements ProtoDuplexer<PartitionMessage, PartitionMessage, PartitionMessage, PartitionMessage> {
        @Override
        public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
            Object eventData = event.getData();
            if (eventData instanceof Number) {
                int partitionId = ((Number) eventData).intValue();
                context.sendData(new PartitionMessage(partitionId, "EVENT-" + partitionId, true)).get();
                return false;
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<PartitionMessage> rcvUp, ProtoSndQueue<PartitionMessage> rcvDown, ProtoRcvQueue<PartitionMessage> sndUp, ProtoSndQueue<PartitionMessage> sndDown) {
            if (isRcv) {
                if (rcvUp.hasMore() && rcvDown.hasSlot()) {
                    rcvDown.offerMessage(rcvUp.takeMessage(1));
                }
            } else {
                if (sndUp.hasMore() && sndDown.hasSlot()) {
                    sndDown.offerMessage(sndUp.takeMessage(1));
                }
            }
            return ProtoStatus.Next;
        }
    }

    private static class EventRecordHandler implements ProtoHandler<PartitionMessage, PartitionMessage> {
        private final List<Integer> eventTrace;

        private EventRecordHandler(List<Integer> eventTrace) {
            this.eventTrace = eventTrace;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event.getData();
            if (eventData instanceof Integer) {
                this.eventTrace.add((Integer) eventData);
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<PartitionMessage> dst) {
            if (src.hasMore() && dst.hasSlot()) {
                dst.offerMessage(src.takeMessage(1));
            }
            return ProtoStatus.Next;
        }
    }

    private static class UnmatchedMessagesRecordHandler implements ProtoHandler<PartitionMessage, PartitionMessage> {
        private final List<UnmatchedEventSnapshot> droppedTrace;

        private UnmatchedMessagesRecordHandler(List<UnmatchedEventSnapshot> droppedTrace) {
            this.droppedTrace = droppedTrace;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event.getData();
            if (eventData instanceof PartitionUnmatchedEvent) {
                PartitionUnmatchedEvent unmatchedEvent = (PartitionUnmatchedEvent) eventData;
                List<PartitionMessage> messages = new ArrayList<PartitionMessage>(unmatchedEvent.getMessages().size());
                for (Object message : unmatchedEvent.getMessages()) {
                    messages.add((PartitionMessage) message);
                }
                this.droppedTrace.add(new UnmatchedEventSnapshot(unmatchedEvent.getPartitionKey(), messages));
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<PartitionMessage> dst) {
            if (src.hasMore() && dst.hasSlot()) {
                dst.offerMessage(src.takeMessage(1));
            }
            return ProtoStatus.Next;
        }
    }

    private static class UnmatchedEventSnapshot {
        private final PartitionKey           partitionKey;
        private final List<PartitionMessage> messages;

        private UnmatchedEventSnapshot(PartitionKey partitionKey, List<PartitionMessage> messages) {
            this.partitionKey = partitionKey;
            this.messages = messages;
        }

        public PartitionKey getPartitionKey() {
            return this.partitionKey;
        }

        public List<PartitionMessage> getMessages() {
            return this.messages;
        }
    }

    private static class ByteBufPassThroughHandler implements ProtoHandler<ByteBuf, ByteBuf> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
            if (src.hasMore() && dst.hasSlot()) {
                dst.offerMessage(src.takeMessage(1));
            }
            return ProtoStatus.Next;
        }
    }

    private static class ByteBufUnmatchedRecordHandler implements ProtoHandler<ByteBuf, ByteBuf> {
        private final List<ByteBuf> observedBuffers;

        private ByteBufUnmatchedRecordHandler(List<ByteBuf> observedBuffers) {
            this.observedBuffers = observedBuffers;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event.getData();
            if (eventData instanceof PartitionUnmatchedEvent) {
                PartitionUnmatchedEvent unmatchedEvent = (PartitionUnmatchedEvent) eventData;
                for (Object message : unmatchedEvent.getMessages()) {
                    this.observedBuffers.add((ByteBuf) message);
                }
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
            if (src.hasMore() && dst.hasSlot()) {
                dst.offerMessage(src.takeMessage(1));
            }
            return ProtoStatus.Next;
        }
    }

    private static class PassThroughEncoder implements ProtoHandler<PartitionMessage, PartitionMessage> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<PartitionMessage> dst) {
            dst.offerMessage(src.takeMessage(src.queueSize()));
            return ProtoStatus.Next;
        }
    }

    private static class EventStopHandler implements ProtoHandler<PartitionMessage, Object> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<Object> dst) {
            dst.offerMessage(src.takeMessage(src.queueSize()));
            return ProtoStatus.Next;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            return false;
        }
    }

    private static class SplitOutputHandler implements ProtoHandler<PartitionMessage, Object> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                PartitionMessage item = src.takeMessage();
                dst.offerMessage(new PartitionMessage(item.partitionId(), item.body() + "-1", false));
                dst.offerMessage(new PartitionMessage(item.partitionId(), item.body() + "-2", false));
            }
            return ProtoStatus.Next;
        }
    }

    private static class DownstreamCollectHandler implements ProtoHandler<PartitionMessage, PartitionMessage> {
        private final List<String> downstream;

        private DownstreamCollectHandler(List<String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<PartitionMessage> dst) {
            while (src.hasMore()) {
                this.downstream.add(src.takeMessage().body());
            }
            return ProtoStatus.Next;
        }
    }

    private static class BatchRecordHandler implements ProtoHandler<PartitionMessage, Object> {
        private final List<Integer> batchSizes;

        private BatchRecordHandler(List<Integer> batchSizes) {
            this.batchSizes = batchSizes;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<Object> dst) {
            this.batchSizes.add(src.queueSize());
            src.skipMessage(src.queueSize());
            return ProtoStatus.Next;
        }
    }

    private static class CountingHandler implements ProtoHandler<PartitionMessage, Object> {
        private final int[] branchCalls;

        private CountingHandler(int[] branchCalls) {
            this.branchCalls = branchCalls;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<PartitionMessage> src, ProtoSndQueue<Object> dst) {
            this.branchCalls[0]++;
            src.skipMessage(src.queueSize());
            return ProtoStatus.Next;
        }
    }

    private static class PartitionMessage {
        private final int     partitionId;
        private final String  body;
        private final boolean terminal;

        private PartitionMessage(int partitionId, String body, boolean terminal) {
            this.partitionId = partitionId;
            this.body = body;
            this.terminal = terminal;
        }

        public int partitionId() {
            return this.partitionId;
        }

        public String body() {
            return this.body;
        }

        public boolean isTerminal() {
            return this.terminal;
        }
    }
}