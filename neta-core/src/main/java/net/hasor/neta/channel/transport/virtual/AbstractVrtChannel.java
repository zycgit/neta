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
import java.io.IOException;
import net.hasor.neta.channel.*;
/**
 * Abstract base class for virtual channels.
 * <p>This type represents a channel that runs entirely in application memory and can move data
 * without relying on a real network stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class AbstractVrtChannel extends NetChannel {
    private final VrtMode vrtMode;

    /**
     * Create a virtual channel base instance.
     * @param channelId the channel ID
     * @param monitor the monitor
     * @param forListen the source listener
     * @param vrtMode the virtual channel mode
     * @param initializer the protocol initializer
     * @param asyncChannel the underlying asynchronous channel
     * @param context the runtime context service
     * @throws IOException if an I/O error occurs during creation
     */
    protected AbstractVrtChannel(long channelId, NetMonitor monitor, NetListen forListen, VrtMode vrtMode, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
        this.vrtMode = vrtMode;
    }

    /**
     * Determine whether the current virtual channel is in client mode.
     * @return true if it is a client channel
     */
    @Override
    public boolean isClient() {
        return this.vrtMode == VrtMode.Client;
    }

    /**
     * Determine whether the current virtual channel is in server mode.
     * @return true if it is a server channel
     */
    @Override
    public boolean isServer() {
        return this.vrtMode == VrtMode.Server;
    }
}