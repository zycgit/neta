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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * Tests that validate network-writable semantics during key lifecycle callbacks.
 * @author test
 */
public class LifecycleNetworkOpsTest {

    // -------------------------------------------------------------------------
    // Scene 1: onActive fires before first onMessage
    // -------------------------------------------------------------------------

    /**
     * In a multi-handler chain (typical for TCP where ByteBuf → Frame → Message),
     * ALL handlers receive lifecycle events in head→tail order, and the ordering
     * constraint (onActive &lt; first-onMessage &lt; onClose) must hold for every handler.
     */
    private static ProtoHandler<Integer, Integer> makeLifecycleHandler(String name, List<String> order, CountDownLatch closeLatch) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public void onActive(ProtoContext context) {
                order.add(name + ":onActive");
            }

            @Override
            public void onClose(ProtoContext context) {
                order.add(name + ":onClose");
                if ("C".equals(name)) {
                    closeLatch.countDown();
                }
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                order.add(name + ":onMessage");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };
    }

    /** Verifies that onActive is always called before the first onMessage. */
    @Test
    public void onActive_firesBeforeFirstMessage() throws Throwable {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onActive(ProtoContext context) {
                order.add("onActive");
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                order.add("onMessage");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("handler", handler).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        // onActive must already be recorded before any data is pushed
        assert order.size() == 1 : "Expected only onActive before first message, got: " + order;
        assert "onActive".equals(order.get(0)) : "First event must be onActive, got: " + order.get(0);

        channel.receiveData(1);
        Thread.sleep(100);

        assert order.size() == 2 : "Expected onActive + onMessage, got: " + order;
        assert "onMessage".equals(order.get(1)) : "Second event must be onMessage, got: " + order.get(1);

        channel.closeNow();
        neta.shutdown();
    }

    /**
     * Verifies onActive fires before ANY of multiple messages.
     * Even when messages arrive in rapid succession onActive was already completed.
     */
    @Test
    public void onActive_firesOnceThenMessages() throws Throwable {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onActive(ProtoContext context) {
                order.add("onActive");
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                // Take all available messages in one pass to ensure all three are processed.
                List<Integer> msgs = src.takeMessage(src.queueSize());
                for (Integer m : msgs) {
                    order.add("onMessage:" + m);
                }
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("handler", handler).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(10, 20, 30);
        Thread.sleep(100);

        // onActive must be first
        assert order.get(0).equals("onActive") : "onActive must be first: " + order;
        // all three messages must have been processed (order within messages may vary)
        assert order.contains("onMessage:10") : "must contain onMessage:10, got: " + order;
        assert order.contains("onMessage:20") : "must contain onMessage:20, got: " + order;
        assert order.contains("onMessage:30") : "must contain onMessage:30, got: " + order;
        // onActive must appear exactly once
        long activeCount = order.stream().filter("onActive"::equals).count();
        assert activeCount == 1 : "onActive must fire exactly once, got " + activeCount;

        channel.closeNow();
        neta.shutdown();
    }

    // -------------------------------------------------------------------------
    // Scene 2: SoBeforeCloseEvent on LOCAL close
    // -------------------------------------------------------------------------

    /**
     * Verifies that onActive can itself enqueue data for sending (e.g. server greeting / challenge).
     */
    @Test
    public void onActive_canSendGreeting() throws Throwable {
        AtomicBoolean greetingSent = new AtomicBoolean(false);
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<Object> outbound = Collections.synchronizedList(new ArrayList<>());

        // Register the global subscriber BEFORE connecting so we capture onActive's send.
        NetManager neta = new NetManager();
        neta.getContext().subscribe(p -> !p.isInbound(), p -> outbound.add(p.getData()));

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onActive(ProtoContext context) {
                // Some protocols send a greeting/preface on connect
                // (HTTP/2 client preface, Redis inline inline "HELLO", SCTP capabilities, etc.).
                // The handler writes directly to sndDown; this is the correct API inside a handler.
                // We record that the call did not throw.
                try {
                    // Use context.sendData — this submits an independent SND task.
                    // In VrtChannel the task runs synchronously via the same SoContextService thread,
                    // so the outbound event fires before connectSync returns.
                    context.sendData(999);
                    greetingSent.set(true);
                } catch (Throwable e) {
                    sendError.set(e);
                }
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("handler", handler).build();

        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        Thread.sleep(100);

        assert greetingSent.get() : "onActive should have been called without error";
        assert sendError.get() == null : "sendData() in onActive must not throw: " + sendError.get();
        // The greeting (999) should have been submitted to the SND pipeline and reach the outbound subscriber
        assert outbound.contains(999) : "Greeting 999 should appear in outbound, got: " + outbound;

        channel.closeNow();
        neta.shutdown();
    }

    /**
     * When the local side calls close(), a {@link SoCloseEvent} is fired through the
     * SND pipeline (tail → head). The handler can call sendData() fire-and-forget; the
     * framework drains the queue again before performing the actual close.
     */
    @Test
    public void beforeCloseEvent_localClose_isFired() throws Throwable {
        AtomicBoolean eventReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        CountDownLatch closeLatch = new CountDownLatch(1);

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) throws Throwable {
                if (event.getData() instanceof SoCloseEvent) {
                    eventReceived.set(true);
                    try {
                        context.sendData(-1); // fire-and-forget farewell
                    } catch (Throwable e) {
                        sendError.set(e);
                    }
                }
                return true;
            }

            @Override
            public void onClose(ProtoContext context) {
                closeLatch.countDown();
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };
        ProtoInitializer initializer = ctx -> ctx.addLast("handler", handler, handler);

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(50);

        channel.close(); // local close — safe-close path through SoCloseTask

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);
        assert closed : "onClose must be called within 2 seconds";
        assert eventReceived.get() : "SoBeforeCloseEvent must be fired on local close";
        assert sendError.get() == null : "sendData() in SoBeforeCloseEvent handler must not throw: " + sendError.get();

        neta.shutdown();
    }

    // -------------------------------------------------------------------------
    // Lifecycle order: onActive → onMessage → onClose
    // -------------------------------------------------------------------------

    /**
     * When the remote side closes the connection, {@link SoCloseEvent} is NOT fired.
     */
    @Test
    public void beforeCloseEvent_remoteClose_isNotFired() throws Throwable {
        AtomicBoolean eventReceived = new AtomicBoolean(false);
        CountDownLatch closeLatch = new CountDownLatch(1);

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getData() instanceof SoCloseEvent) {
                    eventReceived.set(true);
                }
                return true;
            }

            @Override
            public void onClose(ProtoContext context) {
                closeLatch.countDown();
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("handler", handler).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(30);

        channel.soContext.notifyChannelClose(channel.getChannelId(), true);

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);
        assert closed : "onClose must be called after remote close";
        assert !eventReceived.get() : "SoBeforeCloseEvent must NOT be fired on remote close";

        neta.shutdown();
    }

    // -------------------------------------------------------------------------
    // Multi-handler chain: each handler observes the same lifecycle ordering
    // -------------------------------------------------------------------------

    /**
     * Verifies lifecycle order: onActive → onMessage → onClose.
     */
    @Test
    public void lifecycle_order_onActive_onMessage_onClose() throws Throwable {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch closeLatch = new CountDownLatch(1);

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onActive(ProtoContext context) {
                order.add("onActive");
            }

            @Override
            public void onClose(ProtoContext context) {
                order.add("onClose");
                closeLatch.countDown();
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                order.add("onMessage");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("handler", handler).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(50);
        channel.closeNow();

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);
        assert closed : "onClose must be called";

        assert order.indexOf("onActive") < order.indexOf("onMessage") : "onActive must precede onMessage: " + order;
        assert order.indexOf("onMessage") < order.indexOf("onClose") : "onMessage must precede onClose: " + order;

        neta.shutdown();
    }

    @Test
    public void multiLayerChain_allHandlersRespectLifecycleOrder() throws Throwable {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch closeLatch = new CountDownLatch(1);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("A", makeLifecycleHandler("A", order, closeLatch)).nextDecoder("B", makeLifecycleHandler("B", order, closeLatch)).nextDecoder("C", makeLifecycleHandler("C", order, closeLatch)).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(50);
        channel.closeNow();

        boolean closed = closeLatch.await(2, TimeUnit.SECONDS);
        assert closed;

        // For each handler: onActive < its first onMessage < its onDeactivate < its onClose
        for (String name : new String[] { "A", "B", "C" }) {
            int activeIdx = order.indexOf(name + ":onActive");
            int msgIdx = order.indexOf(name + ":onMessage");
            int closeIdx = order.indexOf(name + ":onClose");

            assert activeIdx >= 0 : name + ":onActive not found in " + order;
            assert msgIdx >= 0 : name + ":onMessage not found in " + order;
            assert closeIdx >= 0 : name + ":onClose not found in " + order;
            assert activeIdx < msgIdx : name + ": onActive must precede onMessage: " + order;
            assert msgIdx < closeIdx : name + ": onMessage must precede onClose: " + order;
        }

        neta.shutdown();
    }
}
