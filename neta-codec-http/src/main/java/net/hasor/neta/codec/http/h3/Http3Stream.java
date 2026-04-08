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

/**
 * 表示连接中的单个 HTTP/3 stream。
 * <p>
 * HTTP/3 使用 QUIC stream 进行多路复用。每一组请求/响应通常对应一个独立的双向 QUIC stream。
 * 该类负责跟踪每个 stream 的 HTTP 层状态以及累计的 header block。
 * @see Http3StreamState
 */
public class Http3Stream {
    private final    long             streamId;
    private volatile Http3StreamState state;
    private          byte[]           accumulatedHeaderBlock;
    private          boolean          headersReceived;
    private          boolean          trailersReceived;

    /**
     * 创建一个新的 HTTP/3 stream。
     * @param streamId QUIC stream 标识
     */
    public Http3Stream(long streamId) {
        this.streamId = streamId;
        this.state = Http3StreamState.IDLE;
        this.headersReceived = false;
        this.trailersReceived = false;
    }

    /**
     * 返回 stream ID。
     */
    public long streamId() {
        return streamId;
    }

    /**
     * 返回当前 stream 状态。
     */
    public Http3StreamState state() {
        return state;
    }

    /**
     * 设置当前 stream 状态。
     * @param state 新状态
     */
    public void state(Http3StreamState state) {
        this.state = state;
    }

    /**
     * 返回是否已收到 headers。
     */
    public boolean headersReceived() {
        return headersReceived;
    }

    /**
     * 标记已收到 headers。
     */
    public void markHeadersReceived() {
        this.headersReceived = true;
    }

    /**
     * 返回是否已收到 trailers。
     */
    public boolean trailersReceived() {
        return trailersReceived;
    }

    /**
     * 标记已收到 trailers。
     */
    public void markTrailersReceived() {
        this.trailersReceived = true;
    }

    /**
     * 返回累计的 header block 字节，用于多 frame 的头块重组。
     */
    public byte[] accumulatedHeaderBlock() {
        return accumulatedHeaderBlock;
    }

    /**
     * 追加 header block 数据。
     * @param data 要追加的数据
     */
    public void appendHeaderBlock(byte[] data) {
        if (this.accumulatedHeaderBlock == null) {
            this.accumulatedHeaderBlock = data;
        } else {
            byte[] merged = new byte[this.accumulatedHeaderBlock.length + data.length];
            System.arraycopy(this.accumulatedHeaderBlock, 0, merged, 0, this.accumulatedHeaderBlock.length);
            System.arraycopy(data, 0, merged, this.accumulatedHeaderBlock.length, data.length);
            this.accumulatedHeaderBlock = merged;
        }
    }

    /**
     * 清空累计的 header block。
     */
    public void clearHeaderBlock() {
        this.accumulatedHeaderBlock = null;
    }

    /**
     * 释放当前 stream 持有的资源。
     */
    public void release() {
        this.accumulatedHeaderBlock = null;
        this.state = Http3StreamState.CLOSED;
    }

    @Override
    public String toString() {
        return "Http3Stream{id=" + streamId + ", state=" + state + "}";
    }
}
