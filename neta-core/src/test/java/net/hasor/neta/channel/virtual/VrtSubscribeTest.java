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
package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoStatus;
import org.junit.Test;

import java.util.ArrayList;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class VrtSubscribeTest {
    @Test
    public void direct() throws Throwable {
        ProtoInitializer initializer = ctx -> ProtoHelper.standard().build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> event = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            event.add(data.getData());
        });

        channel.sendData("Hello Vrt");
        assert event.get(0).equals("Hello Vrt");

        neta.shutdown();
    }

    @Test
    public void convert() throws Throwable {
        ProtoInitializer initializer = ctx -> ProtoHelper.object().nextEncoder((context, src, dst) -> {
            dst.offerMessage("Data: " + src.takeMessage());
            return ProtoStatus.Next;
        }).build();

        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, new VrtSoConfig());

        ArrayList<Object> event = new ArrayList<>();
        channel.subscribe(PlayLoad::isOutbound, data -> {
            event.add(data.getData());
        });

        channel.sendData("Hello Vrt");
        assert event.get(0).equals("Data: Hello Vrt");

        neta.shutdown();
    }
}