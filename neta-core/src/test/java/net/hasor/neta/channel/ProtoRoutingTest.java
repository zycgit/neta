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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * Tests for {@link ProtoRoutingDuplexer} branching pipeline mechanism.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public class ProtoRoutingTest extends AbstractStackTest {

    // ==================== Basic Routing Tests ====================

    /** Test: route to branch "A" based on data value, verify RCV data flows through branch A's handlers */
    @Test
    public void routing_rcv_branchA() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            // Router: even numbers go to "even", odd numbers go to "odd"
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> {
                Integer data = rcvUp.peekMessage();
                return (data != null && data % 2 == 0) ? "even" : "odd";
            });
            builder.branch("even", branch -> {
                branch.addLast("evenDec", doNextHandler("Even", decoderLog, decoderErr), doNextHandler("Even", encoderLog, encoderErr));
            });
            builder.branch("odd", branch -> {
                branch.addLast("oddDec", doNextHandler("Odd", decoderLog, decoderErr), doNextHandler("Odd", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        // Send even number -> should route to "even" branch
        channel.onReceive(42);

        assert StringUtils.join(decoderLog.toArray(), ",").equals("EvenDoNext") : "decoderLog=" + decoderLog;
        assert StringUtils.join(encoderLog.toArray(), ",").equals("EvenDoNext") : "encoderLog=" + encoderLog;
        assert received.size() == 1 && received.get(0).equals(42) : "received=" + received;
    }

    /** Test: route to branch "B" based on data value */
    @Test
    public void routing_rcv_branchB() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> {
                Integer data = rcvUp.peekMessage();
                return (data != null && data % 2 == 0) ? "even" : "odd";
            });
            builder.branch("even", branch -> {
                branch.addLast("evenDec", doNextHandler("Even", decoderLog, decoderErr), doNextHandler("Even", encoderLog, encoderErr));
            });
            builder.branch("odd", branch -> {
                branch.addLast("oddDec", doNextHandler("Odd", decoderLog, decoderErr), doNextHandler("Odd", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        // Send odd number -> should route to "odd" branch
        channel.onReceive(7);

        assert StringUtils.join(decoderLog.toArray(), ",").equals("OddDoNext") : "decoderLog=" + decoderLog;
        assert StringUtils.join(encoderLog.toArray(), ",").equals("OddDoNext") : "encoderLog=" + encoderLog;
        assert received.size() == 1 && received.get(0).equals(7) : "received=" + received;
    }

    /** Test: once routed, subsequent data uses the cached route */
    @Test
    public void routing_rcv_cachedRoute() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> {
                Integer data = rcvUp.peekMessage();
                return (data != null && data % 2 == 0) ? "even" : "odd";
            });
            builder.branch("even", branch -> {
                branch.addLast("evenDec", doNextHandler("Even", decoderLog, decoderErr), doNextHandler("Even", encoderLog, encoderErr));
            });
            builder.branch("odd", branch -> {
                branch.addLast("oddDec", doNextHandler("Odd", decoderLog, decoderErr), doNextHandler("Odd", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        // First message: even -> routes to "even"
        channel.onReceive(2);
        // Second message: odd value BUT route should be cached as "even"
        channel.onReceive(3);

        assert StringUtils.join(decoderLog.toArray(), ",").equals("EvenDoNext,EvenDoNext") : "decoderLog=" + decoderLog;
        assert received.size() == 2 : "received=" + received;
        assert received.get(0).equals(2) : "first=" + received.get(0);
        assert received.get(1).equals(3) : "second=" + received.get(1);
    }

    // ==================== Multi-layer Branch Tests ====================

    /** Test: branch with multiple handlers in sequence */
    @Test
    public void routing_rcv_multiLayerBranch() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "main");
            builder.branch("main", branch -> {
                branch.addLast("layer1", doNextHandler("L1", decoderLog, decoderErr), doNextHandler("L1", encoderLog, encoderErr));
                branch.addLast("layer2", doNextHandler("L2", decoderLog, decoderErr), doNextHandler("L2", encoderLog, encoderErr));
                branch.addLast("layer3", doNextHandler("L3", decoderLog, decoderErr), doNextHandler("L3", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        channel.onReceive(100);

        // RCV: L1 -> L2 -> L3 (forward), then SND: L3 -> L2 -> L1 (backward)
        assert StringUtils.join(decoderLog.toArray(), ",").equals("L1DoNext,L2DoNext,L3DoNext") : "decoderLog=" + decoderLog;
        assert StringUtils.join(encoderLog.toArray(), ",").equals("L3DoNext,L2DoNext,L1DoNext") : "encoderLog=" + encoderLog;
        assert received.size() == 1 && received.get(0).equals(100) : "received=" + received;
    }

    // ==================== Router with Pre/Post Handlers ====================

    /** Test: handlers before and after the router in the main pipeline */
    @Test
    public void routing_rcv_withMainPipelineHandlers() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            // Pre-router handler in main pipeline
            ctx.addLast("pre", doNextHandler("Pre", decoderLog, decoderErr), doNextHandler("Pre", encoderLog, encoderErr));

            // Router
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "branch1");
            builder.branch("branch1", branch -> {
                branch.addLast("branchHandler", doNextHandler("BR", decoderLog, decoderErr), doNextHandler("BR", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));

            // Post-router handler in main pipeline
            ctx.addLast("post", doNextHandler("Post", decoderLog, decoderErr), doNextHandler("Post", encoderLog, encoderErr));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        channel.onReceive(42);

        // RCV: Pre -> Router(BR) -> Post, SND: Post -> Router(BR) -> Pre
        // Note: BR encoder runs inside the branch's doSndLife, which fires before main pipeline SND path
        assert StringUtils.join(decoderLog.toArray(), ",").equals("PreDoNext,BRDoNext,PostDoNext") : "decoderLog=" + decoderLog;
        assert StringUtils.join(encoderLog.toArray(), ",").equals("BRDoNext,PostDoNext,PreDoNext") : "encoderLog=" + encoderLog;
        assert received.size() == 1 : "received=" + received;
    }

    // ==================== Lifecycle Tests ====================

    /** Test: onInit and onActive are called for ALL branches */
    @Test
    public void routing_lifecycle_allBranches() throws Throwable {
        List<String> lifecycleLog = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "branchA");
            builder.branch("branchA", branch -> {
                branch.addLast("a", new LifecycleTracker("A", lifecycleLog));
            });
            builder.branch("branchB", branch -> {
                branch.addLast("b", new LifecycleTracker("B", lifecycleLog));
            });
            builder.branch("branchC", branch -> {
                branch.addLast("c", new LifecycleTracker("C", lifecycleLog));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        // onInit and onActive should have been called for all branches
        assert lifecycleLog.contains("A:onInit") : "missing A:onInit, log=" + lifecycleLog;
        assert lifecycleLog.contains("B:onInit") : "missing B:onInit, log=" + lifecycleLog;
        assert lifecycleLog.contains("C:onInit") : "missing C:onInit, log=" + lifecycleLog;
        assert lifecycleLog.contains("A:onActive") : "missing A:onActive, log=" + lifecycleLog;
        assert lifecycleLog.contains("B:onActive") : "missing B:onActive, log=" + lifecycleLog;
        assert lifecycleLog.contains("C:onActive") : "missing C:onActive, log=" + lifecycleLog;

        channel.close();
        Thread.sleep(100);

        assert lifecycleLog.contains("A:onClose") : "missing A:onClose, log=" + lifecycleLog;
        assert lifecycleLog.contains("B:onClose") : "missing B:onClose, log=" + lifecycleLog;
        assert lifecycleLog.contains("C:onClose") : "missing C:onClose, log=" + lifecycleLog;
    }

    // ==================== Error Handling Tests ====================

    /** Test: error in branch handler is handled within the branch */
    @Test
    public void routing_rcv_errorInBranch() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "main");
            builder.branch("main", branch -> {
                branch.addLast("layer1", doNextHandler("L1", decoderLog, decoderErr), doNextHandler("L1", encoderLog, encoderErr));
                branch.addLast("layer2", doThrowHandler("L2", decoderLog, decoderErr), doNextHandler("L2", encoderLog, encoderErr));
                branch.addLast("layer3", doNextHandler("L3", decoderLog, decoderErr), doNextHandler("L3", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> errors = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> {
            if (data.getError() != null) {
                errors.add(data.getError());
            }
        });

        channel.onReceive(42);

        // RCV: L1 -> L2(throw) -> L2 onError -> L3 onError (propagated)
        assert StringUtils.join(decoderLog.toArray(), ",").equals("L1DoNext,L2DoThrow") : "decoderLog=" + decoderLog;
        assert StringUtils.join(decoderErr.toArray(), ",").equals("L2ErrThrow,L3ErrNext") : "decoderErr=" + decoderErr;
    }

    // ==================== ProtoBuilder API Tests ====================

    /** Test: use ProtoBuilder fluent API to construct routing pipeline */
    @Test
    public void routing_builderAPI() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("pre", doNextHandler("Pre", decoderLog, decoderErr)).nextRoute("router", (ctx, rcvUp, rcvDown) -> {
            Integer data = rcvUp.peekMessage();
            return (data != null && data > 0) ? "positive" : "negative";
        }, r -> {
            r.branch("positive", branch -> {
                branch.addLast("pos", doNextHandler("Pos", decoderLog, decoderErr), doNextHandler("Pos", encoderLog, encoderErr));
            });
            r.branch("negative", branch -> {
                branch.addLast("neg", doNextHandler("Neg", decoderLog, decoderErr), doNextHandler("Neg", encoderLog, encoderErr));
            });
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        channel.onReceive(5);

        assert StringUtils.join(decoderLog.toArray(), ",").equals("PreDoNext,PosDoNext") : "decoderLog=" + decoderLog;
        assert received.size() == 1 && received.get(0).equals(5) : "received=" + received;
    }

    /** Test: use ProtoBuilder fluent API - route to negative branch */
    @Test
    public void routing_builderAPI_negativeBranch() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextRoute("router", (ctx, rcvUp, rcvDown) -> {
            Integer data = rcvUp.peekMessage();
            return (data != null && data > 0) ? "positive" : "negative";
        }, r -> {
            r.branch("positive", branch -> {
                branch.addLast("pos", doNextHandler("Pos", decoderLog, decoderErr), doNextHandler("Pos", encoderLog, encoderErr));
            });
            r.branch("negative", branch -> {
                branch.addLast("neg", doNextHandler("Neg", decoderLog, decoderErr), doNextHandler("Neg", encoderLog, encoderErr));
            });
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        channel.onReceive(-3);

        assert StringUtils.join(decoderLog.toArray(), ",").equals("NegDoNext") : "decoderLog=" + decoderLog;
        assert received.size() == 1 && received.get(0).equals(-3) : "received=" + received;
    }

    // ==================== Edge Cases ====================

    /** Test: routing returns unknown branch name -> exception */
    @Test
    public void routing_unknownBranch() throws Throwable {
        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "nonexistent");
            builder.branch("branchA", branch -> {
                branch.addLast("a", doNextHandler("A", new ArrayList<>(), new ArrayList<>()), doNextHandler("A", new ArrayList<>(), new ArrayList<>()));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> errors = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> {
            if (data.getError() != null) {
                errors.add(data.getError());
            }
        });

        channel.onReceive(42);

        // Should have an error due to unknown branch
        assert errors.size() == 1 : "errors=" + errors;
        assert errors.get(0) instanceof IllegalStateException : "error type=" + errors.get(0).getClass();
    }

    /** Test: duplicate branch name in builder -> exception */
    @Test(expected = IllegalArgumentException.class)
    public void routing_duplicateBranch() {
        ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "a");
        builder.branch("a", branch -> {
        });
        builder.branch("a", branch -> {
        }); // should throw
    }

    /** Test: no branches registered -> exception */
    @Test(expected = IOException.class)
    public void routing_noBranches() throws Throwable {
        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "a");
            builder.build(ctx); // should throw - no branches
        };

        NetManager neta = new NetManager();
        neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());
    }

    // ==================== SND Path Tests ====================

    /** Test: SND path through the routed branch */
    @Test
    public void routing_snd_throughBranch() throws Throwable {
        List<String> decoderLog = new ArrayList<>();
        List<String> decoderErr = new ArrayList<>();
        List<String> encoderLog = new ArrayList<>();
        List<String> encoderErr = new ArrayList<>();

        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "main");
            builder.branch("main", branch -> {
                branch.addLast("handler", doNextHandler("BR", decoderLog, decoderErr), doNextHandler("BR", encoderLog, encoderErr));
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        // First establish the route via RCV
        channel.onReceive(42);
        decoderLog.clear();
        encoderLog.clear();

        // Now test explicit SND path
        channel.sendData(99);
        Thread.sleep(100);

        // SND should go through branch encoder
        assert encoderLog.contains("BRDoNext") : "encoderLog=" + encoderLog;
    }

    // ==================== Data Transformation Tests ====================

    /** Test: branch handler transforms data */
    @Test
    public void routing_rcv_dataTransformation() throws Throwable {
        ProtoInitializer initializer = ctx -> {
            ProtoRoutingDuplexer.Builder<Integer> builder = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> "doubler");
            builder.branch("doubler", branch -> {
                // Handler that doubles the value
                branch.addLast("double", new ProtoHandler<Integer, Integer>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                        while (src.hasMore()) {
                            dst.offerMessage(src.takeMessage() * 2);
                        }
                        return ProtoStatus.Next;
                    }

                    @Override
                    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                        return ProtoStatus.Next;
                    }
                }, new ProtoHandler<Integer, Integer>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                        dst.offerMessage(src.takeMessage(src.queueSize()));
                        return ProtoStatus.Next;
                    }

                    @Override
                    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                        return ProtoStatus.Next;
                    }
                });
            });
            ctx.addLast("router", builder.build(ctx));
        };

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> received = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, data -> received.add(data.getData()));

        channel.onReceive(21);

        assert received.size() == 1 : "received=" + received;
        assert received.get(0).equals(42) : "expected 42, got " + received.get(0);
    }

    // ==================== Helper Classes ====================

    /** ProtoDuplexer that tracks lifecycle events */
    private static class LifecycleTracker implements ProtoDuplexer<Object, Object, Object, Object> {
        private final String       name;
        private final List<String> log;

        LifecycleTracker(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onInit(ProtoContext context) {
            log.add(name + ":onInit");
        }

        @Override
        public void onActive(ProtoContext context) {
            log.add(name + ":onActive");
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Object> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<Object> sndDown) {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage(rcvUp.queueSize()));
            } else {
                sndDown.offerMessage(sndUp.takeMessage(sndUp.queueSize()));
            }
            return ProtoStatus.Next;
        }

        @Override
        public void onClose(ProtoContext context) {
            log.add(name + ":onClose");
        }
    }
}
