/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.h3;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * 由 {@link Http3FrameToHttpDecoder} 与 {@link Http3HttpToFrameEncoder} 共享的连接级状态容器。
 * <p>
 * 相关操作被划分为四类：
 * <ul>
 * <li><b>init</b>：构造方法以及初始化配置。</li>
 * <li><b>append</b>：仅由 {@link Http3FrameToHttpDecoder} 调用，用于在 frame 到达时补充状态。</li>
 * <li><b>poll</b>：由语义编码路径调用，用于提取排队的响应 stream 关联数据。</li>
 * <li><b>release</b>：在连接关闭时调用，用于释放资源。</li>
 * </ul>
 * 所有字段均为私有，调用方不得直接访问内部集合或子对象。
 */
class Http3DecoderContent {
    private final QpackDecoder           qpackDecoder;
    private final Map<Long, Http3Stream> streams               = new HashMap<>();
    private final Http3Settings          remoteSettings        = new Http3Settings();
    private final Queue<Long>            responseStreamIdQueue = new LinkedList<>();
    private boolean                      settingsReceived;

    Http3DecoderContent(Http3Settings localSettings) {
        Http3Settings settings = localSettings != null ? new Http3Settings(localSettings) : Http3Settings.defaultLocalSettings(false);
        this.qpackDecoder = new QpackDecoder(settings.localQpackMaxTableCapacity(), settings.localMaxFieldSectionSize());
        this.settingsReceived = false;
    }

    // ─── append（由 Http3FrameToHttpDecoder 调用） ───────────────────────────────

    /**
     * 标记 SETTINGS frame 已收到。
     */
    void markSettingsReceived() {
        this.settingsReceived = true;
    }

    /**
     * 返回指定 stream ID 对应的 stream；如不存在则创建。
     */
    Http3Stream getOrCreateStream(long streamId) {
        return streams.computeIfAbsent(streamId, Http3Stream::new);
    }

    /**
     * 返回指定 stream ID 对应的 stream；如果不存在则返回 {@code null}。
     */
    Http3Stream getStream(long streamId) {
        return streams.get(streamId);
    }

    /**
     * 关闭并释放指定 stream ID 对应的 stream。
     */
    void closeStream(long streamId) {
        Http3Stream stream = streams.remove(streamId);
        if (stream != null) {
            stream.state(Http3StreamState.CLOSED);
            stream.release();
        }
    }

    /**
     * 从 FIFO 队列中移除指定 stream 对应的待响应 stream ID 条目。
     */
    void removeFromResponseQueue(long streamId) {
        responseStreamIdQueue.removeIf(id -> id == streamId);
    }

    /**
     * 解码 QPACK 压缩后的 header block。
     */
    HttpHeaders decodeHeaders(byte[] data, int offset, int length) {
        return qpackDecoder.decode(data, offset, length);
    }

    /**
     * 记录一个可用于后续响应关联的请求 stream ID。
     */
    void offerResponseStreamId(long streamId) {
        responseStreamIdQueue.offer(streamId);
    }

    /**
     * 应用一个远端 SETTINGS 参数。
     * 保留 settings 会按 RFC 9114 第 7.2.4 节要求静默忽略。
     */
    void applyRemoteSetting(long settingId, long settingValue) {
        if (!Http3Settings.isReservedSetting(settingId)) {
            remoteSettings.applySetting(settingId, settingValue);
        }
    }

    // ─── poll（由语义编码路径调用） ─────────────────────────────────────────────

    /**
     * 提取下一个响应 stream ID；队列为空时返回 -1。
     */
    long pollResponseStreamId() {
        Long id = this.responseStreamIdQueue.poll();
        return id != null ? id : -1;
    }

    // ─── state view（只读，供 Http3ContextImpl 使用） ────────────────────────────

    /**
     * 当 SETTINGS frame 已收到时返回 {@code true}。
     */
    boolean isSettingsReceived() {
        return settingsReceived;
    }

    /**
     * 返回当前已跟踪的最大 stream ID。
     */
    long lastStreamId() {
        long max = 0;
        for (Long id : this.streams.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    /**
     * 返回与远端协商得到的 {@code SETTINGS_MAX_FIELD_SECTION_SIZE}。
     */
    long maxFieldSectionSize() {
        return remoteSettings.maxFieldSectionSize();
    }

    /**
     * 返回与远端协商得到的 {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}。
     */
    long qpackMaxTableCapacity() {
        return remoteSettings.qpackMaxTableCapacity();
    }

    /**
     * 返回与远端协商得到的 {@code SETTINGS_QPACK_BLOCKED_STREAMS}。
     */
    long qpackBlockedStreams() {
        return remoteSettings.qpackBlockedStreams();
    }

    // ─── release ───────────────────────────────────────────────────────────────────

    /**
     * 在连接关闭时释放所有 stream 资源。
     */
    void releaseAll() {
        for (Http3Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }
}
