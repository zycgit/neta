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
 * Traffic counter and I/O timing monitor attached to a {@link NetChannel}.
 * Tracks total bytes sent/received and the timestamps of the last I/O activities.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetMonitor {
    private final    long createdTime = System.currentTimeMillis();
    private          long rcvCounterBytes;
    private          long sndCounterBytes;
    private volatile long lastSndTime;
    private volatile long lastRcvTime;

    /** Returns the epoch-ms timestamp when this monitor (and its channel) was created. */
    public long getCreatedTime() {
        return this.createdTime;
    }

    /** Returns the epoch-ms timestamp of the last data reception, or 0 if nothing received yet. */
    public long getLastRcvTime() {
        return this.lastRcvTime;
    }

    /** Returns the epoch-ms timestamp of the last data send, or 0 if nothing sent yet. */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /** Returns the later of {@link #getLastRcvTime()} and {@link #getLastSndTime()}. */
    public long getLastActiveTime() {
        return Math.max(this.lastRcvTime, this.lastSndTime);
    }

    /** Returns the cumulative number of bytes received since channel creation. */
    public long getRcvCounterBytes() {
        return this.rcvCounterBytes;
    }

    /** Returns the cumulative number of bytes sent since channel creation. */
    public long getSndCounterBytes() {
        return this.sndCounterBytes;
    }

    /** Adds {@code update} bytes to the receive counter and refreshes the last-receive timestamp. */
    public void updateRcvCounter(long update) {
        this.rcvCounterBytes += update;
        this.lastRcvTime = System.currentTimeMillis();
    }

    /** Adds {@code update} bytes to the send counter and refreshes the last-send timestamp. */
    public void updateSndCounter(long update) {
        this.sndCounterBytes += update;
        this.lastSndTime = System.currentTimeMillis();
    }
}