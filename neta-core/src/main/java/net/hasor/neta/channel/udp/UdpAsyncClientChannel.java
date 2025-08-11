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
import net.hasor.neta.channel.AsyncChannel;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.SoReadTimeoutException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;

/**
 * An implementation of the {@link AsyncChannel} interface for UDP communication.
 * This class provides asynchronous read and write operations over a UDP channel,
 * using a non-blocking {@link DatagramChannel} and a dedicated I/O executor service.
 * <p>
 * The UdpAsyncChannel supports reading data into a {@link ByteBuffer} with or without
 * a specified timeout. It does not support writing data, as well as connecting to a remote
 * address, which are unsupported operations for this type of channel.
 * <p>
 * Upon creation, the channel is registered with a selector for reading, and all I/O operations
 * are performed by the provided I/O executor service.
 *
 * @see DatagramChannel
 * @see ExecutorService
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class UdpAsyncClientChannel extends UdpAsyncChannel {
    private final InetSocketAddress remoteAddr;
    private final ExecutorService   ioExecutor;
    private       Selector          selector;
    private final SoConfig          options;

    UdpAsyncClientChannel(long channelID, InetSocketAddress remoteAddr, DatagramChannel channel, ExecutorService ioExecutor, SoConfig options) {
        super(channelID, remoteAddr, channel, options);
        this.remoteAddr = remoteAddr;
        this.ioExecutor = ioExecutor;
        this.options = options;
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) throws IOException {
        this.selector = Selector.open();
        this.channel.connect(remote);
        this.channel.register(this.selector, SelectionKey.OP_READ);
        this.channel.configureBlocking(false);

        handler.completed(null, attachment);

        int rcvPacketSize = this.options.getSoRcvBuf();
        if (options instanceof UdpOptions) {
            Integer packetSize = ((UdpOptions) this.options).getRcvPacketSize();
            if (packetSize != null) {
                rcvPacketSize = packetSize;
            }
        }

        int finalRcvPacketSize = rcvPacketSize;
        this.ioExecutor.execute(() -> this.receiveData(finalRcvPacketSize));
    }

    private void receiveData(int finalRcvPacketSize) {
        //
    }

    private <A> void asyncReadToBuffer(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler, long timeoutMs) {
        try {
            long startTime = System.currentTimeMillis();
            if (timeoutMs > 0) {
                long remainingTimeout = timeoutMs - (System.currentTimeMillis() - startTime);
                if (remainingTimeout <= 0) {
                    handler.failed(new SoReadTimeoutException("socket read timeout"), attachment);
                    return;
                }
                this.selector.select(remainingTimeout);
            } else {
                this.selector.select();
            }

            int pos = dst.position();
            SocketAddress remoteADdr = this.channel.receive(dst);
            int bytesRead = dst.position() - pos;
            if (bytesRead >= 0) {
                handler.completed(bytesRead, attachment);
            } else {
                handler.failed(new IOException("channel closed"), attachment);
            }
        } catch (IOException e) {
            handler.failed(e, attachment);
        }
    }
}
