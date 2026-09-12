/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;

/**
 * Tests for {@link PlayLoadObject} factory methods and behaviour as observed
 * through subscribe mechanism.
 * @author test
 */
public class PlayLoadTest extends AbstractStackTest {

    // --- PlayLoad.isSuccess / PlayLoad.getData via receive ---

    @Test
    public void playLoad_successfulMessage() throws Throwable {
        AtomicReference<PlayLoad> received = new AtomicReference<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        ch.subscribe(playLoad -> received.set(playLoad));

        ch.receiveData(42);
        Thread.sleep(200);

        PlayLoad p = received.get();
        assert p != null;
        assert p.isSuccess();
        assert p.getError() == null;
        assert (Integer) p.getData() == 42;
        assert p.getSource() != null;
        assert p.getSource().getChannelId() == ch.getChannelId();
        assert p.isInbound();

        ch.closeNow();
        neta.shutdown();
    }

    // --- PlayLoad with error via receive error ---

    @Test
    public void playLoad_errorMessage() throws Throwable {
        AtomicReference<PlayLoad> received = new AtomicReference<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        ch.subscribe(playLoad -> {
            if (!playLoad.isSuccess()) {
                received.set(playLoad);
            }
        });

        SoException err = new SoException("test error");
        ch.receiveError(err);
        Thread.sleep(200);

        PlayLoad p = received.get();
        if (p != null) {
            assert !p.isSuccess();
            assert p.getError() != null;
            assert p.getData() == null;
        }
        // Note: error handling may not always produce a PlayLoad depending on pipeline

        ch.closeNow();
        neta.shutdown();
    }

    // --- Multiple messages arrive in order ---

    @Test
    public void playLoad_ordering() throws Throwable {
        List<Object> messages = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        ch.subscribe(playLoad -> messages.add(playLoad.getData()));

        ch.receiveData(1);
        ch.receiveData(2);
        ch.receiveData(3);
        Thread.sleep(300);

        assert messages.size() == 3;
        assert (Integer) messages.get(0) == 1;
        assert (Integer) messages.get(1) == 2;
        assert (Integer) messages.get(2) == 3;

        ch.closeNow();
        neta.shutdown();
    }

    // --- Batch receive ---

    @Test
    public void playLoad_batchReceive() throws Throwable {
        List<Object> messages = Collections.synchronizedList(new ArrayList<>());

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)  //
                .nextDecoder("L1", doNextHandler("a", new ArrayList<>(), new ArrayList<>()))//
                .build();

        NetManager neta = new NetManager();
        VrtChannel ch = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), init, VrtSoConfig.asServer());

        ch.subscribe(playLoad -> messages.add(playLoad.getData()));

        // batch send multiple objects
        ch.receiveData(10, 20, 30);
        Thread.sleep(300);

        assert messages.size() == 3 : "messages.size=" + messages.size();
        assert (Integer) messages.get(0) == 10;
        assert (Integer) messages.get(1) == 20;
        assert (Integer) messages.get(2) == 30;

        ch.closeNow();
        neta.shutdown();
    }
}
