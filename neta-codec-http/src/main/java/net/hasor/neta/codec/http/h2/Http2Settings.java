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
 * HTTP/2 connection settings defined by RFC 9113 Section 6.5.2.
 * <p>
 * Settings parameters control connection-level behavior such as flow control, header table size,
 * and concurrency limits.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
public class Http2Settings {
    /** SETTINGS_HEADER_TABLE_SIZE (0x01), the HPACK header table size, defaulting to 4096. */
    public static final int SETTINGS_HEADER_TABLE_SIZE      = 0x01;
    /** SETTINGS_ENABLE_PUSH (0x02), the server push switch, defaulting to 1 (enabled). */
    public static final int SETTINGS_ENABLE_PUSH            = 0x02;
    /** SETTINGS_MAX_CONCURRENT_STREAMS (0x03), the maximum concurrent stream count, unlimited by default. */
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x03;
    /** SETTINGS_INITIAL_WINDOW_SIZE (0x04), the initial flow-control window size, defaulting to 65535. */
    public static final int SETTINGS_INITIAL_WINDOW_SIZE    = 0x04;
    /** SETTINGS_MAX_FRAME_SIZE (0x05), the maximum frame payload size, defaulting to 16384. */
    public static final int SETTINGS_MAX_FRAME_SIZE         = 0x05;
    /** SETTINGS_MAX_HEADER_LIST_SIZE (0x06), the maximum header list size, unlimited by default. */
    public static final int SETTINGS_MAX_HEADER_LIST_SIZE   = 0x06;

    /** Default values defined by RFC 9113. */
    private long    headerTableSize      = 4096;
    private boolean enablePush           = true;
    private long    maxConcurrentStreams = Long.MAX_VALUE;
    private int     initialWindowSize    = 65535;
    private int     maxFrameSize         = 16384;
    private long    maxHeaderListSize    = Long.MAX_VALUE;

    public Http2Settings() {
    }

    /**
     * Copy constructor.
     * @param other the source settings
     */
    public Http2Settings(Http2Settings other) {
        this.headerTableSize = other.headerTableSize;
        this.enablePush = other.enablePush;
        this.maxConcurrentStreams = other.maxConcurrentStreams;
        this.initialWindowSize = other.initialWindowSize;
        this.maxFrameSize = other.maxFrameSize;
        this.maxHeaderListSize = other.maxHeaderListSize;
    }

    /**
     * Builds the default local settings.
     * @param serverMode whether server mode is used
     * @return the default settings
     */
    public static Http2Settings defaultLocalSettings(boolean serverMode) {
        return defaultLocalSettings(serverMode, 4096, 8192, 65535);
    }

    /**
     * Builds the default local settings.
     * @param serverMode whether server mode is used
     * @param maxHeaderListSize the maximum header list size
     * @param initialWindowSize the initial window size
     * @return the default settings
     */
    public static Http2Settings defaultLocalSettings(boolean serverMode, long maxHeaderListSize, int initialWindowSize) {
        return defaultLocalSettings(serverMode, 4096, maxHeaderListSize, initialWindowSize);
    }

    /**
     * Builds the default local settings.
     * @param serverMode whether server mode is used
     * @param headerTableSize the header table size
     * @param maxHeaderListSize the maximum header list size
     * @param initialWindowSize the initial window size
     * @return the default settings
     */
    public static Http2Settings defaultLocalSettings(boolean serverMode, long headerTableSize, long maxHeaderListSize, int initialWindowSize) {
        Http2Settings settings = new Http2Settings();
        settings.headerTableSize(headerTableSize);
        settings.maxHeaderListSize(maxHeaderListSize);
        settings.initialWindowSize(Math.max(initialWindowSize, 65535));
        if (serverMode) {
            settings.enablePush(false);
            settings.maxConcurrentStreams(100L);
        }
        return settings;
    }

    /**
     * Returns the header table size.
     */
    public long headerTableSize() {
        return headerTableSize;
    }

    /**
     * Sets the header table size.
     * @param value the new value
     * @return the current settings instance
     */
    public Http2Settings headerTableSize(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid HEADER_TABLE_SIZE: " + value);
        }
        this.headerTableSize = value;
        return this;
    }

    /**
     * Returns whether push is enabled.
     */
    public boolean enablePush() {
        return enablePush;
    }

    /**
     * Sets whether push is enabled.
     * @param value whether push is enabled
     * @return the current settings instance
     */
    public Http2Settings enablePush(boolean value) {
        this.enablePush = value;
        return this;
    }

    /**
     * Returns the maximum concurrent stream count.
     */
    public long maxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /**
     * Sets the maximum concurrent stream count.
     * @param value the new value
     * @return the current settings instance
     */
    public Http2Settings maxConcurrentStreams(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid MAX_CONCURRENT_STREAMS: " + value);
        }
        this.maxConcurrentStreams = value;
        return this;
    }

    /**
     * Returns the initial window size.
     */
    public int initialWindowSize() {
        return initialWindowSize;
    }

    /**
     * Sets the initial window size.
     * @param value the new value
     * @return the current settings instance
     */
    public Http2Settings initialWindowSize(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("invalid INITIAL_WINDOW_SIZE: " + value);
        }
        this.initialWindowSize = value;
        return this;
    }

    /**
     * Returns the maximum frame size.
     */
    public int maxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the maximum frame size.
     * @param value the new value
     * @return the current settings instance
     */
    public Http2Settings maxFrameSize(int value) {
        if (value < 16384 || value > 16777215) {
            throw new IllegalArgumentException("invalid MAX_FRAME_SIZE: " + value + " (must be 16384..16777215)");
        }
        this.maxFrameSize = value;
        return this;
    }

    /**
     * Returns the maximum header list size.
     */
    public long maxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Sets the maximum header list size.
     * @param value the new value
     * @return the current settings instance
     */
    public Http2Settings maxHeaderListSize(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("invalid MAX_HEADER_LIST_SIZE: " + value);
        }
        this.maxHeaderListSize = value;
        return this;
    }

    /**
     * Applies a setting by its SETTINGS parameter ID.
     * Unknown settings are silently ignored as required by RFC 9113.
     * @param id the setting ID
     * @param value the setting value
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
