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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AsynchronousSocketChannel wrap
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-06
 */
class SoAsyncChannel implements Closeable {
    private final AsyncChannelWrap channel;
    //
    private final Integer          rTimeoutMs;
    private final Integer          wTimeoutMs;
    private final AtomicBoolean    shutdownInputSignal;
    private final AtomicBoolean    shutdownOutputSignal;
    private final ByteBufAllocator allocator;
    private final ByteBuffer       rcvSwapBuffer;
    //
    private       boolean          ignoreReadEofFlag;

    SoAsyncChannel(AsyncChannelWrap channel, ByteBufAllocator allocator, SoConfig soConfig) {
        this.channel = channel;
        this.rTimeoutMs = soConfig.getSoReadTimeoutMs();
        this.wTimeoutMs = soConfig.getSoWriteTimeoutMs();
        this.shutdownInputSignal = new AtomicBoolean(false);
        this.shutdownOutputSignal = new AtomicBoolean(false);

        this.allocator = allocator;
        this.rcvSwapBuffer = allocator.jvmBuffer(soConfig.getSoRcvBuf());
        this.ignoreReadEofFlag = false;
    }

    public void ignoreReadEofFlag() {
        this.ignoreReadEofFlag = true;
    }

    /** Returns whether the read channel is closed. */
    public boolean isShutdownInput() {
        return this.shutdownInputSignal.get();
    }

    /** Shutdown the connection for reading without closing the channel. */
    public void shutdownInput() throws IOException {
        if (this.shutdownOutputSignal.get()) {
            this.close();
            return;
        }

        try {
            if (this.channel.supportShutdownInput()) {
                this.channel.shutdownInput();
            }
        } finally {
            this.shutdownInputSignal.set(true);
        }
    }

    /** Returns whether the write channel is closed. */
    public boolean isShutdownOutput() {
        return this.shutdownOutputSignal.get();
    }

    /** Shutdown the connection for write without closing the channel. */
    public void shutdownOutput() throws IOException {
        if (this.shutdownInputSignal.get()) {
            this.close();
            return;
        }

        try {
            if (this.channel.supportShutdownOutput()) {
                this.channel.shutdownOutput();
            }
        } finally {
            this.shutdownOutputSignal.set(true);
        }
    }

    /** Returns the remote address, using {@link AsynchronousSocketChannel#getRemoteAddress()} method. */
    public SocketAddress getRemoteAddress() throws IOException {
        return this.channel.getRemoteAddress();
    }

    /** Returns the local address, using {@link AsynchronousSocketChannel#getLocalAddress()} method. */
    public SocketAddress getLocalAddress() throws IOException {
        return this.channel.getLocalAddress();
    }

    public boolean isOpen() {
        if (!this.shutdownInputSignal.get()) {
            return this.channel.isOpen();
        } else {
            return !this.shutdownOutputSignal.get();
        }
    }

    public boolean isIgnoreReadEofFlag() {
        return this.ignoreReadEofFlag;
    }

    @Override
    public void close() throws IOException {
        this.shutdownInputSignal.set(true);
        this.shutdownOutputSignal.set(true);
        this.channel.close();

        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.rcvSwapBuffer);
        }
    }

    /** pull data from {@link ByteBuffer} type to new {@link ByteBuf}. */
    public ByteBuf pullSwapBuffer(int pullSize) {
        if (pullSize <= 0) {
            return ByteBuf.EMPTY;
        }

        this.rcvSwapBuffer.flip();
        if (!this.rcvSwapBuffer.hasRemaining()) {
            return ByteBuf.EMPTY;
        }

        ByteBuf byteBuf = this.allocator.buffer(pullSize);
        byteBuf.writeBuffer(this.rcvSwapBuffer);
        byteBuf.markWriter();
        return byteBuf;
    }

    /** Reads a sequence of bytes from this channel into the given buffer. */
    public boolean read(SoContextImpl context, SoRcvCompletionHandler rHandler) {
        if (this.shutdownInputSignal.get()) {
            return false;
        }

        this.rcvSwapBuffer.clear();
        if (this.rTimeoutMs != null && this.rTimeoutMs > 0) {
            this.channel.read(this.rcvSwapBuffer, this.rTimeoutMs, TimeUnit.MILLISECONDS, context, rHandler);
        } else {
            this.channel.read(this.rcvSwapBuffer, context, rHandler);
        }

        return true;
    }

    /** Writes a sequence of bytes to this channel from the given buffer. */
    public boolean write(ByteBuffer swapBuf, SoContextImpl context, SoSndCompletionHandler wHandler) {
        if (this.shutdownOutputSignal.get()) {
            return false;
        }

        if (this.wTimeoutMs != null && this.wTimeoutMs > 0) {
            this.channel.write(swapBuf, this.wTimeoutMs, TimeUnit.MILLISECONDS, context, wHandler);
        } else {
            this.channel.write(swapBuf, context, wHandler);
        }

        return true;
    }

    public void connect(InetSocketAddress remoteAddr, SoContextImpl context, SoConnectCompletionHandler handler) throws IOException {
        this.channel.connect(remoteAddr, context, handler);
    }
}
