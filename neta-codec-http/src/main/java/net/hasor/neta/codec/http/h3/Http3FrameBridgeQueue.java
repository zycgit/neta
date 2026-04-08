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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.SoUtils;

/**
 * 一个轻量级桥接队列，同时实现了 {@link ProtoRcvQueue} 和 {@link ProtoSndQueue}。
 * <p>
 * 它被 {@link Http3ServerDuplexe} 与 {@link Http3ClientDuplexe} 内部使用，用于在两个处理器之间通过
 * 中间 {@link Http3Frame} 缓冲区串联处理链，形成如下 pipeline：
 * <pre>
 *   ByteBuf →[FrameDecoder]→ Http3Frame →[FrameToHttpDecoder]→ HttpObject
 * </pre>
 */
final class Http3FrameBridgeQueue implements ProtoRcvQueue<Http3Frame>, ProtoSndQueue<Http3Frame> {
    private final List<Http3Frame> list = new ArrayList<>();

    /**
     * 返回队列容量上限。
     */
    @Override
    public int getCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * 返回槽位容量上限。
     */
    @Override
    public int slotSize() {
        return Integer.MAX_VALUE;
    }

    /**
     * 批量写入数组中的 frame。
     */
    @Override
    public boolean offerMessage(Http3Frame[] offerList) {
        Collections.addAll(list, offerList);
        return true;
    }

    /**
     * 批量写入列表中的 frame。
     */
    @Override
    public boolean offerMessage(List<Http3Frame> offerList) {
        list.addAll(offerList);
        return true;
    }

    /**
     * 将接收队列中的 frame 转移到当前桥接队列。
     */
    @Override
    public boolean offerMessage(ProtoRcvQueue<Http3Frame> offerList) {
        while (offerList.hasMore()) {
            list.add(offerList.takeMessage());
        }
        return true;
    }

    /**
     * 返回当前排队的 frame 数量。
     */
    @Override
    public int queueSize() {
        return list.size();
    }

    /**
     * 取出最多指定数量的 frame。
     */
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

    /**
     * 窥视最多指定数量的 frame。
     */
    @Override
    public List<Http3Frame> peekMessage(int cnt) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        int take = Math.min(cnt, list.size());
        return new ArrayList<>(list.subList(0, take));
    }

    /**
     * 跳过并释放最多指定数量的 frame。
     */
    @Override
    public void skipMessage(int cnt) {
        int skip = Math.min(cnt, list.size());
        if (skip > 0) {
            for (int i = 0; i < skip; i++) {
                SoUtils.release(list.get(i));
            }
            list.subList(0, skip).clear();
        }
    }

    /**
     * 清空队列中的全部 frame。
     */
    void clear() {
        for (Http3Frame item : list) {
            SoUtils.release(item);
        }
        list.clear();
    }
}
