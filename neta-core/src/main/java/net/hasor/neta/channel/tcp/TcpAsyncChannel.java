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
package net.hasor.neta.channel.tcp;
import net.hasor.neta.channel.AsyncChannel;
import net.hasor.neta.channel.SoConfig;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.TimeUnit;

/**
 * TCP implementation of asynchronous client channel.
 * Provides TCP-specific implementation for establishing connections and performing I/O operations asynchronously.
 * Wraps Java NIO's AsynchronousSocketChannel for actual network operations.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncChannel implements AsyncChannel {
    private final AsynchronousSocketChannel channel;
    private final long                      channelID;
    private final SoConfig                  soConfig;

    TcpAsyncChannel(long channelId, AsynchronousSocketChannel channel, SoConfig soConfig) {
        this.channel = channel;
        this.channelID = channelId;
        this.soConfig = soConfig;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
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
        return this.channel.getRemoteAddress();
    }

    @Override
    public NetworkChannel getTarget() {
        return this.channel;
    }

    @Override
    public boolean usingSndSwapBuffer() {
        return true;
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public boolean supportShutdownInput() {
        return true;
    }

    @Override
    public void shutdownInput() throws IOException {
        this.channel.shutdownInput();
    }

    @Override
    public boolean supportShutdownOutput() {
        return true;
    }

    @Override
    public void shutdownOutput() throws IOException {
        this.channel.shutdownOutput();
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    @Override
    public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
        this.channel.read(dst, attachment, handler);
    }

    @Override
    public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        this.channel.read(dst, timeout, unit, attachment, handler);
    }

    @Override
    public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
        this.channel.write(src, attachment, handler);
    }

    @Override
    public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        this.channel.write(src, timeout, unit, attachment, handler);
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        this.channel.connect(remote, attachment, handler);
    }
}