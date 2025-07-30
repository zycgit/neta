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
package net.hasor.neta.channel;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.TimeUnit;

public interface AsyncChannelWrap {

    SocketAddress getLocalAddress() throws IOException;

    SocketAddress getRemoteAddress() throws IOException;

    NetworkChannel getTargetChannel();

    //

    boolean isOpen();

    boolean supportShutdownInput();

    void shutdownInput() throws IOException;

    boolean supportShutdownOutput();

    void shutdownOutput() throws IOException;

    void close() throws IOException;

    //

    <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler);

    <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler);

    <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler);

    <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler);

    //

    NetworkChannel bind(SocketAddress local) throws IOException;

    <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) throws IOException;
}