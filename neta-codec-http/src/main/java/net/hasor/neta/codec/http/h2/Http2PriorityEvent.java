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
package net.hasor.neta.codec.http.h2;

/**
 * HTTP/2 PRIORITY frame event.
 * <p>
 * Carries the dependency tree information from RFC 9113 Section 6.3.
 */
public class Http2PriorityEvent extends AbstractHttp2Event {
    private final int     streamDependency;
    private final int     weight;
    private final boolean exclusive;

    public Http2PriorityEvent(int streamId, int streamDependency, int weight, boolean exclusive) {
        this.streamId(streamId);
        this.streamDependency = streamDependency;
        this.weight = weight;
        this.exclusive = exclusive;
    }

    public int streamDependency() {
        return this.streamDependency;
    }

    public int weight() {
        return this.weight;
    }

    public boolean exclusive() {
        return this.exclusive;
    }

    @Override
    public String toString() {
        return "Http2PriorityEvent{streamId=" + this.streamId() + ", streamDependency=" + this.streamDependency + ", weight=" + this.weight + ", exclusive=" + this.exclusive + '}';
    }
}
