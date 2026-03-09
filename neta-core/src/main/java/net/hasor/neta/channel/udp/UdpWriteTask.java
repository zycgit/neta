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
package net.hasor.neta.channel.udp;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoSndContext;

/**
 * Concrete datagram sender for plain UDP channels.
 * <p>The transport decision is simple:
 * <ul>
 *   <li>server-side logical channels share one unconnected socket, so sending uses
 *       {@link DatagramChannel#send(ByteBuffer, SocketAddress)} with the logical
 *       channel's remote address;</li>
 *   <li>client-side channels use a socket already connected to one peer, so sending
 *       uses {@link DatagramChannel#write(ByteBuffer)}.</li>
 * </ul>
 * <p>All queue management, retry handling, and error mapping remain in
 * {@link AbstractUdpWriteTask}; this class only supplies the plain UDP transport-specific
 * send primitive.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see AbstractUdpWriteTask
 * @see net.hasor.neta.channel.SoSndContext
 */
public class UdpWriteTask extends AbstractUdpWriteTask {
    private final DatagramChannel udpChannel;

    public UdpWriteTask(NetChannel netChannel, DatagramChannel channel, SoSndContext wContext, SoContextService context) {
        super(netChannel, wContext, context);
        this.udpChannel = channel;
    }

    @Override
    protected boolean isChannelOpen() {
        return this.udpChannel.isOpen();
    }

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