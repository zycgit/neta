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
 * A tcp network channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetMonitor {
    private final    long createdTime = System.currentTimeMillis();
    private          long rcvCounterBytes;
    private          long sndCounterBytes;
    private volatile long lastSndTime;
    private volatile long lastRcvTime;

    public long getCreatedTime() {
        return this.createdTime;
    }

    public long getLastRcvTime() {
        return this.lastRcvTime;
    }

    public long getLastSndTime() {
        return this.lastSndTime;
    }

    public long getLastActiveTime() {
        return Math.max(this.lastRcvTime, this.lastSndTime);
    }

    public long getRcvCounterBytes() {
        return this.rcvCounterBytes;
    }

    public long getSndCounterBytes() {
        return this.sndCounterBytes;
    }

    public void updateRcvCounter(long update) {
        this.rcvCounterBytes += update;
        this.lastRcvTime = System.currentTimeMillis();
    }

    public void updateSndCounter(long update) {
        this.sndCounterBytes += update;
        this.lastSndTime = System.currentTimeMillis();
    }
}