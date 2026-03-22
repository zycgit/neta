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
 * @version : 2024-01-15
 */
public class ProtoRoutingHandshakeTest extends AbstractStackTest {
    /** peek 不消费，数据在路由队列中积累，积累到 3 条时激活分支，积累的数据一次性 flush */
    @Test
    public void peekAccumulate_activateOnSizeThree() throws Throwable {
        List<String> branchLog = new ArrayList<>(), branchErr = new ArrayList<>();
        List<Integer> selectorSeenSizes = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    selectorSeenSizes.add(rcvUp.queueSize());
                    return rcvUp.queueSize() >= 3 ? "main" : null;
                }, r -> {
                    r.branch("main", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("mainH",//
                            doNextHandler("Main", branchLog, branchErr),//
                            doNextHandler("Main", branchLog, branchErr)));
                }).build();

        //
        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        //
        // onActive → size=0 → null
        // onReceive(10) → size=1 → null
        // onReceive(20) → size=2 → null
        // onReceive(30) → size=3 → "main"，三条数据一起 flush
        channel.receiveData(10);
        // step1: 路由未生效，selector 已调用 2 次，received 仍空
        Assert.assertEquals(2, selectorSeenSizes.size());
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(20);
        // step2: 路由未生效，selector 已调用 3 次，received 仍空
        Assert.assertEquals(3, selectorSeenSizes.size());
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(30);
        // step3: 路由生效，3 条积累数据一次性 flush

        Assert.assertEquals(4, selectorSeenSizes.size());
        Assert.assertEquals(0, (int) selectorSeenSizes.get(0)); // onActive probe
        Assert.assertEquals(1, (int) selectorSeenSizes.get(1));
        Assert.assertEquals(2, (int) selectorSeenSizes.get(2));
        Assert.assertEquals(3, (int) selectorSeenSizes.get(3));
        Assert.assertEquals(3, received.size());
        Assert.assertEquals(10, received.get(0));
        Assert.assertEquals(20, received.get(1));
        Assert.assertEquals(30, received.get(2));
    }

    /** peek 不消费，始终返回 null，数据无限积累，不向下流转 */
    @Test
    public void peekAccumulate_neverActivate() throws Throwable {
        List<Integer> selectorSeenSizes = new ArrayList<>();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    selectorSeenSizes.add(rcvUp.queueSize());
                    return null;
                }, r -> {
                    r.branch("main", (ProtoBuilder<Integer, Integer> branch) -> {
                        branch.nextDuplex("mainH",//
                                doNextHandler("Main", new ArrayList<>(), new ArrayList<>()),//
                                doNextHandler("Main", new ArrayList<>(), new ArrayList<>()));
                    });
                }).build();

        //
        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        // onActive → size=0 → null
        // onReceive(1) → size=1 → null
        // onReceive(2) → size=2 → null
        // onReceive(3) → size=3 → null
        channel.receiveData(1);
        // step1: 路由未生效，selector 已调用 2 次，received 仍空
        Assert.assertEquals(2, selectorSeenSizes.size());
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(2);
        // step2: 路由未生效，selector 已调用 3 次，received 仍空
        Assert.assertEquals(3, selectorSeenSizes.size());
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(3);
        // step3: selector 仍返回 null，路由始终未生效，received 仍空

        Assert.assertEquals(4, selectorSeenSizes.size());
        Assert.assertEquals(0, (int) selectorSeenSizes.get(0)); // onActive probe
        Assert.assertEquals(1, (int) selectorSeenSizes.get(1));
        Assert.assertEquals(2, (int) selectorSeenSizes.get(2));
        Assert.assertEquals(3, (int) selectorSeenSizes.get(3));
        Assert.assertTrue(received.isEmpty());
    }

    /** 握手阶段每次消费全部数据并丢弃，消费 3 次后激活路由，之后数据正常流转 */
    @Test
    public void consumeHandshake_activateAfterThree_thenDataFlows() throws Throwable {
        List<String> branchLog = new ArrayList<>(), branchErr = new ArrayList<>();
        List<Integer> selectorSeenSizes = new ArrayList<>();
        int[] handshakeCount = { 0 };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", (ProtoRoutingDataSelector<Integer, Integer>) (ctx, rcvUp, rcvDown) -> {
                    selectorSeenSizes.add(rcvUp.queueSize());
                    if (rcvUp.queueSize() > 0) {
                        rcvUp.takeMessage(rcvUp.queueSize());
                        handshakeCount[0]++;
                    }
                    return handshakeCount[0] >= 3 ? "main" : null;
                }, r -> {
                    r.branch("main", (ProtoBuilder<Integer, Integer> branch) -> branch.nextDuplex("mainH",//
                            doNextHandler("Main", branchLog, branchErr),//
                            doNextHandler("Main", branchLog, branchErr)));
                }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
        List<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> received.add(data.getData()));

        // onActive    → size=0 → null
        // onReceive(10) → size=1, consume → count=1 → null
        // onReceive(20) → size=1, consume → count=2 → null
        // onReceive(30) → size=1, consume → count=3 → "main"（路由锁定）
        // onReceive(40) → fast-path，不再调用 selector → branch
        // onReceive(50) → fast-path → branch
        channel.receiveData(10);
        // step1: 握手包已消费，路由未生效，count=1，received 仍空
        Assert.assertEquals(2, selectorSeenSizes.size());
        Assert.assertEquals(1, handshakeCount[0]);
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(20);
        // step2: 握手包已消费，路由未生效，count=2，received 仍空
        Assert.assertEquals(3, selectorSeenSizes.size());
        Assert.assertEquals(2, handshakeCount[0]);
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(30);
        // step3: 握手包已消费，路由锁定，但 30 被 selector 丢弃，received 仍空
        Assert.assertEquals(4, selectorSeenSizes.size());
        Assert.assertEquals(3, handshakeCount[0]);
        Assert.assertTrue(received.isEmpty());

        channel.receiveData(40);
        // step4: fast-path，40 流入分支
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(40, received.get(0));

        channel.receiveData(50);

        // selector 仅被调用 4 次（onActive + 3 次握手），路由锁定后不再调用
        Assert.assertEquals(4, selectorSeenSizes.size());
        Assert.assertEquals(0, (int) selectorSeenSizes.get(0)); // onActive probe
        Assert.assertEquals(1, (int) selectorSeenSizes.get(1));
        Assert.assertEquals(1, (int) selectorSeenSizes.get(2)); // 上一次已清空，所以还是 1
        Assert.assertEquals(1, (int) selectorSeenSizes.get(3));
        // 握手包（10/20/30）已被 selector 消费，不进 received；只有 40/50 流到分支
        Assert.assertEquals(2, received.size());
        Assert.assertEquals(40, received.get(0));
        Assert.assertEquals(50, received.get(1));
    }
}
