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

/**
 * A lightweight bridge queue that implements both {@link ProtoRcvQueue} and {@link ProtoSndQueue}.
 * <p>
 * Used internally by {@link Http2ServerDuplexe} and {@link Http2ClientDuplexe} to chain
 * two handlers with an intermediate {@link Http2Frame} buffer, enabling the pipeline:
 * <pre>
 *   ByteBuf →[FrameDecoder]→ Http2Frame →[FrameToHttpDecoder]→ HttpObject
 * </pre>
 */
final class Http2FrameBridgeQueue implements ProtoRcvQueue<Http2Frame>, ProtoSndQueue<Http2Frame> {
    private final List<Http2Frame> list = new ArrayList<>();

    // ========================= ProtoSndQueue =========================

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
    public ProtoSndQueue<Http2Frame> sndSubmit() {
        return this;
    }

    @Override
    public ProtoSndQueue<Http2Frame> sndReset() {
        return this;
    }

    @Override
    public int offerMessage(Http2Frame[] offerList) {
        Collections.addAll(list, offerList);
        return offerList.length;
    }

    @Override
    public int offerMessage(List<Http2Frame> offerList) {
        list.addAll(offerList);
        return offerList.size();
    }

    @Override
    public int offerMessage(ProtoRcvQueue<Http2Frame> offerList) {
        int count = 0;
        while (offerList.hasMore()) {
            list.add(offerList.takeMessage());
            count++;
        }
        return count;
    }

    // ========================= ProtoRcvQueue =========================

    @Override
    public int queueSize() {
        return list.size();
    }

    @Override
    public ProtoRcvQueue<Http2Frame> rcvSubmit() {
        return this;
    }

    @Override
    public ProtoRcvQueue<Http2Frame> rcvReset() {
        return this;
    }

    @Override
    public List<Http2Frame> takeMessage(int cnt) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, list.size());
        List<Http2Frame> result = new ArrayList<>(list.subList(0, take));
        list.subList(0, take).clear();
        return result;
    }

    @Override
    public List<Http2Frame> peekMessage(int cnt) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, list.size());
        return new ArrayList<>(list.subList(0, take));
    }

    @Override
    public void skipMessage(int cnt) {
        int skip = Math.min(cnt, list.size());
        list.subList(0, skip).clear();
    }

    /** Clears all frames from the queue. */
    void clear() {
        list.clear();
    }
}
