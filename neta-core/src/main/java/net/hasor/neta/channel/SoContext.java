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
import java.net.SocketAddress;
import java.util.function.Predicate;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Central service context that manages all {@link NetChannel} and {@link NetListen} instances.
 * <p>Provides channel lookup, event subscription, and access to global configuration.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface SoContext {
    /** return global config. */
    NetConfig getConfig();

    /** default {@link ByteBufAllocator} */
    ByteBufAllocator getByteBufAllocator();

    /** get remote address of the channel */
    SocketAddress getRemoteAddress(long channelId);

    /** test channel is not exist or closed */
    boolean isClose(long channelId);

    /** find SoChannel by id */
    SoChannel<?> findChannel(long channelId);

    /** get {@link NetManager} */
    NetManager getNetManager();

    /**
     * subscribe event
     * @param channelId event topic
     * @param listener event listener
     */
    SubscribeHolder subscribe(long channelId, PlayLoadListener listener);

    /**
     * subscribe event
     * @param select event topic
     * @param listener event listener
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener);
}