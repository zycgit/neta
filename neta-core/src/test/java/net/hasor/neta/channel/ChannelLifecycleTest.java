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
import net.hasor.cobble.concurrent.future.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;

/**
 * Tests for channel attributes, context, flash, subscribe, and lifecycle management.
 * Uses VrtChannel for in-memory testing without network I/O.
 * @author test
 */
public class ChannelLifecycleTest extends AbstractStackTest {

    // --- Channel attribute tests ---

    @Test
    public void setAttribute_getAttribute() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.setAttribute("key1", "value1");
        channel.setAttribute("key2", 42);

        assert "value1".equals(channel.getAttribute("key1"));
        assert (Integer) channel.getAttribute("key2") == 42;
        assert channel.getAttribute("nonexistent") == null;

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void setAttribute_overwrite() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.setAttribute("key", "first");
        assert "first".equals(channel.getAttribute("key"));

        channel.setAttribute("key", "second");
        assert "second".equals(channel.getAttribute("key"));

        channel.closeNow();
        neta.shutdown();
    }

    // --- Context (ProtoContext.context) tests ---

    @Test
    public void protoContext_contextAttachment() throws Throwable {
        AtomicReference<String> contextValue = new AtomicReference<>();

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onInit(String name, int poolSize, ProtoContext context) {
                context.context(String.class, "hello-context");
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                String val = context.context(String.class);
                contextValue.set(val);
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", handler)                                              //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(100);

        assert "hello-context".equals(contextValue.get());

        channel.closeNow();
        neta.shutdown();
    }

    // --- Flash (ProtoContext.flash) tests ---

    @Test
    public void protoContext_flashIsClearedBetweenMessages() throws Throwable {
        List<String> flashValues = Collections.synchronizedList(new ArrayList<>());

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                String existing = context.flash("testKey");
                flashValues.add(existing == null ? "null" : existing);
                context.flash("testKey", "was-set");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", handler)                                              //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        Thread.sleep(100);
        channel.receiveData(2);
        Thread.sleep(100);

        // flash should be cleared between onMessage calls. each call should see null.
        assert flashValues.size() == 2;
        assert "null".equals(flashValues.get(0));
        assert "null".equals(flashValues.get(1));

        channel.closeNow();
        neta.shutdown();
    }

    // --- Channel lifecycle: init/active/close callbacks ---

    @Test
    public void lifecycleCallbacks_initActiveClose() throws Throwable {
        List<String> lifecycle = Collections.synchronizedList(new ArrayList<>());

        ProtoHandler<Integer, Integer> handler = new ProtoHandler<Integer, Integer>() {
            @Override
            public void onInit(String name, int poolSize, ProtoContext context) {
                lifecycle.add("init");
            }

            @Override
            public void onActive(ProtoContext context) {
                lifecycle.add("active");
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                lifecycle.add("message");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
                lifecycle.add("close");
            }
        };

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", handler)                                              //
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        // after connect, init and active should have been called
        assert lifecycle.contains("init");
        assert lifecycle.contains("active");
        assert !lifecycle.contains("close");

        channel.receiveData(1);
        Thread.sleep(100);
        assert lifecycle.contains("message");

        channel.closeNow();
        Thread.sleep(100);
        assert lifecycle.contains("close");

        neta.shutdown();
    }

    // --- Channel close listener ---

    @Test
    public void onCloseListener_notified() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicBoolean closeCalled = new AtomicBoolean(false);
        channel.onClose(ch -> {
            closeCalled.set(true);
            closeLatch.countDown();
        });

        assert !channel.isClose();
        channel.closeNow();

        boolean awaited = closeLatch.await(2, TimeUnit.SECONDS);
        assert awaited;
        assert closeCalled.get();
        assert channel.isClose();

        neta.shutdown();
    }

    @Test
    public void virtualGracefulCloseCompletesLifecycleAsync() throws Throwable {
        AtomicBoolean lifecycleClosed = new AtomicBoolean();
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                        return ProtoStatus.Next;
                    }

                    @Override
                    public void onClose(ProtoContext context) {
                        lifecycleClosed.set(true);
                    }
                })//
                .build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        Future<NetChannel> closeFuture = channel.close();

        // Graceful close runs on the pipeline executor for all transports (uniform close path).
        closeFuture.get();

        assert closeFuture.isDone();
        assert channel.isClose();
        assert lifecycleClosed.get();
        assert neta.getContext().findChannel(channel.getChannelId()) == null;

        neta.shutdown();
    }

    // --- Channel identity ---

    @Test
    public void channelId_unique() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch1 = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());
        VrtChannel ch2 = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), initializer, VrtSoConfig.asServer());

        assert ch1.getChannelId() != ch2.getChannelId();

        ch1.closeNow();
        ch2.closeNow();
        neta.shutdown();
    }

    @Test
    public void channelAddresses() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(42), initializer, VrtSoConfig.asServer());

        assert ch.getLocalAddr() != null;
        assert ch.getRemoteAddr() != null;

        ch.closeNow();
        neta.shutdown();
    }

    // --- Server mode detection ---

    @Test
    public void serverMode() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, VrtSoConfig.asServer());

        assert ch.isServer();
        assert !ch.isClient();
        assert !ch.isListen();

        ch.closeNow();
        neta.shutdown();
    }

    // --- Subscribe on channel filters by channelId ---

    @Test
    public void channelSubscribe_filtersById() throws Throwable {
        List<Object> ch1Messages = Collections.synchronizedList(new ArrayList<>());
        List<Object> ch2Messages = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch1 = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());
        VrtChannel ch2 = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), init, VrtSoConfig.asServer());

        ch1.subscribe(playLoad -> ch1Messages.add(playLoad.getData()));
        ch2.subscribe(playLoad -> ch2Messages.add(playLoad.getData()));

        ch1.receiveData(100);
        ch2.receiveData(200);
        Thread.sleep(200);

        // ch1 should only get message from ch1
        assert ch1Messages.size() == 1 : "ch1Messages size=" + ch1Messages.size();
        assert (Integer) ch1Messages.get(0) == 100;

        assert ch2Messages.size() == 1 : "ch2Messages size=" + ch2Messages.size();
        assert (Integer) ch2Messages.get(0) == 200;

        ch1.closeNow();
        ch2.closeNow();
        neta.shutdown();
    }

    // --- Subscribe with predicate filter ---

    @Test
    public void channelSubscribe_withPredicate() throws Throwable {
        List<Object> filtered = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        // only accept inbound messages
        ch.subscribe(PlayLoad::isInbound, playLoad -> filtered.add(playLoad.getData()));

        ch.receiveData(42);
        Thread.sleep(200);

        assert filtered.size() == 1;
        assert (Integer) filtered.get(0) == 42;

        ch.closeNow();
        neta.shutdown();
    }

    // --- Unsubscribe ---

    @Test
    public void unsubscribe_stopsReceiving() throws Throwable {
        List<Object> received = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        SubscribeHolder holder = ch.subscribe(playLoad -> received.add(playLoad.getData()));

        ch.receiveData(1);
        Thread.sleep(100);
        assert received.size() == 1;

        holder.unSubscribe();

        ch.receiveData(2);
        Thread.sleep(100);
        // should not have received the second message
        assert received.size() == 1;

        ch.closeNow();
        neta.shutdown();
    }

    // --- Global subscribe via NetManager.getContext() ---

    @Test
    public void globalSubscribe_receivesAllChannels() throws Throwable {
        List<Long> channelIds = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        neta.getContext().subscribe(p -> true, p -> channelIds.add(p.getSource().getChannelId()));

        VrtChannel ch1 = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());
        VrtChannel ch2 = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), init, VrtSoConfig.asServer());

        ch1.receiveData(1);
        ch2.receiveData(2);
        Thread.sleep(200);

        // global subscriber got messages from both channels
        assert channelIds.size() >= 2;
        assert channelIds.contains(ch1.getChannelId());
        assert channelIds.contains(ch2.getChannelId());

        ch1.closeNow();
        ch2.closeNow();
        neta.shutdown();
    }
}
