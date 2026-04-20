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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoSndContext;
/**
 * Concrete datagram send task used by plain UDP channels.
 * <p>The transport strategy is straightforward:
 * <ul>
 *   <li>Server-side logical channels share one unconnected socket, so sending uses
 *       {@link DatagramChannel#send(ByteBuffer, SocketAddress)} with the logical channel's remote address.</li>
 *   <li>Client-side channels use a socket already connected to a single peer, so sending uses
 *       {@link DatagramChannel#write(ByteBuffer)}.</li>
 * </ul>
 * <p>Queue management, retry handling, and exception mapping remain centralized in
 * {@link AbstractUdpWriteTask}; this class only supplies the UDP-specific send adaptation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see AbstractUdpWriteTask
 * @see net.hasor.neta.channel.SoSndContext
 */
public class UdpWriteTask extends AbstractUdpWriteTask {
    private final DatagramChannel udpChannel;

    /**
     * Create a plain UDP write task.
     * @param netChannel the framework channel
     * @param channel the underlying DatagramChannel
     * @param wContext the send context
     * @param context the runtime context service
     */
    public UdpWriteTask(NetChannel netChannel, DatagramChannel channel, SoSndContext wContext, SoContextService context) {
        super(netChannel, wContext, context);
        this.udpChannel = channel;
    }

    /**
     * Determine whether the underlying UDP channel is still usable.
     * @return true if it is open
     */
    @Override
    protected boolean isChannelOpen() {
        return this.udpChannel.isOpen();
    }

    /**
     * Perform one UDP send.
     * @param data the payload to send
     * @return the number of bytes written this time
     * @throws IOException if an I/O error occurs during sending
     */
    @Override
    protected int doSend(byte[] data) throws IOException {
        SocketAddress target = this.getNetChannel().isServer() ? this.getNetChannel().getRemoteAddr() : null;
        if (target != null) {
            return this.udpChannel.send(ByteBuffer.wrap(data), target);
        } else {
            return this.udpChannel.write(ByteBuffer.wrap(data));
        }
    }
}