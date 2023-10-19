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
package net.hasor.cobble.net.channel;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.net.bytebuf.ByteBuf;
import net.hasor.cobble.net.bytebuf.ByteBufAllocator;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * SoResManager implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoResManagerImpl implements SoResManager, AutoCloseable {
    private static final Logger           logger = Logger.getLogger(SoResManagerImpl.class);
    private final        SoConfig         config;
    private final        ByteBufAllocator bufAllocator;
    private final        ExecutorService  executor;
    private final        List<Object>     resources;

    public SoResManagerImpl(SoConfig config, ExecutorService executor) {
        this.config = config;
        this.bufAllocator = config.getBufAllocator() == null ? ByteBufAllocator.DEFAULT : this.config.getBufAllocator();
        this.executor = executor;
        this.resources = new ArrayList<>();
    }

    @Override
    public ByteBuf newByteBuf(int capacity) {
        ByteBuf byteBuf;
        if (this.bufAllocator.isDirect()) {
            if (capacity < 0) {
                byteBuf = this.bufAllocator.directBuffer();
            } else {
                byteBuf = this.bufAllocator.directBuffer(capacity);
            }
        } else {
            if (capacity < 0) {
                byteBuf = this.bufAllocator.heapBuffer();
            } else {
                byteBuf = this.bufAllocator.heapBuffer(capacity);
            }
        }

        this.resources.add(byteBuf);
        return byteBuf;
    }

    @Override
    public ByteBuffer newByteBuffer(int capacity) {
        return this.newByteBuf(capacity).asByteBuffer();
    }

    @Override
    public ByteBuffer newSwapRcvBuf() {
        return this.newByteBuf(this.config.getRcvSwapBuf()).asByteBuffer();
    }

    @Override
    public ByteBuffer newSwapSndBuf() {
        return this.newByteBuf(this.config.getSndSwapBuf()).asByteBuffer();
    }

    @Override
    public ByteBuf newLocalRcvBuf() {
        return this.newByteBuf(this.config.getRcvLocalBuf());
    }

    @Override
    public ByteBuf newLocalSndBuf() {
        return this.newByteBuf(this.config.getSndLocalBuf());
    }

    @Override
    public synchronized <T> T freeObject(Object refObj) {
        int index = this.resources.indexOf(refObj);
        if (index > 0) {
            this.resources.remove(index);
            if (refObj instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) refObj);
            } else if (refObj instanceof AutoCloseable) {
                IOUtils.closeQuietly((AutoCloseable) refObj);
            }
        }
        return null;
    }

    @Override
    public void submitTask(Runnable runnable) {
        this.executor.submit(runnable);
    }

    @Override
    public void close() throws Exception {
        long t = System.currentTimeMillis();
        if (this.executor != null) {
            while (!this.executor.isTerminated()) {
                long cost = System.currentTimeMillis() - t;
                if (cost > 3000) {
                    t = System.currentTimeMillis();
                    logger.info("wait workerThread close...");
                }
                ThreadUtils.sleep(50);
            }
            logger.info("workerThread closed.");
        }

        this.resources.forEach(refObj -> {
            if (refObj instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) refObj);
            } else if (refObj instanceof AutoCloseable) {
                IOUtils.closeQuietly((AutoCloseable) refObj);
            }
        });
        this.resources.clear();
    }
}