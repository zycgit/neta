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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public class ProtoRoutingDataSelectorTest extends AbstractStackTest {

    /** 选 "even" 分支 */
    @Test
    public void selectEvenBranch() throws Throwable {
        List<String> evenDecLog = new ArrayList<>(), evenDecErr = new ArrayList<>();
        List<String> evenEncLog = new ArrayList<>(), evenEncErr = new ArrayList<>();
        List<String> oddDecLog = new ArrayList<>(), oddDecErr = new ArrayList<>();
        List<String> oddEncLog = new ArrayList<>(), oddEncErr = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    Integer data = rcvUp.peekMessage();
                    return (data != null && data % 2 == 0) ? "even" : "odd";
                }, r -> {
                    r.branch("even", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("evenDec",//
                            doNextHandler("Even", evenDecLog, evenDecErr),//
                            doNextHandler("Even", evenEncLog, evenEncErr)));
                    r.branch("odd", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("oddDec",//
                            doNextHandler("Odd", oddDecLog, oddDecErr),//
                            doNextHandler("Odd", oddEncLog, oddEncErr)));
                }).build();

        //
        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(42); // even → "even" branch

        //
        Assert.assertEquals("EvenDoNext", StringUtils.join(evenDecLog.toArray(), ","));
        Assert.assertEquals("EvenDoNext", StringUtils.join(evenEncLog.toArray(), ","));
        Assert.assertTrue(oddDecLog.isEmpty());
        Assert.assertTrue(oddEncLog.isEmpty());
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(42, received.get(0));
    }

    /** 选 "odd" 分支 */
    @Test
    public void selectOddBranch() throws Throwable {
        List<String> evenDecLog = new ArrayList<>(), evenDecErr = new ArrayList<>();
        List<String> evenEncLog = new ArrayList<>(), evenEncErr = new ArrayList<>();
        List<String> oddDecLog = new ArrayList<>(), oddDecErr = new ArrayList<>();
        List<String> oddEncLog = new ArrayList<>(), oddEncErr = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    Integer data = rcvUp.peekMessage();
                    return (data != null && data % 2 == 0) ? "even" : "odd";
                }, r -> {
                    r.branch("even", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("evenDec",//
                            doNextHandler("Even", evenDecLog, evenDecErr),//
                            doNextHandler("Even", evenEncLog, evenEncErr)));
                    r.branch("odd", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("oddDec",//
                            doNextHandler("Odd", oddDecLog, oddDecErr),//
                            doNextHandler("Odd", oddEncLog, oddEncErr)));
                }).build();

        //
        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(7); // odd → "odd" branch

        //
        Assert.assertTrue(evenDecLog.isEmpty());
        Assert.assertTrue(evenEncLog.isEmpty());
        Assert.assertEquals("OddDoNext", StringUtils.join(oddDecLog.toArray(), ","));
        Assert.assertEquals("OddDoNext", StringUtils.join(oddEncLog.toArray(), ","));
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(7, received.get(0));
    }

    /** 返回 null，没有选择任何分支 */
    @Test
    public void selectNull_messageDropped() throws Throwable {
        List<String> evenDecLog = new ArrayList<>(), evenDecErr = new ArrayList<>();
        List<String> evenEncLog = new ArrayList<>(), evenEncErr = new ArrayList<>();
        List<String> oddDecLog = new ArrayList<>(), oddDecErr = new ArrayList<>();
        List<String> oddEncLog = new ArrayList<>(), oddEncErr = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    return null;
                }, r -> {
                    r.branch("even", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("evenDec",//
                            doNextHandler("Even", evenDecLog, evenDecErr),//
                            doNextHandler("Even", evenEncLog, evenEncErr)));
                    r.branch("odd", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("oddDec",//
                            doNextHandler("Odd", oddDecLog, oddDecErr),//
                            doNextHandler("Odd", oddEncLog, oddEncErr)));
                }).build();

        //
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(5); // selector returns null → no branch

        //
        Assert.assertTrue(evenDecLog.isEmpty());
        Assert.assertTrue(evenEncLog.isEmpty());
        Assert.assertTrue(oddDecLog.isEmpty());
        Assert.assertTrue(oddEncLog.isEmpty());
        Assert.assertTrue(received.isEmpty());
    }

    /** 2层嵌套路由：L1 按正负分流，L2 居内按大小分流。路径：positive+small / positive+large / nonPos */
    private VrtChannel buildNestedRoutingChannel(NetManager netManager,//
            List<String> smallDecLog, List<String> smallDecErr, List<String> smallEncLog, List<String> smallEncErr,//
            List<String> largeDecLog, List<String> largeDecErr, List<String> largeEncLog, List<String> largeEncErr,//
            List<String> nonPosDecLog, List<String> nonPosDecErr, List<String> nonPosEncLog, List<String> nonPosEncErr) throws IOException {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("L1", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    Integer v = rcvUp.peekMessage();
                    return (v != null && v > 0) ? "positive" : "nonPos";
                }, r -> {
                    r.branch("positive", branch -> {
                        branch.nextRouteAsStatic("L2", (ProtoRoutingDataSelector<Integer, Integer>) (ctx2, rcvUp, rcvDown) -> {
                            if (rcvUp.queueSize() == 0) {
                                return null;
                            }
                            Integer v = rcvUp.peekMessage();
                            return (v != null && v < 10) ? "small" : "large";
                        }, r2 -> {
                            r2.branch("small", (ProtoBuilder<Integer, Integer> sc) -> sc.nextDuplex("smallH",//
                                    doNextHandler("Small", smallDecLog, smallDecErr),//
                                    doNextHandler("Small", smallEncLog, smallEncErr)));
                            r2.branch("large", (ProtoBuilder<Integer, Integer> lc) -> lc.nextDuplex("largeH",//
                                    doNextHandler("Large", largeDecLog, largeDecErr),//
                                    doNextHandler("Large", largeEncLog, largeEncErr)));
                        });
                    });
                    r.branch("nonPos", (ProtoBuilder<Integer, Integer> nonPos) -> nonPos.nextDuplex("nonPosH",//
                            doNextHandler("NonPos", nonPosDecLog, nonPosDecErr),//
                            doNextHandler("NonPos", nonPosEncLog, nonPosEncErr)));
                }).build();
        return (VrtChannel) netManager.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
    }

    /** L2路径： positive(<10) → L1:positive → L2:small */
    @Test
    public void nestedRoute_positiveSmall() throws Throwable {
        List<String> smallDecLog = new ArrayList<>(), smallDecErr = new ArrayList<>();
        List<String> smallEncLog = new ArrayList<>(), smallEncErr = new ArrayList<>();
        List<String> largeDecLog = new ArrayList<>(), largeDecErr = new ArrayList<>();
        List<String> largeEncLog = new ArrayList<>(), largeEncErr = new ArrayList<>();
        List<String> nonPosDecLog = new ArrayList<>(), nonPosDecErr = new ArrayList<>();
        List<String> nonPosEncLog = new ArrayList<>(), nonPosEncErr = new ArrayList<>();

        //
        NetManager manager = new NetManager();
        VrtChannel channel = buildNestedRoutingChannel(manager,//
                smallDecLog, smallDecErr, smallEncLog, smallEncErr,//
                largeDecLog, largeDecErr, largeEncLog, largeEncErr,//
                nonPosDecLog, nonPosDecErr, nonPosEncLog, nonPosEncErr);
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(5); // positive + <10 → L2:small

        //
        Assert.assertEquals("smallDecLog=" + smallDecLog, "SmallDoNext", StringUtils.join(smallDecLog.toArray(), ","));
        Assert.assertEquals("smallEncLog=" + smallEncLog, "SmallDoNext", StringUtils.join(smallEncLog.toArray(), ","));
        Assert.assertTrue("largeDecLog should be empty: " + largeDecLog, largeDecLog.isEmpty());
        Assert.assertTrue("nonPosDecLog should be empty: " + nonPosDecLog, nonPosDecLog.isEmpty());
        Assert.assertEquals("received size", 1, received.size());
        Assert.assertEquals("received[0]", 5, received.get(0));
        manager.shutdown();
    }

    /** L2路径： positive(>=10) → L1:positive → L2:large */
    @Test
    public void nestedRoute_positiveLarge() throws Throwable {
        List<String> smallDecLog = new ArrayList<>(), smallDecErr = new ArrayList<>();
        List<String> smallEncLog = new ArrayList<>(), smallEncErr = new ArrayList<>();
        List<String> largeDecLog = new ArrayList<>(), largeDecErr = new ArrayList<>();
        List<String> largeEncLog = new ArrayList<>(), largeEncErr = new ArrayList<>();
        List<String> nonPosDecLog = new ArrayList<>(), nonPosDecErr = new ArrayList<>();
        List<String> nonPosEncLog = new ArrayList<>(), nonPosEncErr = new ArrayList<>();

        //
        NetManager manager = new NetManager();
        VrtChannel channel = buildNestedRoutingChannel(manager,//
                smallDecLog, smallDecErr, smallEncLog, smallEncErr,//
                largeDecLog, largeDecErr, largeEncLog, largeEncErr,//
                nonPosDecLog, nonPosDecErr, nonPosEncLog, nonPosEncErr);
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(42); // positive + >=10 → L2:large

        //
        Assert.assertTrue("smallDecLog should be empty: " + smallDecLog, smallDecLog.isEmpty());
        Assert.assertEquals("largeDecLog=" + largeDecLog, "LargeDoNext", StringUtils.join(largeDecLog.toArray(), ","));
        Assert.assertEquals("largeEncLog=" + largeEncLog, "LargeDoNext", StringUtils.join(largeEncLog.toArray(), ","));
        Assert.assertTrue("nonPosDecLog should be empty: " + nonPosDecLog, nonPosDecLog.isEmpty());
        Assert.assertEquals("received size", 1, received.size());
        Assert.assertEquals("received[0]", 42, received.get(0));
        manager.shutdown();
    }

    /** L1路径： <=0 → L1:nonPos */
    @Test
    public void nestedRoute_nonPositive() throws Throwable {
        List<String> smallDecLog = new ArrayList<>(), smallDecErr = new ArrayList<>();
        List<String> smallEncLog = new ArrayList<>(), smallEncErr = new ArrayList<>();
        List<String> largeDecLog = new ArrayList<>(), largeDecErr = new ArrayList<>();
        List<String> largeEncLog = new ArrayList<>(), largeEncErr = new ArrayList<>();
        List<String> nonPosDecLog = new ArrayList<>(), nonPosDecErr = new ArrayList<>();
        List<String> nonPosEncLog = new ArrayList<>(), nonPosEncErr = new ArrayList<>();

        //
        NetManager manager = new NetManager();
        VrtChannel channel = buildNestedRoutingChannel(manager,//
                smallDecLog, smallDecErr, smallEncLog, smallEncErr,//
                largeDecLog, largeDecErr, largeEncLog, largeEncErr,//
                nonPosDecLog, nonPosDecErr, nonPosEncLog, nonPosEncErr);
        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        channel.receiveData(-3); // <=0 → L1:nonPos

        //
        Assert.assertTrue("smallDecLog should be empty: " + smallDecLog, smallDecLog.isEmpty());
        Assert.assertTrue("largeDecLog should be empty: " + largeDecLog, largeDecLog.isEmpty());
        Assert.assertEquals("nonPosDecLog=" + nonPosDecLog, "NonPosDoNext", StringUtils.join(nonPosDecLog.toArray(), ","));
        Assert.assertEquals("nonPosEncLog=" + nonPosEncLog, "NonPosDoNext", StringUtils.join(nonPosEncLog.toArray(), ","));
        Assert.assertEquals("received size", 1, received.size());
        Assert.assertEquals("received[0]", -3, received.get(0));
        manager.shutdown();
    }
}
