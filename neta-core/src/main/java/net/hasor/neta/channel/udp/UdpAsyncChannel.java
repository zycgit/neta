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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;

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
 * @see java.nio.channels.DatagramChannel
 * @see java.util.concurrent.ExecutorService
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class UdpAsyncChannel implements AsyncChannel {
    protected final InetSocketAddress remoteAddr;
    protected final long              channelID;
    protected final DatagramChannel   channel;
    protected final SoConfig          options;

    UdpAsyncChannel(long channelID, InetSocketAddress remoteAddr, DatagramChannel channel, SoConfig options) {
        this.channelID = channelID;
        this.remoteAddr = remoteAddr;
        this.channel = channel;
        this.options = options;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.options;
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return this.channel.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return this.remoteAddr;
    }

    @Override
    public DatagramChannel getTarget() {
        return this.channel;
    }

    //

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public boolean supportShutdownInput() {
        return false;
    }

    @Override
    public void shutdownInput() throws IOException {
        throw new UnsupportedOperationException("UDP Unsupported.");
    }

    @Override
    public boolean supportShutdownOutput() {
        return false;
    }

    @Override
    public void shutdownOutput() throws IOException {
        throw new UnsupportedOperationException("UDP Unsupported.");
    }

    @Override
    public void close() throws IOException {
        //
    }

    @Override
    public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
        //
    }

    @Override
    public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        //
    }

    @Override
    public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) throws IOException {
        throw new UnsupportedOperationException("UDP Unsupported.");
    }
}
