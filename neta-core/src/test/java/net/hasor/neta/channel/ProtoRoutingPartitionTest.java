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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Assert;
import org.junit.Test;

public class ProtoRoutingPartitionTest extends AbstractStackTest {
    @Test
    public void interleavedPartitionsKeepIndependentState() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()))).nextEncoder("pass-through", new PassThroughEncoder()).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<PartitionMessage> outbound = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> outbound.add((PartitionMessage) data.getData()));

        channel.receiveData(new PartitionMessage(1, "A", false));
        channel.receiveData(new PartitionMessage(2, "X", false));
        channel.receiveData(new PartitionMessage(1, "B", true));
        channel.receiveData(new PartitionMessage(2, "Y", true));

        Assert.assertEquals(2, outbound.size());
        Assert.assertEquals(1, outbound.get(0).partitionId());
        Assert.assertEquals("AB", outbound.get(0).body());
        Assert.assertEquals(2, outbound.get(1).partitionId());
        Assert.assertEquals("XY", outbound.get(1).body());
    }

    @Test
    public void terminalMessageClosesPartitionState() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()))).nextEncoder("pass-through", new PassThroughEncoder()).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<PartitionMessage> outbound = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> outbound.add((PartitionMessage) data.getData()));

        channel.receiveData(new PartitionMessage(7, "AB", true));
        channel.receiveData(new PartitionMessage(7, "C", true));

        Assert.assertEquals(2, outbound.size());
        Assert.assertEquals("AB", outbound.get(0).body());
        Assert.assertEquals("C", outbound.get(1).body());
    }

    @Test
    public void unmatchedMessagesAreIgnored() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class).nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("collector", new CollectingHandler()))).nextEncoder("pass-through", new PassThroughEncoder()).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<PartitionMessage> outbound = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> outbound.add((PartitionMessage) data.getData()));

        channel.receiveData(new PartitionMessage(0, "IGNORED", true));
        channel.receiveData(new PartitionMessage(1, "A", false));
        channel.receiveData(new PartitionMessage(0, "IGNORED", true));
        channel.receiveData(new PartitionMessage(1, "B", true));

        Assert.assertEquals(1, outbound.size());
        Assert.assertEquals(1, outbound.get(0).partitionId());
        Assert.assertEquals("AB", outbound.get(0).body());
    }

    @Test
    public void matchedEventReturningFalseShouldStopAtPartition() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.setInitializer(ctx -> ctx.addLastDecoder("event-stop", new EventStopHandler()));

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(2), ctx -> {
        }, new VrtSoConfig());
        ProtoContextService context = extractProtoContext(channel);
        ProtoQueue<PartitionMessage> rcvUp = new ProtoQueue<>(1);
        rcvUp.offerMessage(new PartitionMessage(1, "A", false));

        duplexer.onInit("partition", 1, 1, context);
        duplexer.onMessage(context, true, rcvUp, new ProtoQueue<PartitionMessage>(1), ProtoQueue.emptyRcv(), new ProtoQueue<PartitionMessage>(1));

        boolean result = duplexer.onUserEvent(context, SoUserEventObject.of(null, Integer.class, 1), true);
        Assert.assertFalse(result);
    }

    @Test
    public void pendingPartitionOutputShouldFlushAfterDownstreamHasCapacity() throws Throwable {
        ProtoPartitionDuplexer<PartitionMessage, PartitionMessage> duplexer = new ProtoPartitionDuplexer<>(new MessagePartitionSelector());
        duplexer.setInitializer(ctx -> ctx.addLastDecoder("splitter", new SplitOutputHandler()));

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(3), ctx -> {
        }, new VrtSoConfig());
        ProtoContextService context = extractProtoContext(channel);
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
    public void writableRecoveryShouldRestartFromRootDirection() throws Throwable {
        List<String> downstream = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(1);

        ProtoInitializer initializer = ProtoHelper.typed(PartitionMessage.class, PartitionMessage.class)
                .nextPartition("partition", new MessagePartitionSelector(), partition -> partition.byInitializer(ctx -> ctx.addLastDecoder("splitter", new SplitOutputHandler())))
                .nextDecoder("collector", limitedConfig, new DownstreamCollectHandler(downstream))
                .build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(4), initializer, new VrtSoConfig());
        channel.receiveData(new PartitionMessage(1, "A", false));

        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals("A-1", downstream.get(0));
        Assert.assertEquals("A-2", downstream.get(1));
    }

    private static ProtoContextService extractProtoContext(SoChannel<?> channel) throws Exception {
        Field field = NetChannel.class.getDeclaredField("protoCtx");
        field.setAccessible(true);
        return (ProtoContextService) field.get(channel);
    }

    private static class MessagePartitionSelector implements ProtoPartitionSelector<PartitionMessage> {
        @Override
        public String route(ProtoContext context, boolean isRcv, PartitionMessage message) {
            return message == null || message.partitionId() <= 0 ? null : String.valueOf(message.partitionId());
        }

        @Override
        public String route(ProtoContext context, boolean isRcv, SoUserEvent event) {
            Object eventData = event == null ? null : event.getData();
            if (!(eventData instanceof Number)) {
                return null;
            }
            int partitionId = ((Number) eventData).intValue();
            return partitionId <= 0 ? null : String.valueOf(partitionId);
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
                    ProtoPartitionDuplexer.PartitionKeyHolder<?> holder = context.context(ProtoPartitionDuplexer.PartitionKeyHolder.class);
                    Integer partitionId = holder == null || holder.getPartitionKey() == null ? null : Integer.valueOf(String.valueOf(holder.getPartitionKey()));
                    context.sendData(new PartitionMessage(partitionId == null ? 0 : partitionId, builder.toString(), true)).get();
                    builder.setLength(0);
                }
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
        public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
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