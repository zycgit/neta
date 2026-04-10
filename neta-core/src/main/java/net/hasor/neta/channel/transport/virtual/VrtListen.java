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
package net.hasor.neta.channel.transport.virtual;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.SoContextService;

/**
 * Active listening endpoint for the virtual transport.
 * <p>This type extends {@link net.hasor.neta.channel.NetListen} and additionally holds a
 * reference to {@link VrtTransfer}, which handles client/server data routing between paired
 * virtual channels inside the same JVM.
 * <p>{@code VrtListen} is created by
 * {@link net.hasor.neta.channel.AsyncServerChannel#bind(net.hasor.neta.channel.ProtoInitializer)}
 * and initialized through the standard
 * {@link net.hasor.neta.channel.SoContextService#initChannel(net.hasor.neta.channel.SoChannel, boolean)}
 * path. That path runs the user-supplied {@link net.hasor.neta.channel.ProtoInitializer} to
 * build the server-side protocol stack.
 * <p>The embedded {@link VrtTransfer} is used by incoming {@link VrtAsyncChannel} write flows,
 * so data can be delivered directly into the pipeline associated with this listener without any
 * operating-system socket involvement.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see VrtAsyncServerChannel
 * @see VrtTransfer
 */
public class VrtListen extends NetListen {
    private final VrtTransfer transfer;

    /**
     * Create a virtual listen endpoint.
     * @param channelId the channel ID
     * @param listenAddr the listen address
     * @param channel the owning server channel
     * @param initializer the protocol initializer
     * @param context the runtime context service
     * @param soConfig the listen configuration
     * @param transfer the virtual transport object
     */
    VrtListen(long channelId, VrtSocketAddress listenAddr, VrtAsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig, VrtTransfer transfer) {
        super(channelId, listenAddr, listenAddr.getAddress(), channel, initializer, context, soConfig);
        this.transfer = transfer;
    }

    /**
     * Return the virtual transport object used by the current listener.
     * @return the virtual transport object
     */
    public VrtTransfer getTransfer() {
        return this.transfer;
    }
}