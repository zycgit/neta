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
 * HTTP/2 connection settings as defined in RFC 9113, Section 6.5.2.
 * <p>
 * Settings parameters control connection-level behavior such as flow control,
 * header table size, and concurrency limits.
 */
public class Http2Settings {
    /** SETTINGS_HEADER_TABLE_SIZE (0x01) - HPACK header table size. Default: 4096. */
    public static final int SETTINGS_HEADER_TABLE_SIZE      = 0x01;
    /** SETTINGS_ENABLE_PUSH (0x02) - Server push enable/disable. Default: 1 (enabled). */
    public static final int SETTINGS_ENABLE_PUSH            = 0x02;
    /** SETTINGS_MAX_CONCURRENT_STREAMS (0x03) - Max concurrent streams. Default: unlimited. */
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x03;
    /** SETTINGS_INITIAL_WINDOW_SIZE (0x04) - Initial flow-control window size. Default: 65535. */
    public static final int SETTINGS_INITIAL_WINDOW_SIZE    = 0x04;
    /** SETTINGS_MAX_FRAME_SIZE (0x05) - Max frame payload size. Default: 16384. */
    public static final int SETTINGS_MAX_FRAME_SIZE         = 0x05;
    /** SETTINGS_MAX_HEADER_LIST_SIZE (0x06) - Max size of header list. Default: unlimited. */
    public static final int SETTINGS_MAX_HEADER_LIST_SIZE   = 0x06;

    /** Default values per RFC 9113 */
    private long    headerTableSize      = 4096;
    private boolean enablePush           = true;
    private long    maxConcurrentStreams = Long.MAX_VALUE;
    private int     initialWindowSize    = 65535;
    private int     maxFrameSize         = 16384;
    private long    maxHeaderListSize    = Long.MAX_VALUE;

    public Http2Settings() {
    }

    /** Copy constructor. */
    public Http2Settings(Http2Settings other) {
        this.headerTableSize = other.headerTableSize;
        this.enablePush = other.enablePush;
        this.maxConcurrentStreams = other.maxConcurrentStreams;
        this.initialWindowSize = other.initialWindowSize;
        this.maxFrameSize = other.maxFrameSize;
        this.maxHeaderListSize = other.maxHeaderListSize;
    }

    public long headerTableSize() {
        return headerTableSize;
    }

    public Http2Settings headerTableSize(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid HEADER_TABLE_SIZE: " + value);
        }
        this.headerTableSize = value;
        return this;
    }

    public boolean enablePush() {
        return enablePush;
    }

    public Http2Settings enablePush(boolean value) {
        this.enablePush = value;
        return this;
    }

    public long maxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public Http2Settings maxConcurrentStreams(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid MAX_CONCURRENT_STREAMS: " + value);
        }
        this.maxConcurrentStreams = value;
        return this;
    }

    public int initialWindowSize() {
        return initialWindowSize;
    }

    public Http2Settings initialWindowSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("invalid INITIAL_WINDOW_SIZE: " + value);
        }
        this.initialWindowSize = value;
        return this;
    }

    public int maxFrameSize() {
        return maxFrameSize;
    }

    public Http2Settings maxFrameSize(int value) {
        if (value < 16384 || value > 16777215) {
            throw new IllegalArgumentException("invalid MAX_FRAME_SIZE: " + value + " (must be 16384..16777215)");
        }
        this.maxFrameSize = value;
        return this;
    }

    public long maxHeaderListSize() {
        return maxHeaderListSize;
    }

    public Http2Settings maxHeaderListSize(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid MAX_HEADER_LIST_SIZE: " + value);
        }
        this.maxHeaderListSize = value;
        return this;
    }

    /**
     * Applies a setting identified by its SETTINGS parameter ID.
     * Unknown settings are silently ignored per RFC 9113.
     */
    public void applySetting(int id, long value) {
        switch (id) {
            case SETTINGS_HEADER_TABLE_SIZE:
                headerTableSize(value);
                break;
            case SETTINGS_ENABLE_PUSH:
                enablePush(value != 0);
                break;
            case SETTINGS_MAX_CONCURRENT_STREAMS:
                maxConcurrentStreams(value);
                break;
            case SETTINGS_INITIAL_WINDOW_SIZE:
                initialWindowSize((int) value);
                break;
            case SETTINGS_MAX_FRAME_SIZE:
                maxFrameSize((int) value);
                break;
            case SETTINGS_MAX_HEADER_LIST_SIZE:
                maxHeaderListSize(value);
                break;
            default:
                // Unknown settings MUST be ignored (RFC 9113, Section 6.5.2)
                break;
        }
    }

    @Override
    public String toString() {
        return "Http2Settings{" + "headerTableSize=" + headerTableSize + ", enablePush=" + enablePush + ", maxConcurrentStreams=" + maxConcurrentStreams + ", initialWindowSize=" + initialWindowSize + ", maxFrameSize=" + maxFrameSize + ", maxHeaderListSize=" + maxHeaderListSize + '}';
    }
}
