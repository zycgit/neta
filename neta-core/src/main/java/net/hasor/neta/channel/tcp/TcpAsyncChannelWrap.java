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
import net.hasor.neta.channel.AsyncChannelWrap;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.TimeUnit;

public class TcpAsyncChannelWrap implements AsyncChannelWrap {
    private final AsynchronousSocketChannel channel;

    public TcpAsyncChannelWrap(AsynchronousSocketChannel channel) {
        this.channel = channel;
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
    public NetworkChannel getTargetChannel() {
        return this.channel;
    }

    //

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

    //

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

    //

    @Override
    public NetworkChannel bind(SocketAddress local) throws IOException {
        throw new UnsupportedOperationException("TCP Unsupported.");
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        this.channel.connect(remote, attachment, handler);
    }
}