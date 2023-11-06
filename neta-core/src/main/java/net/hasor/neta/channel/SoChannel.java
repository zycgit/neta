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
import net.hasor.cobble.concurrent.future.Future;

import java.net.SocketAddress;

/**
 * Channel
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SoChannel<T> {
    /** channel id */
    long getChannelID();

    /** channel accept time */
    long getCreatedTime();

    /** The last time data was received or sent */
    long getLastActiveTime();

    /** a {@link NetListen} */
    boolean isListen();

    /** an accept {@link NetChannel} */
    boolean isServer();

    /** a connect {@link NetChannel} */
    boolean isClient();

    /** local {@link SocketAddress} */
    SocketAddress getLocalAddr();

    /** get remote address of the channel */
    SocketAddress getRemoteAddr();

    /**
     * close this channel.
     *
     * <li>For the Listen channel, listening is turned off.</li>
     * <li>For the Socket channel, will be closed after all data are written.</li>
     *
     * <p>The {@link #closeNow()} and {@link #close()} methods are valid if they are called first</p>
     */
    Future<T> close();

    /** close this channel now. */
    Future<T> closeNow();

    /** return close status. */
    boolean isClose();

    /** set any object to SoChannel attributes */
    void setAttribute(String key, Object value);

    /** get SoChannel attributes */
    Object getAttribute(String key);
}