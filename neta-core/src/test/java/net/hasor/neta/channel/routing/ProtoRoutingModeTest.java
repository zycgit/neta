/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.routing;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-08
 */
public class ProtoRoutingModeTest {
    @Test
    public void realtimeMode_recomputeRouteForEveryInboundMessage() throws Throwable {
        RecordHandler.reset();
        ProtoInitializer initializer = ProtoHelper   //
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

        channel.receiveData(2);
        channel.receiveData(3);

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
        ProtoRoutingDuplex<Integer, Integer> router = new ProtoRoutingDuplex<>(ProtoRoutingMode.STATIC, (ctx, rcvUp, rcvDown) -> {
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

        channel.receiveData(7);

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
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            return "alpha";
        }, r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SwitchHandler("alpha", 10, "beta", routingControl, routeEvents)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SwitchHandler("beta", 20, "alpha", routingControl, routeEvents)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        channel.receiveData(20);
        channel.receiveData(30);

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
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("outer", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            return "nested";
        }, r -> {
            r.branch("nested", branch -> {
                branch.nextRouteAsStatic("inner", (ProtoRoutingDataSelector<Integer, Integer>) (ctx2, rcvUp2, rcvDown2) -> {
                    if (rcvUp2.queueSize() == 0) {
                        return null;
                    }
                    return "left";
                }, r2 -> {
                    ProtoRoutingControl innerControl = r2.control();
                    r2.branch("left", (ProtoBuilder<Integer, Integer> c2) -> {
                        c2.nextDecoder("left", new SwitchHandler("left", 10, "right", innerControl, routeEvents));
                    });
                    r2.branch("right", (ProtoBuilder<Integer, Integer> c2) -> {
                        c2.nextDecoder("right", new SwitchHandler("right", Integer.MIN_VALUE, "right", innerControl, routeEvents));
                    });
                });
            });
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        channel.receiveData(20);

        Assert.assertEquals(2, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(1, SwitchHandler.activeCount("left"));
        Assert.assertEquals(1, SwitchHandler.activeCount("right"));
        Assert.assertTrue(routeEvents.isEmpty());
    }

    @Test
    public void routeSwitchShouldWaitUntilPreviousBranchPendingOutputFlushed() throws Throwable {
        List<Integer> downstream = new ArrayList<>();
        List<String> routeEvents = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(1);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
            if (rcvUp.queueSize() == 0) {
                return null;
            }
            return "alpha";
        }, r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SplitSwitchHandler("beta", routingControl, routeEvents)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new BetaHandler(routeEvents)));
        }).nextDecoder("collector", limitedConfig, new CollectIntegerHandler(downstream)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        channel.receiveData(1);
        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals(Integer.valueOf(101), downstream.get(0));
        Assert.assertEquals(Integer.valueOf(102), downstream.get(1));
        Assert.assertTrue(routeEvents.isEmpty());

        channel.receiveData(2);
        Assert.assertEquals(3, downstream.size());
        Assert.assertEquals(Integer.valueOf(2002), downstream.get(2));
    }

    @Test
    public void nestedRouteSwitchShouldResumeWithinNearestRouterScope() throws Throwable {
        List<Integer> downstream = new ArrayList<>();
        List<String> innerRouteEvents = new ArrayList<>();
        List<String> outerRouteEvents = new ArrayList<>();
        ProtoConfig limitedConfig = new ProtoConfig();
        limitedConfig.setRcvSlotSize(1);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("outer", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "nested", outer -> {
            outer.branch("nested", branch -> branch.nextRouteAsStatic("inner", (ProtoRoutingDataSelector<Integer, Integer>) (ctx2, rcvUp2, rcvDown2) -> rcvUp2.queueSize() == 0 ? null : "alpha", inner -> {
                ProtoRoutingControl innerControl = inner.control();
                inner.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SplitSwitchHandler("beta", innerControl, innerRouteEvents)));
                inner.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new BetaHandler(innerRouteEvents)));
            }).nextDecoder("outer-observer", new RouteEventObserver(outerRouteEvents)));
        }).nextDecoder("collector", limitedConfig, new CollectIntegerHandler(downstream)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        channel.receiveData(1);
        Assert.assertEquals(2, downstream.size());
        Assert.assertEquals(Integer.valueOf(101), downstream.get(0));
        Assert.assertEquals(Integer.valueOf(102), downstream.get(1));
        Assert.assertTrue(innerRouteEvents.isEmpty());
        Assert.assertTrue(outerRouteEvents.isEmpty());

        channel.receiveData(2);
        Assert.assertEquals(3, downstream.size());
        Assert.assertEquals(Integer.valueOf(2002), downstream.get(2));
        Assert.assertTrue(outerRouteEvents.isEmpty());
    }

    @Test
    public void switchRouteWithNext_shouldFinishCurrentBranchThenApplyRouteChange() throws Throwable {
        RecordHandler.reset();
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha-switch", new SwitchStatusHandler(10, "beta", routingControl, ProtoStatus.Next, stepLog)).nextDecoder("alpha-tail", new LogHandler("alpha-tail", stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new RecordHandler("beta")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        channel.receiveData(20);

        Assert.assertEquals(2, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(2, stepLog.size());
        Assert.assertEquals("alpha-switch", stepLog.get(0));
        Assert.assertEquals("alpha-tail", stepLog.get(1));
        Assert.assertEquals(1, RecordHandler.activeCount("beta"));
        Assert.assertEquals(2, RecordHandler.messageCount("beta"));
    }

    @Test
    public void switchRouteWithStop_shouldStopCurrentBranchAndApplyRouteChangeInSameRound() throws Throwable {
        RecordHandler.reset();
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha-switch", new SwitchStatusHandler(10, "beta", routingControl, ProtoStatus.Stop, stepLog)).nextDecoder("alpha-tail", new LogHandler("alpha-tail", stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new RecordHandler("beta")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertEquals(0, received.size());
        Assert.assertEquals(1, stepLog.size());
        Assert.assertEquals("alpha-switch", stepLog.get(0));
        Assert.assertEquals(1, RecordHandler.activeCount("beta"));
        Assert.assertEquals(1, RecordHandler.messageCount("beta"));

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(2, RecordHandler.messageCount("beta"));
    }

    @Test
    public void switchRouteWithAbort_shouldNotApplyUntilNextRouterEntry() throws Throwable {
        RecordHandler.reset();
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha-switch", new SwitchStatusHandler(10, "beta", routingControl, ProtoStatus.Abort, stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new RecordHandler("beta")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(1, stepLog.size());
        Assert.assertEquals("alpha-switch", stepLog.get(0));
        Assert.assertEquals(0, RecordHandler.activeCount("beta"));
        Assert.assertEquals(0, RecordHandler.messageCount("beta"));

        channel.receiveData(20);
        Assert.assertEquals(2, received.size());
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(1, RecordHandler.activeCount("beta"));
        Assert.assertEquals(2, RecordHandler.messageCount("beta"));
    }

    @Test
    public void switchRouteWithSeed_shouldExposeOneShotSeedInSameRouterEntry() throws Throwable {
        List<String> stepLog = new ArrayList<>();
        List<Object> received = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SeedSwitchHandler(routingControl, "beta", stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SeedAwareHandler(routingControl, stepLog)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertTrue(received.isEmpty());
        Assert.assertEquals(3, stepLog.size());
        Assert.assertEquals("alpha-switch", stepLog.get(0));
        Assert.assertEquals("beta-hasSeed=true", stepLog.get(1));
        Assert.assertEquals("beta-seed=1010", stepLog.get(2));

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(5, stepLog.size());
        Assert.assertEquals("beta-hasSeed=false", stepLog.get(3));
        Assert.assertEquals("beta-seed=null", stepLog.get(4));
    }

    @Test
    public void switchRouteNextTickWithSeed_shouldExposeSeedOnNextRouterEntry() throws Throwable {
        List<String> stepLog = new ArrayList<>();
        List<Object> received = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new SeedSwitchHandler(routingControl, "beta", true, stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SeedAwareHandler(routingControl, stepLog)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertTrue(received.isEmpty());
        Assert.assertEquals(1, stepLog.size());
        Assert.assertEquals("alpha-switch", stepLog.get(0));

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(3, stepLog.size());
        Assert.assertEquals("beta-hasSeed=true", stepLog.get(1));
        Assert.assertEquals("beta-seed=1010", stepLog.get(2));
    }

    @Test
    public void switchRouteMultipleTimes_shouldApplyOnlyLastRouteInSameEntry() throws Throwable {
        RecordHandler.reset();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new MultiRouteSwitchHandler(routingControl, false, "beta", "gamma")));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new RecordHandler("beta")));
            r.branch("gamma", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("gamma", new RecordHandler("gamma")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertEquals(0, received.size());
        Assert.assertEquals(0, RecordHandler.activeCount("beta"));
        Assert.assertEquals(1, RecordHandler.activeCount("gamma"));
        Assert.assertEquals(0, RecordHandler.messageCount("beta"));
        Assert.assertEquals(1, RecordHandler.messageCount("gamma"));

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(0, RecordHandler.messageCount("beta"));
        Assert.assertEquals(2, RecordHandler.messageCount("gamma"));
    }

    @Test
    public void switchRouteNextTickMultipleTimes_shouldApplyOnlyLastRouteOnNextEntry() throws Throwable {
        RecordHandler.reset();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new MultiRouteSwitchHandler(routingControl, true, "beta", "gamma")));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new RecordHandler("beta")));
            r.branch("gamma", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("gamma", new RecordHandler("gamma")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertEquals(0, received.size());
        Assert.assertEquals(0, RecordHandler.activeCount("beta"));
        Assert.assertEquals(0, RecordHandler.activeCount("gamma"));
        Assert.assertEquals(0, RecordHandler.messageCount("beta"));
        Assert.assertEquals(0, RecordHandler.messageCount("gamma"));

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(0, RecordHandler.activeCount("beta"));
        Assert.assertEquals(1, RecordHandler.activeCount("gamma"));
        Assert.assertEquals(0, RecordHandler.messageCount("beta"));
        Assert.assertEquals(1, RecordHandler.messageCount("gamma"));
    }

    @Test
    public void switchRouteWithSeedMultipleTimes_shouldExposeOnlyLastSeed() throws Throwable {
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new MultiSeedSwitchHandler(routingControl, false, "beta", 1010, "gamma", 2020)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SeedAwareHandler(routingControl, stepLog)));
            r.branch("gamma", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("gamma", new SeedAwareHandler(routingControl, stepLog, "gamma")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        channel.receiveData(10);
        Assert.assertEquals(2, stepLog.size());
        Assert.assertEquals("gamma-hasSeed=true", stepLog.get(0));
        Assert.assertEquals("gamma-seed=2020", stepLog.get(1));
    }

    @Test
    public void switchRouteNextTickWithSeedMultipleTimes_shouldExposeOnlyLastSeedOnNextEntry() throws Throwable {
        List<String> stepLog = new ArrayList<>();
        List<Object> received = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new MultiSeedSwitchHandler(routingControl, true, "beta", 1010, "gamma", 2020)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SeedAwareHandler(routingControl, stepLog)));
            r.branch("gamma", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("gamma", new SeedAwareHandler(routingControl, stepLog, "gamma")));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertTrue(received.isEmpty());
        Assert.assertTrue(stepLog.isEmpty());

        channel.receiveData(20);
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(20, received.get(0));
        Assert.assertEquals(2, stepLog.size());
        Assert.assertEquals("gamma-hasSeed=true", stepLog.get(0));
        Assert.assertEquals("gamma-seed=2020", stepLog.get(1));
    }

    @Test
    public void switchRouteWithSeed_shouldReleaseOverriddenSeedAndExposeLatestSeed() throws Throwable {
        ByteBuf seedA = ByteBuf.wrap(new byte[] { 1 });
        ByteBuf seedB = ByteBuf.wrap(new byte[] { 2 });
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new ByteBufSeedSwitchHandler(routingControl, "beta", seedA, false, true, stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new ByteBufSeedSwitchHandler(routingControl, "gamma", seedB, true, true, stepLog)));
            r.branch("gamma", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("gamma", new ByteBufSeedObserveHandler(routingControl, stepLog)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        channel.receiveData(10);
        Assert.assertEquals(1, seedA.refCnt());

        channel.receiveData(20);
        Assert.assertEquals(0, seedA.refCnt());
        Assert.assertEquals(1, seedB.refCnt());

        channel.receiveData(30);
        Assert.assertEquals(0, seedB.refCnt());
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(30, received.get(0));
        Assert.assertEquals(2, stepLog.size());
        Assert.assertEquals("beta-override-old=true", stepLog.get(0));
        Assert.assertEquals("gamma-seedRefCnt=1", stepLog.get(1));
    }

    @Test
    public void removeSeed_shouldClearAndReleaseVisibleSeed() throws Throwable {
        ByteBuf seed = ByteBuf.wrap(new byte[] { 7 });
        List<String> stepLog = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() == 0 ? null : "alpha", r -> {
            ProtoRoutingControl routingControl = r.control();
            r.branch("alpha", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("alpha", new ByteBufSeedSwitchHandler(routingControl, "beta", seed, false, true, stepLog)));
            r.branch("beta", (ProtoBuilder<Integer, Integer> c) -> c.nextDecoder("beta", new SeedRemoveHandler(routingControl, stepLog)));
        }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        channel.receiveData(10);
        Assert.assertEquals(1, seed.refCnt());

        channel.receiveData(20);
        Assert.assertEquals(0, seed.refCnt());
        Assert.assertEquals(2, stepLog.size());
        Assert.assertEquals("beta-beforeRemove=true", stepLog.get(0));
        Assert.assertEquals("beta-afterRemove=false", stepLog.get(1));
    }

    private static class RecordHandler implements ProtoHandler<Integer, Integer> {
        private static final java.util.Map<String, Integer> ACTIVE_COUNTS  = new java.util.HashMap<>();
        private static final java.util.Map<String, Integer> MESSAGE_COUNTS = new java.util.HashMap<>();
        private final String                                name;

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
        private final String                                name;
        private final int                                   triggerValue;
        private final String                                targetRoute;
        private final ProtoRoutingControl                   routingControl;
        private final List<String>                          routeEvents;

        private SwitchHandler(String name, int triggerValue, String targetRoute, ProtoRoutingControl routingControl, List<String> routeEvents) {
            this.name = name;
            this.triggerValue = triggerValue;
            this.targetRoute = targetRoute;
            this.routingControl = routingControl;
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
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (event.getEventType() == ProtoRouteEvent.class) {
                ProtoRouteEvent changedEvent = (ProtoRouteEvent) event.getData();
                this.routeEvents.add(changedEvent.getFromRoute() + "->" + changedEvent.getToRoute());
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                dst.offerMessage(value);
            }
            if (value != null && value == this.triggerValue) {
                Assert.assertNotNull(this.routingControl);
                this.routingControl.switchRoute(this.targetRoute);
            }
            return ProtoStatus.Next;
        }
    }

    private static class SplitSwitchHandler implements ProtoHandler<Integer, Integer> {
        private final String              targetRoute;
        private final ProtoRoutingControl routingControl;
        private final List<String>        routeEvents;

        private SplitSwitchHandler(String targetRoute, ProtoRoutingControl routingControl, List<String> routeEvents) {
            this.targetRoute = targetRoute;
            this.routingControl = routingControl;
            this.routeEvents = routeEvents;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (event.getEventType() == ProtoRouteEvent.class) {
                ProtoRouteEvent changedEvent = (ProtoRouteEvent) event.getData();
                this.routeEvents.add(changedEvent.getFromRoute() + "->" + changedEvent.getToRoute());
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value == null) {
                return ProtoStatus.Next;
            }
            dst.offerMessage(value + 100);
            dst.offerMessage(value + 101);
            Assert.assertNotNull(this.routingControl);
            this.routingControl.switchRoute(this.targetRoute);
            return ProtoStatus.Next;
        }
    }

    private static class BetaHandler implements ProtoHandler<Integer, Integer> {
        private final List<String> routeEvents;

        private BetaHandler(List<String> routeEvents) {
            this.routeEvents = routeEvents;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (event.getEventType() == ProtoRouteEvent.class) {
                ProtoRouteEvent changedEvent = (ProtoRouteEvent) event.getData();
                this.routeEvents.add(changedEvent.getFromRoute() + "->" + changedEvent.getToRoute());
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                dst.offerMessage(value + 2000);
            }
            return ProtoStatus.Next;
        }
    }

    private static class CollectIntegerHandler implements ProtoHandler<Integer, Integer> {
        private final List<Integer> downstream;

        private CollectIntegerHandler(List<Integer> downstream) {
            this.downstream = downstream;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            while (src.hasMore()) {
                this.downstream.add(src.takeMessage());
            }
            return ProtoStatus.Next;
        }
    }

    private static class RouteEventObserver implements ProtoHandler<Integer, Integer> {
        private final List<String> routeEvents;

        private RouteEventObserver(List<String> routeEvents) {
            this.routeEvents = routeEvents;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            if (event.getEventType() == ProtoRouteEvent.class) {
                ProtoRouteEvent changedEvent = (ProtoRouteEvent) event.getData();
                this.routeEvents.add(changedEvent.getFromRoute() + "->" + changedEvent.getToRoute());
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            dst.offerMessage(src.takeMessage(src.queueSize()));
            return ProtoStatus.Next;
        }
    }

    private static class SwitchStatusHandler implements ProtoHandler<Integer, Integer> {
        private final int                 triggerValue;
        private final String              targetRoute;
        private final ProtoRoutingControl routingControl;
        private final ProtoStatus         returnStatus;
        private final List<String>        stepLog;

        private SwitchStatusHandler(int triggerValue, String targetRoute, ProtoRoutingControl routingControl, ProtoStatus returnStatus, List<String> stepLog) {
            this.triggerValue = triggerValue;
            this.targetRoute = targetRoute;
            this.routingControl = routingControl;
            this.returnStatus = returnStatus;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                this.stepLog.add("alpha-switch");
                dst.offerMessage(value);
                if (value == this.triggerValue) {
                    Assert.assertNotNull(this.routingControl);
                    this.routingControl.switchRoute(this.targetRoute);
                    return this.returnStatus;
                }
            }
            return ProtoStatus.Next;
        }
    }

    private static class SeedSwitchHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final String              targetRoute;
        private final boolean             nextTick;
        private final List<String>        stepLog;

        private SeedSwitchHandler(ProtoRoutingControl routingControl, String targetRoute, List<String> stepLog) {
            this(routingControl, targetRoute, false, stepLog);
        }

        private SeedSwitchHandler(ProtoRoutingControl routingControl, String targetRoute, boolean nextTick, List<String> stepLog) {
            this.routingControl = routingControl;
            this.targetRoute = targetRoute;
            this.nextTick = nextTick;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                this.stepLog.add("alpha-switch");
                if (this.nextTick) {
                    this.routingControl.switchRouteNextTick(this.targetRoute, value + 1000);
                } else {
                    this.routingControl.switchRoute(this.targetRoute, value + 1000);
                }
                return ProtoStatus.Stop;
            }
            return ProtoStatus.Next;
        }
    }

    private static class SeedAwareHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final List<String>        stepLog;
        private final String              prefix;

        private SeedAwareHandler(ProtoRoutingControl routingControl, List<String> stepLog) {
            this(routingControl, stepLog, "beta");
        }

        private SeedAwareHandler(ProtoRoutingControl routingControl, List<String> stepLog, String prefix) {
            this.routingControl = routingControl;
            this.stepLog = stepLog;
            this.prefix = prefix;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            this.stepLog.add(this.prefix + "-hasSeed=" + this.routingControl.hasSeed());
            this.stepLog.add(this.prefix + "-seed=" + this.routingControl.takeSeed());
            Integer value = src.takeMessage();
            if (value != null) {
                dst.offerMessage(value);
            }
            return ProtoStatus.Next;
        }
    }

    private static class MultiRouteSwitchHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final boolean             nextTick;
        private final String              firstRoute;
        private final String              secondRoute;

        private MultiRouteSwitchHandler(ProtoRoutingControl routingControl, boolean nextTick, String firstRoute, String secondRoute) {
            this.routingControl = routingControl;
            this.nextTick = nextTick;
            this.firstRoute = firstRoute;
            this.secondRoute = secondRoute;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                if (this.nextTick) {
                    this.routingControl.switchRouteNextTick(this.firstRoute);
                    this.routingControl.switchRouteNextTick(this.secondRoute);
                } else {
                    this.routingControl.switchRoute(this.firstRoute);
                    this.routingControl.switchRoute(this.secondRoute);
                }
                return ProtoStatus.Stop;
            }
            return ProtoStatus.Next;
        }
    }

    private static class MultiSeedSwitchHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final boolean             nextTick;
        private final String              firstRoute;
        private final Integer             firstSeed;
        private final String              secondRoute;
        private final Integer             secondSeed;

        private MultiSeedSwitchHandler(ProtoRoutingControl routingControl, boolean nextTick, String firstRoute, Integer firstSeed, String secondRoute, Integer secondSeed) {
            this.routingControl = routingControl;
            this.nextTick = nextTick;
            this.firstRoute = firstRoute;
            this.firstSeed = firstSeed;
            this.secondRoute = secondRoute;
            this.secondSeed = secondSeed;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                if (this.nextTick) {
                    this.routingControl.switchRouteNextTick(this.firstRoute, this.firstSeed);
                    this.routingControl.switchRouteNextTick(this.secondRoute, this.secondSeed);
                } else {
                    this.routingControl.switchRoute(this.firstRoute, this.firstSeed);
                    this.routingControl.switchRoute(this.secondRoute, this.secondSeed);
                }
                return ProtoStatus.Stop;
            }
            return ProtoStatus.Next;
        }
    }

    private static class ByteBufSeedSwitchHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final String              targetRoute;
        private final ByteBuf             seed;
        private final boolean             observeBeforeOverride;
        private final boolean             nextTick;
        private final List<String>        stepLog;

        private ByteBufSeedSwitchHandler(ProtoRoutingControl routingControl, String targetRoute, ByteBuf seed, boolean observeBeforeOverride, boolean nextTick, List<String> stepLog) {
            this.routingControl = routingControl;
            this.targetRoute = targetRoute;
            this.seed = seed;
            this.observeBeforeOverride = observeBeforeOverride;
            this.nextTick = nextTick;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Integer value = src.takeMessage();
            if (value != null) {
                if (this.observeBeforeOverride) {
                    this.stepLog.add("beta-override-old=" + this.routingControl.hasSeed());
                }
                if (this.nextTick) {
                    this.routingControl.switchRouteNextTick(this.targetRoute, this.seed);
                } else {
                    this.routingControl.switchRoute(this.targetRoute, this.seed);
                }
                return ProtoStatus.Stop;
            }
            return ProtoStatus.Next;
        }
    }

    private static class ByteBufSeedObserveHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final List<String>        stepLog;

        private ByteBufSeedObserveHandler(ProtoRoutingControl routingControl, List<String> stepLog) {
            this.routingControl = routingControl;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            Object seed = this.routingControl.takeSeed();
            this.stepLog.add("gamma-seedRefCnt=" + ((ByteBuf) seed).refCnt());
            Integer value = src.takeMessage();
            if (value != null) {
                dst.offerMessage(value);
            }
            ((ByteBuf) seed).release();
            return ProtoStatus.Next;
        }
    }

    private static class SeedRemoveHandler implements ProtoHandler<Integer, Integer> {
        private final ProtoRoutingControl routingControl;
        private final List<String>        stepLog;

        private SeedRemoveHandler(ProtoRoutingControl routingControl, List<String> stepLog) {
            this.routingControl = routingControl;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            src.takeMessage();
            this.stepLog.add("beta-beforeRemove=" + this.routingControl.hasSeed());
            this.routingControl.removeSeed();
            this.stepLog.add("beta-afterRemove=" + this.routingControl.hasSeed());
            return ProtoStatus.Next;
        }
    }

    private static class LogHandler implements ProtoHandler<Integer, Integer> {
        private final String       name;
        private final List<String> stepLog;

        private LogHandler(String name, List<String> stepLog) {
            this.name = name;
            this.stepLog = stepLog;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
            this.stepLog.add(this.name);
            dst.offerMessage(src.takeMessage(src.queueSize()));
            return ProtoStatus.Next;
        }
    }
}
