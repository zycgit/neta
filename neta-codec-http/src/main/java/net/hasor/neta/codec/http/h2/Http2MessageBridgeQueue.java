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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;

/** Bridge queue used to chain HTTP/2 message-layer handlers together. */
final class Http2MessageBridgeQueue implements ProtoRcvQueue<Http2Message>, ProtoSndQueue<Http2Message> {
    private final List<Http2Message> list = new ArrayList<>();

    @Override
    public int getCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int slotSize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean hasCommit() {
        return true;
    }

    @Override
    public ProtoSndQueue<Http2Message> sndSubmit() {
        return this;
    }

    @Override
    public ProtoSndQueue<Http2Message> sndReset() {
        return this;
    }

    @Override
    public int offerMessage(Http2Message[] offerList) {
        Collections.addAll(this.list, offerList);
        return offerList.length;
    }

    @Override
    public int offerMessage(List<Http2Message> offerList) {
        this.list.addAll(offerList);
        return offerList.size();
    }

    @Override
    public int offerMessage(ProtoRcvQueue<Http2Message> offerList) {
        int count = 0;
        while (offerList.hasMore()) {
            this.list.add(offerList.takeMessage());
            count++;
        }
        return count;
    }

    @Override
    public int queueSize() {
        return this.list.size();
    }

    @Override
    public ProtoRcvQueue<Http2Message> rcvSubmit() {
        return this;
    }

    @Override
    public ProtoRcvQueue<Http2Message> rcvReset() {
        return this;
    }

    @Override
    public List<Http2Message> takeMessage(int cnt) {
        if (this.list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, this.list.size());
        List<Http2Message> result = new ArrayList<>(this.list.subList(0, take));
        this.list.subList(0, take).clear();
        return result;
    }

    @Override
    public List<Http2Message> peekMessage(int cnt) {
        if (this.list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, this.list.size());
        return new ArrayList<>(this.list.subList(0, take));
    }

    @Override
    public void skipMessage(int cnt) {
        int skip = Math.min(cnt, this.list.size());
        this.list.subList(0, skip).clear();
    }

    void clear() {
        this.list.clear();
    }

    @Override
    public boolean hasMore() {
        return !this.list.isEmpty();
    }
}