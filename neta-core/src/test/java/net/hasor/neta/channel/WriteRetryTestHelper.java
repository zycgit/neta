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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBufUtils;

/**
 * Test helper that creates SoContextService and NetChannel instances for retry logic unit tests.
 * Must live in net.hasor.neta.channel to access package-private constructor of SoContextService.
 * @author 赵永春 (zyc@hasor.net)
 */
public class WriteRetryTestHelper {
    /** Create a minimal SoContextService backed by a small thread pool. */
    public static SoContextService createContextService() {
        NetConfig config = new NetConfig();
        config.setPrintLog(false);
        config.setBufAllocator(ByteBufUtils.DEFAULT_ALLOCATOR);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(1);
        config.setTaskThreads(1);
        return new SoContextService(config, null);
    }

    /**
     * Create a minimal NetChannel whose {@code getConfig()} returns the given soConfig.
     * The channel is NOT registered with the SoContextService, so exception notifications
     * will be silently dropped (logged only) – which is acceptable for retry-logic tests.
     */
    public static NetChannel createNetChannel(SoConfig soConfig, SoContextService ctx) throws IOException {
        AsyncChannel mockAsync = new AsyncChannel() {
            @Override
            public long getChannelId() {
                return 0;
            }

            @Override
            public SoConfig getSoConfig() {
                return soConfig;
            }

            @Override
            public SocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return null;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public void write(NetChannel channel, SoSndContext wContext) {
            }

            @Override
            public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
            }
        };

        // Use a no-op initializer; the protected constructor is accessible from this package.
        return new NetChannel(1L, new NetMonitor(), null, ctx2 -> {
        }, mockAsync, ctx) {
        };
    }
}
