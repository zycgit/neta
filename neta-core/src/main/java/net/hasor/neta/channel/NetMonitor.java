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
 * Traffic counters and I/O timing monitor maintained by each {@link NetChannel}.
 * <p>Counters start at zero and increase monotonically, resetting only when the channel is created.
 * Timestamps use epoch milliseconds from {@link System#currentTimeMillis()}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetMonitor {
    private final    long createdTime = System.currentTimeMillis();
    private          long rcvCounterBytes;
    private          long sndCounterBytes;
    private volatile long lastSndTime;
    private volatile long lastRcvTime;

    /** Return the epoch millisecond timestamp when this monitor and its channel were created. */
    public long getCreatedTime() {
        return this.createdTime;
    }

    /** Return the epoch millisecond timestamp of the most recent receive, or 0 if none has happened yet. */
    public long getLastRcvTime() {
        return this.lastRcvTime;
    }

    /** Return the epoch millisecond timestamp of the most recent send, or 0 if none has happened yet. */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /** Return the later value of {@link #getLastRcvTime()} and {@link #getLastSndTime()}. */
    public long getLastActiveTime() {
        return Math.max(this.lastRcvTime, this.lastSndTime);
    }

    /** Return the total number of bytes received since the channel was created. */
    public long getRcvCounterBytes() {
        return this.rcvCounterBytes;
    }

    /** Return the total number of bytes sent since the channel was created. */
    public long getSndCounterBytes() {
        return this.sndCounterBytes;
    }

    /** Add {@code update} bytes to the receive counter and refresh the last-receive timestamp. */
    public void updateRcvCounter(long update) {
        this.rcvCounterBytes += update;
        this.lastRcvTime = System.currentTimeMillis();
    }

    /** Add {@code update} bytes to the send counter and refresh the last-send timestamp. */
    public void updateSndCounter(long update) {
        this.sndCounterBytes += update;
        this.lastSndTime = System.currentTimeMillis();
    }
}