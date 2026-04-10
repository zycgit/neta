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
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import net.hasor.neta.channel.*;

/**
 * Application-facing UDP {@link NetChannel} implementation.
 * <p>This wrapper binds one logical remote-address view to the framework pipeline.
 * In plain UDP server mode, multiple {@code UdpChannel} instances can share the same underlying
 * datagram socket.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class UdpChannel extends NetChannel {
    /**
     * Create a standard framework-level UDP channel.
     * @param channelId the channel ID
     * @param monitor the monitor
     * @param forListen the source listener
     * @param initializer the protocol initializer
     * @param asyncChannel the underlying UDP asynchronous channel
     * @param context the runtime context service
     * @throws IOException if an I/O error occurs during creation
     */
    protected UdpChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, UdpAsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
    }

    /**
     * Create a UDP channel that allows subclasses to inject a different AsyncChannel implementation.
     * @param channelId the channel ID
     * @param monitor the monitor
     * @param forListen the source listener
     * @param initializer the protocol initializer
     * @param asyncChannel the underlying asynchronous channel
     * @param context the runtime context service
     * @throws IOException if an I/O error occurs during creation
     */
    protected UdpChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
    }

    /**
     * Return the monitor used by the current channel.
     * @return the monitor object
     */
    protected NetMonitor getNetMonitor() {
        return this.monitor;
    }
}