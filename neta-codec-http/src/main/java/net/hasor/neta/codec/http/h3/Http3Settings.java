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
 * HTTP/3 settings as defined in RFC 9114, Section 7.2.4.1.
 * <p>
 * HTTP/3 settings are sent on the control stream at the start of a connection.
 * Unlike HTTP/2, HTTP/3 does not have flow-control settings (handled by QUIC)
 * and does not support server push enablement (always available).
 * <p>
 * Settings IDs defined by the formula 0x1f * N + 0x21 are reserved
 * and MUST be treated as unknown (greasing).
 */
public class Http3Settings {
    /** QPACK maximum dynamic table capacity (0x01). Default: 0. */
    public static final long SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0x01;
    /** Maximum value of header list size (0x06). Default: unlimited. */
    public static final long SETTINGS_MAX_FIELD_SECTION_SIZE   = 0x06;
    /** QPACK maximum blocked streams (0x07). Default: 0. */
    public static final long SETTINGS_QPACK_BLOCKED_STREAMS    = 0x07;
    /** Enable connect protocol (0x08), per RFC 8441. Default: 0 (disabled). */
    public static final long SETTINGS_ENABLE_CONNECT_PROTOCOL  = 0x08;

    private long    qpackMaxTableCapacity = 0;
    private long    maxFieldSectionSize   = Long.MAX_VALUE;
    private long    qpackBlockedStreams   = 0;
    private boolean enableConnectProtocol = false;

    public Http3Settings() {
    }

    /** Copy constructor. */
    public Http3Settings(Http3Settings other) {
        this.qpackMaxTableCapacity = other.qpackMaxTableCapacity;
        this.maxFieldSectionSize = other.maxFieldSectionSize;
        this.qpackBlockedStreams = other.qpackBlockedStreams;
        this.enableConnectProtocol = other.enableConnectProtocol;
    }

    /**
     * Returns true if the setting ID is reserved (greasing).
     * Reserved IDs: 0x1f * N + 0x21
     */
    public static boolean isReservedSetting(long id) {
        return id >= 0x21 && ((id - 0x21) % 0x1f) == 0;
    }

    public long qpackMaxTableCapacity() {
        return qpackMaxTableCapacity;
    }

    public Http3Settings qpackMaxTableCapacity(long value) {
        this.qpackMaxTableCapacity = value;
        return this;
    }

    public long maxFieldSectionSize() {
        return maxFieldSectionSize;
    }

    public Http3Settings maxFieldSectionSize(long value) {
        this.maxFieldSectionSize = value;
        return this;
    }

    public long qpackBlockedStreams() {
        return qpackBlockedStreams;
    }

    public Http3Settings qpackBlockedStreams(long value) {
        this.qpackBlockedStreams = value;
        return this;
    }

    public boolean enableConnectProtocol() {
        return enableConnectProtocol;
    }

    public Http3Settings enableConnectProtocol(boolean value) {
        this.enableConnectProtocol = value;
        return this;
    }

    /**
     * Applies a setting by its ID.
     * @param settingId the setting identifier
     * @param value the setting value
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
