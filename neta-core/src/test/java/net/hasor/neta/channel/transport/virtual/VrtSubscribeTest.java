/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.virtual;

import java.util.ArrayList;

import org.junit.Test;

import net.hasor.neta.channel.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class VrtSubscribeTest {
    @Test
    public void direct() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.standard().build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> event = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> {
            event.add(data.getData());
        });

        channel.sendData("Hello Vrt");
        assert event.get(0).equals("Hello Vrt");

        neta.shutdown();
    }

    @Test
    public void convert() throws Throwable {
        ProtoInitializer initializer = ProtoHelper.object().nextEncoder((context, src, dst) -> {
            dst.offerMessage("Data: " + src.takeMessage());
            return ProtoStatus.Next;
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> event = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, data -> {
            event.add(data.getData());
        });

        channel.sendData("Hello Vrt");
        assert event.get(0).equals("Data: Hello Vrt");

        neta.shutdown();
    }
}
