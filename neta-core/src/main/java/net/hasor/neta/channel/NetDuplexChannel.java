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
/**
 * A duplex {@link SoChannel} that has two sides that can be shutdown independently.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface NetDuplexChannel<T> extends SoChannel<T> {

    /** Returns whether the read channel is closed. */
    boolean isShutdownInput();

    /** Shutdown the connection for reading without closing the channel. */
    void shutdownInput();

    /**
     * <p>When shutdownOutput is called remotely, an end of read flag was encountered, which usually means closing the channel.
     * But the remote still has the ability to receive data.</p>
     * <p>so use {@link #ignoreReadEofFlag()} method, keep the channel state and continue to send data</p>
     * <p>Once {@link #ignoreReadEofFlag()} is activated, the release of remote connections needs to be managed manually,
     * leading to {@link NetChannel} leakage if not released in time</p>
     */
    void ignoreReadEofFlag();

    /** Returns whether the write channel is closed. */
    boolean isShutdownOutput();

    /** Shutdown the connection for write without closing the channel. */
    void shutdownOutput();
}