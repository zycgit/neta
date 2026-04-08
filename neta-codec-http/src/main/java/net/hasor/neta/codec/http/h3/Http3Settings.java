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
 * RFC 9114 第 7.2.4.1 节定义的 HTTP/3 settings。
 * <p>
 * HTTP/3 的 settings 会在连接建立初期通过 control stream 发送。
 * 与 HTTP/2 不同，HTTP/3 不包含流控 settings（由 QUIC 负责），也不提供 server push 开关。
 * <p>
 * 满足公式 0x1f * N + 0x21 的 settings ID 属于保留值，必须按未知值处理（greasing）。
 */
public class Http3Settings {
    /** QPACK 最大动态表容量（0x01），默认值为 0。 */
    public static final long SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0x01;
    /** header field section 最大大小（0x06），默认不限。 */
    public static final long SETTINGS_MAX_FIELD_SECTION_SIZE   = 0x06;
    /** QPACK 最大 blocked streams 数（0x07），默认值为 0。 */
    public static final long SETTINGS_QPACK_BLOCKED_STREAMS    = 0x07;
    /** 启用 connect protocol（0x08，见 RFC 8441），默认值为 0（禁用）。 */
    public static final long SETTINGS_ENABLE_CONNECT_PROTOCOL  = 0x08;

    private long    qpackMaxTableCapacity = 0;
    private long    maxFieldSectionSize   = Long.MAX_VALUE;
    private long    qpackBlockedStreams   = 0;
    private boolean enableConnectProtocol = false;

    public Http3Settings() {
    }

    /**
     * 拷贝构造方法。
     * @param other 源 settings
     */
    public Http3Settings(Http3Settings other) {
        this.qpackMaxTableCapacity = other.qpackMaxTableCapacity;
        this.maxFieldSectionSize = other.maxFieldSectionSize;
        this.qpackBlockedStreams = other.qpackBlockedStreams;
        this.enableConnectProtocol = other.enableConnectProtocol;
    }

    /**
     * 判断 setting ID 是否为保留值（greasing）。
     * 保留 ID 的规则为：0x1f * N + 0x21。
     * @param id setting ID
     * @return 命中保留规则时返回 {@code true}
     */
    public static boolean isReservedSetting(long id) {
        return id >= 0x21 && ((id - 0x21) % 0x1f) == 0;
    }

    /**
     * 返回 QPACK 最大表容量。
     */
    public long qpackMaxTableCapacity() {
        return qpackMaxTableCapacity;
    }

    /**
     * 设置 QPACK 最大表容量。
     * @param value 新值
     * @return 当前 settings
     */
    public Http3Settings qpackMaxTableCapacity(long value) {
        this.qpackMaxTableCapacity = value;
        return this;
    }

    /**
     * 返回最大 field section 大小。
     */
    public long maxFieldSectionSize() {
        return maxFieldSectionSize;
    }

    /**
     * 设置最大 field section 大小。
     * @param value 新值
     * @return 当前 settings
     */
    public Http3Settings maxFieldSectionSize(long value) {
        this.maxFieldSectionSize = value;
        return this;
    }

    /**
     * 返回 QPACK blocked streams 数。
     */
    public long qpackBlockedStreams() {
        return qpackBlockedStreams;
    }

    /**
     * 设置 QPACK blocked streams 数。
     * @param value 新值
     * @return 当前 settings
     */
    public Http3Settings qpackBlockedStreams(long value) {
        this.qpackBlockedStreams = value;
        return this;
    }

    /**
     * 返回是否启用 connect protocol。
     */
    public boolean enableConnectProtocol() {
        return enableConnectProtocol;
    }

    /**
     * 设置是否启用 connect protocol。
     * @param value 是否启用
     * @return 当前 settings
     */
    public Http3Settings enableConnectProtocol(boolean value) {
        this.enableConnectProtocol = value;
        return this;
    }

    /**
     * 按 setting ID 应用一个 setting。
     * @param settingId setting 标识
     * @param value setting 值
     */
    public void applySetting(long settingId, long value) {
        if (settingId == SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
            qpackMaxTableCapacity(value);
        } else if (settingId == SETTINGS_MAX_FIELD_SECTION_SIZE) {
            maxFieldSectionSize(value);
        } else if (settingId == SETTINGS_QPACK_BLOCKED_STREAMS) {
            qpackBlockedStreams(value);
        } else if (settingId == SETTINGS_ENABLE_CONNECT_PROTOCOL) {
            enableConnectProtocol(value != 0);
        }
        // Unknown settings are ignored per RFC 9114 §7.2.4
    }
}
