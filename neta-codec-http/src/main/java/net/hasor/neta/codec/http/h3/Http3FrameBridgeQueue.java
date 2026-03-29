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
package net.hasor.neta.codec.http.h3;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ReferenceHolder;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;

/**
 * A lightweight bridge queue that implements both {@link ProtoRcvQueue} and {@link ProtoSndQueue}.
 * <p>
 * Used internally by {@link Http3ServerDuplexe} and {@link Http3ClientDuplexe} to chain
 * two handlers with an intermediate {@link Http3Frame} buffer, enabling the pipeline:
 * <pre>
 *   ByteBuf →[FrameDecoder]→ Http3Frame →[FrameToHttpDecoder]→ HttpObject
 * </pre>
 */
final class Http3FrameBridgeQueue implements ProtoRcvQueue<Http3Frame>, ProtoSndQueue<Http3Frame> {
    private final List<Http3Frame> list = new ArrayList<>();

    @Override
    public int getCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int slotSize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int offerMessage(Http3Frame[] offerList) {
        Collections.addAll(list, offerList);
        return offerList.length;
    }

    @Override
    public int offerMessage(List<Http3Frame> offerList) {
        list.addAll(offerList);
        return offerList.size();
    }

    @Override
    public int offerMessage(ProtoRcvQueue<Http3Frame> offerList) {
        int count = 0;
        while (offerList.hasMore()) {
            list.add(offerList.takeMessage());
            count++;
        }
        return count;
    }

    @Override
    public int queueSize() {
        return list.size();
    }

    @Override
    public List<Http3Frame> takeMessage(int cnt) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, list.size());
        List<Http3Frame> result = new ArrayList<>(list.subList(0, take));
        list.subList(0, take).clear();
        return result;
    }

    @Override
    public List<Http3Frame> peekMessage(int cnt) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, list.size());
        return new ArrayList<>(list.subList(0, take));
    }

    @Override
    public void skipMessage(int cnt) {
        int skip = Math.min(cnt, list.size());
        if (skip > 0) {
            for (int i = 0; i < skip; i++) {
                releaseOwned(list.get(i));
            }
            list.subList(0, skip).clear();
        }
    }

    /** Clears all frames from the queue. */
    void clear() {
        for (Http3Frame item : list) {
            releaseOwned(item);
        }
        list.clear();
    }

    private static void releaseOwned(Object item) {
        if (item instanceof ReferenceHolder) {
            ((ReferenceHolder) item).release();
        } else if (item instanceof Release) {
            ((Release) item).release();
        } else if (item instanceof Closeable) {
            IOUtils.closeQuietly((Closeable) item);
        }
    }
}
