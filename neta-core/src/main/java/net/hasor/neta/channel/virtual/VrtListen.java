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
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.SoContextService;

/**
 * The active listening endpoint of a virtual transport.
 * <p>Extends {@link net.hasor.neta.channel.NetListen} with a reference to the
 * {@link VrtTransfer} that handles in-JVM data routing between the client and
 * server sides of a virtual channel pair.
 * <p>A {@code VrtListen} is created by
 * {@link VrtAsyncServerChannel#bind(net.hasor.neta.channel.ProtoInitializer)}
 * and initialized through the standard
 * {@link net.hasor.neta.channel.SoContextService#initChannel} path, which runs
 * the user-supplied {@link net.hasor.neta.channel.ProtoInitializer} to build
 * the server-side protocol stack.
 * <p>The embedded {@link VrtTransfer} is used by incoming
 * {@link VrtAsyncChannel} writes to deliver data directly into this pipeline
 * without any OS socket involvement.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see VrtAsyncServerChannel
 * @see VrtTransfer
 */
public class VrtListen extends NetListen {
    private final VrtTransfer transfer;

    VrtListen(long channelId, VrtSocketAddress listenAddr, VrtAsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig, VrtTransfer transfer) {
        super(channelId, listenAddr, listenAddr.getAddress(), channel, initializer, context, soConfig);
        this.transfer = transfer;
    }

    public VrtTransfer getTransfer() {
        return this.transfer;
    }
}