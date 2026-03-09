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
import org.junit.Assert;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public class ProtoRoutingModeTest {
    @Test
    public void realtimeMode_recomputeRouteForEveryInboundMessage() throws Throwable {
        RecordHandler.reset();
        ProtoInitializer initializer = ProtoHelper          //
                .typed(Integer.class, Integer.class) //
                .<Integer, Integer>nextRouteAsRealtime("router", (ctx, rcvUp, rcvDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    Integer value = rcvUp.peekMessage();
                    return value != null && value % 2 == 0 ? "even" : "odd";
                }, router -> {
                    router.branch("even", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("even", new RecordHandler("even")));
                    router.branch("odd", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("odd", new RecordHandler("odd")));
                }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.onReceive(2);
        channel.onReceive(3);

        Assert.assertEquals(2, received.size());
        Assert.assertEquals(2, received.get(0));
        Assert.assertEquals(3, received.get(1));
        Assert.assertEquals(1, RecordHandler.activeCount("even"));
        Assert.assertEquals(1, RecordHandler.activeCount("odd"));
        Assert.assertEquals(1, RecordHandler.messageCount("even"));
        Assert.assertEquals(1, RecordHandler.messageCount("odd"));
    }

    @Test
    public void staticMode_onlySelectedBranchReceivesOnActive() throws Throwable {
        RecordHandler.reset();
        ProtoRoutingDuplexer<Integer, Integer> router = new ProtoRoutingDuplexer<>(ProtoRoutingMode.STATIC, (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            Integer value = rcvUp.peekMessage();
            return value != null && value % 2 == 0 ? "even" : "odd";
        });
        router.addBranch("even", c -> c.addLastDecoder("even", new RecordHandler("even")));
        router.addBranch("odd", c -> c.addLastDecoder("odd", new RecordHandler("odd")));
        router.activateBranch("odd");

        ProtoInitializer initializer = ctx -> ctx.addLast("router", router);
        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.onReceive(7);

        Assert.assertEquals(1, RecordHandler.activeCount("odd"));
        Assert.assertEquals(0, RecordHandler.activeCount("even"));
        Assert.assertEquals(1, RecordHandler.messageCount("odd"));
        Assert.assertEquals(0, RecordHandler.messageCount("even"));
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(7, received.get(0));
    }

    @Test
    public void switchingBackToActivatedBranch_firesRouteEventInsteadOfSecondOnActive() throws Throwable {
        SwitchHandler.reset();
        List<String> routeEvents = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).<Integer, Integer>nextRouteAsStatic("router", (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            return "alpha";
        }, r -> {
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SwitchHandler("alpha", 10, "beta", routeEvents)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SwitchHandler("beta", 20, "alpha", routeEvents)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.onReceive(10);
        channel.onReceive(20);
        channel.onReceive(30);

        Assert.assertEquals(3, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(30, received.get(2));
        Assert.assertEquals(1, SwitchHandler.activeCount("alpha"));
        Assert.assertEquals(1, SwitchHandler.activeCount("beta"));
        Assert.assertEquals(1, routeEvents.size());
        Assert.assertEquals("beta->alpha", routeEvents.get(0));
    }

    @Test
    public void nestedRouter_prefersNearestRoutingControl() throws Throwable {
        SwitchHandler.reset();
        List<String> routeEvents = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).<Integer, Integer>nextRouteAsStatic("outer", (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            return "nested";
        }, r -> {
            r.branch("nested", branch -> branch.<Integer, Integer>nextRouteAsStatic("inner", (ctx2, rcvUp2, rcvDown2) -> {
                if (rcvUp2.queueSize() == 0) {
                    return null;
                }
                return "left";
            }, r2 -> {
                r2.branch("left", (ProtoBuilder<Integer, Integer> c2) -> c2.nextDecoder("left", new SwitchHandler("left", 10, "right", routeEvents)));
                r2.branch("right", (ProtoBuilder<Integer, Integer> c2) -> c2.nextDecoder("right", new SwitchHandler("right", Integer.MIN_VALUE, "right", routeEvents)));
            }));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.onReceive(10);
        channel.onReceive(20);

        Assert.assertEquals(2, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(1, SwitchHandler.activeCount("left"));
        Assert.assertEquals(1, SwitchHandler.activeCount("right"));
        Assert.assertTrue(routeEvents.isEmpty());
    }

    private static class RecordHandler implements ProtoHandler<Integer, Integer> {
        private static final java.util.Map<String, Integer> ACTIVE_COUNTS  = new java.util.HashMap<>();
        private static final java.util.Map<String, Integer> MESSAGE_COUNTS = new java.util.HashMap<>();
        private final        String                         name;

        private RecordHandler(String name) {
            this.name = name;
        }

        static int activeCount(String name) {
            return ACTIVE_COUNTS.getOrDefault(name, 0);
        }

        static int messageCount(String name) {
            return MESSAGE_COUNTS.getOrDefault(name, 0);
        }

        static void reset() {
            ACTIVE_COUNTS.clear();
            MESSAGE_COUNTS.clear();
        }

        @Override
        public void onActive(ProtoContext context) {
            ACTIVE_COUNTS.put(this.name, ACTIVE_COUNTS.getOrDefault(this.name, 0) + 1);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            MESSAGE_COUNTS.put(this.name, MESSAGE_COUNTS.getOrDefault(this.name, 0) + 1);
            dst.offerMessage(src.takeMessage(src.queueSize()));
            return ProtoStatus.Next;
        }
    }

    private static class SwitchHandler implements ProtoHandler<Integer, Integer> {
        private static final java.util.Map<String, Integer> ACTIVE_COUNTS = new java.util.HashMap<>();
        private final        String                         name;
        private final        int                            triggerValue;
        private final        String                         targetRoute;
        private final        List<String>                   routeEvents;

        private SwitchHandler(String name, int triggerValue, String targetRoute, List<String> routeEvents) {
            this.name = name;
            this.triggerValue = triggerValue;
            this.targetRoute = targetRoute;
            this.routeEvents = routeEvents;
        }

        static int activeCount(String name) {
            return ACTIVE_COUNTS.getOrDefault(name, 0);
        }

        static void reset() {
            ACTIVE_COUNTS.clear();
        }

        @Override
        public void onActive(ProtoContext context) {
            ACTIVE_COUNTS.put(this.name, ACTIVE_COUNTS.getOrDefault(this.name, 0) + 1);
        }

        @Override
        public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
            if (event.getEventType() == ProtoRouteEvent.class) {
                ProtoRouteEvent changedEvent = (ProtoRouteEvent) event.getData();
                this.routeEvents.add(changedEvent.getFromRoute() + "->" + changedEvent.getToRoute());
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            dst.offerMessage(value);
            if (value != null && value == this.triggerValue) {
                ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
                Assert.assertNotNull(routingControl);
                routingControl.switchRoute(this.targetRoute);
            }
            return ProtoStatus.Next;
        }
    }
}