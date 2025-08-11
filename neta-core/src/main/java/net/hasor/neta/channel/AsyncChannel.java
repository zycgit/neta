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
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous channel interface for network communication.
 * Represents a bidirectional communication channel that can perform operations asynchronously.
 * Extends Closeable to ensure proper resource cleanup.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
public interface AsyncChannel extends Closeable {

    /** return socket config. */
    SoConfig getSoConfig();

    /**
     * Gets the unique identifier of this channel.
     * @return The channel ID as a long value
     */
    long getChannelID();

    /**
     * Gets the local address to which this channel is bound.
     * @return The local SocketAddress
     * @throws IOException If an I/O error occurs
     */
    SocketAddress getLocalAddress() throws IOException;

    /**
     * Gets the remote address to which this channel is connected.
     * @return The remote SocketAddress
     * @throws IOException If an I/O error occurs
     */
    SocketAddress getRemoteAddress() throws IOException;

    /**
     * Gets the target object associated with this channel.
     * @return The target object
     */
    Object getTarget();

    boolean usingSndSwapBuffer();

    /**
     * Checks if this channel is open.
     * @return true if the channel is open, false otherwise
     */
    boolean isOpen();

    /**
     * Checks if this channel supports shutting down the input side.
     * @return true if the channel supports shutting down the input side, false otherwise
     */
    boolean supportShutdownInput();

    /**
     * Shuts down the input side of this channel.
     * @throws IOException If an I/O error occurs
     */
    void shutdownInput() throws IOException;

    /**
     * Checks if this channel supports shutting down the output side.
     * @return true if the channel supports shutting down the output side, false otherwise
     */
    boolean supportShutdownOutput();

    /**
     * Shuts down the output side of this channel.
     * @throws IOException If an I/O error occurs
     */
    void shutdownOutput() throws IOException;

    /**
     * Closes this channel.
     * @throws IOException If an I/O error occurs
     */
    @Override
    void close() throws IOException;

    /**
     * Reads data from this channel into the given buffer.
     * @param dst The destination buffer
     * @param attachment An attachment object that will be passed to the completion handler
     * @param handler The completion handler
     * @param <A> The type of the attachment object
     */
    <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler);

    /**
     * Reads data from this channel into the given buffer with a timeout.
     * @param dst The destination buffer
     * @param timeout The maximum time to wait for the read operation to complete
     * @param unit The time unit of the timeout argument
     * @param attachment An attachment object that will be passed to the completion handler
     * @param handler The completion handler
     * @param <A> The type of the attachment object
     */
    <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler);

    /**
     * Writes data from the given buffer to this channel.
     * @param src The source buffer
     * @param attachment An attachment object that will be passed to the completion handler
     * @param handler The completion handler
     * @param <A> The type of the attachment object
     */
    <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler);

    /**
     * Writes data from the given buffer to this channel with a timeout.
     * @param src The source buffer
     * @param timeout The maximum time to wait for the write operation to complete
     * @param unit The time unit of the timeout argument
     * @param attachment An attachment object that will be passed to the completion handler
     * @param handler The completion handler
     * @param <A> The type of the attachment object
     */
    <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler);

    /**
     * Connects this channel to the given remote address.
     * @param remote The remote address to connect to
     * @param attachment An attachment object that will be passed to the completion handler
     * @param handler The completion handler
     * @param <A> The type of the attachment object
     * @throws IOException If an I/O error occurs
     */
    <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) throws IOException;
}