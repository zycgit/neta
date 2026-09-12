/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;

public class NetChannelPipelineScopeTest extends AbstractStackTest {
    @Test
    public void staticChecks_followCurrentChannelScope() throws Throwable {
        AtomicBoolean inHandlerAny = new AtomicBoolean(false);
        AtomicBoolean inHandlerSelf = new AtomicBoolean(false);
        AtomicBoolean inSubscribeAny = new AtomicBoolean(false);
        AtomicBoolean inSubscribeSelf = new AtomicBoolean(false);
        CountDownLatch subscribeLatch = new CountDownLatch(1);

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                NetChannel channel = (NetChannel) context.getChannel();
                inHandlerAny.set(NetChannel.isCurrentThreadInPipeline());
                inHandlerSelf.set(NetChannel.isCurrentThreadInPipeline(channel));
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(11), initializer, VrtSoConfig.asServer());
        channel.subscribe(PlayLoad::isInbound, data -> {
            inSubscribeAny.set(NetChannel.isCurrentThreadInPipeline());
            inSubscribeSelf.set(NetChannel.isCurrentThreadInPipeline(channel));
            subscribeLatch.countDown();
        });

        channel.receiveData(1);
        assert subscribeLatch.await(2, TimeUnit.SECONDS);
        assert inHandlerAny.get();
        assert inHandlerSelf.get();
        assert !inSubscribeAny.get();
        assert !inSubscribeSelf.get();

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void sameChannelSendInsideAsyncSubscribe_isAllowed() throws Throwable {
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        AtomicReference<Throwable> sendCause = new AtomicReference<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(111), initializer, VrtSoConfig.asServer());
        channel.subscribe(PlayLoad::isInbound, data -> {
            Future<?> future = channel.sendData(99);
            sendCause.set(future.getCause());
            subscribeLatch.countDown();
        });

        channel.receiveData(1);
        assert subscribeLatch.await(2, TimeUnit.SECONDS);
        assert sendCause.get() == null;

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void sameChannelSendInsideSyncSubscribe_isRejected() throws Throwable {
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        AtomicReference<Throwable> sendCause = new AtomicReference<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1111), initializer, VrtSoConfig.asServer());
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> {
            Future<?> future = channel.sendData(99);
            sendCause.set(future.getCause());
            subscribeLatch.countDown();
        });

        channel.receiveData(1);
        assert subscribeLatch.await(2, TimeUnit.SECONDS);
        assert sendCause.get() instanceof IllegalStateException;
        assert sendCause.get().getMessage().contains("this channel's pipeline call chain");

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void sameChannelSendInsideHandler_isRejected() throws Throwable {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicReference<Throwable> sendCause = new AtomicReference<>();

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                NetChannel channel = (NetChannel) context.getChannel();
                Future<?> future = channel.sendData(99);
                sendCause.set(future.getCause());
                src.takeMessage(src.queueSize());
                handlerLatch.countDown();
                return ProtoStatus.Stop;
            }
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(12), initializer, VrtSoConfig.asServer());

        channel.receiveData(1);
        assert handlerLatch.await(2, TimeUnit.SECONDS);
        assert sendCause.get() instanceof IllegalStateException;
        assert sendCause.get().getMessage().contains("this channel's pipeline call chain");

        channel.closeNow();
        neta.shutdown();
    }

    @Test
    public void otherChannelSendInsideHandler_isAllowed() throws Throwable {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        CountDownLatch receiveLatch = new CountDownLatch(1);
        AtomicBoolean anyInHandler = new AtomicBoolean(false);
        AtomicBoolean selfInHandler = new AtomicBoolean(false);
        AtomicBoolean otherBeforeSend = new AtomicBoolean(true);
        AtomicReference<Throwable> sendCause = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();

        NetManager neta = new NetManager();
        VrtChannel target = (VrtChannel) neta.connectSync(new VrtSocketAddress(21), ProtoHelper.standard().build(), VrtSoConfig.asDefault());
        VrtChannel targetPeer = (VrtChannel) neta.connectSync(new VrtSocketAddress(21), ProtoHelper.standard().build(), VrtSoConfig.asDefault());
        targetPeer.subscribe(PlayLoad::isInbound, data -> {
            received.set((String) data.getData());
            receiveLatch.countDown();
        });

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(target, targetPeer, VrtTransfer.direct());

        ProtoInitializer initializer = ProtoHelper.typed(Integer.class, Integer.class).nextDecoder("L1", new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                NetChannel channel = (NetChannel) context.getChannel();
                anyInHandler.set(NetChannel.isCurrentThreadInPipeline());
                selfInHandler.set(NetChannel.isCurrentThreadInPipeline(channel));
                otherBeforeSend.set(NetChannel.isCurrentThreadInPipeline(target));

                Future<?> future = target.sendData("from-other-channel");
                sendCause.set(future.getCause());

                src.takeMessage(src.queueSize());
                handlerLatch.countDown();
                return ProtoStatus.Stop;
            }
        }).build();

        VrtChannel source = (VrtChannel) neta.connectSync(new VrtSocketAddress(22), initializer, VrtSoConfig.asServer());
        source.receiveData(1);

        assert handlerLatch.await(2, TimeUnit.SECONDS);
        assert anyInHandler.get();
        assert selfInHandler.get();
        assert !otherBeforeSend.get();
        assert sendCause.get() == null;
        assert receiveLatch.await(2, TimeUnit.SECONDS);
        assert "from-other-channel".equals(received.get());

        source.closeNow();
        target.closeNow();
        targetPeer.closeNow();
        neta.shutdown();
    }
}
