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
package net.hasor.neta.channel.quic;

/**
 * QUIC transport parameters as defined in RFC 9000, Section 18.2.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSettings {
    public static final int PARAM_MAX_IDLE_TIMEOUT                    = 0x01;
    public static final int PARAM_MAX_UDP_PAYLOAD_SIZE                = 0x03;
    public static final int PARAM_INITIAL_MAX_DATA                    = 0x04;
    public static final int PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL  = 0x05;
    public static final int PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06;
    public static final int PARAM_INITIAL_MAX_STREAM_DATA_UNI         = 0x07;
    public static final int PARAM_INITIAL_MAX_STREAMS_BIDI            = 0x08;
    public static final int PARAM_INITIAL_MAX_STREAMS_UNI             = 0x09;
    public static final int PARAM_ACK_DELAY_EXPONENT                  = 0x0a;
    public static final int PARAM_MAX_ACK_DELAY                       = 0x0b;
    public static final int PARAM_ACTIVE_CONNECTION_ID_LIMIT          = 0x0e;

    private long maxIdleTimeout                 = 0;
    private long maxUdpPayloadSize              = 65527;
    private long initialMaxData                 = 0;
    private long initialMaxStreamDataBidiLocal  = 0;
    private long initialMaxStreamDataBidiRemote = 0;
    private long initialMaxStreamDataUni        = 0;
    private long initialMaxStreamsBidi          = 0;
    private long initialMaxStreamsUni           = 0;
    private int  ackDelayExponent               = 3;
    private long maxAckDelay                    = 25;
    private long activeConnectionIdLimit        = 2;

    public QuicSettings() {
    }

    public QuicSettings(QuicSettings other) {
        this.maxIdleTimeout = other.maxIdleTimeout;
        this.maxUdpPayloadSize = other.maxUdpPayloadSize;
        this.initialMaxData = other.initialMaxData;
        this.initialMaxStreamDataBidiLocal = other.initialMaxStreamDataBidiLocal;
        this.initialMaxStreamDataBidiRemote = other.initialMaxStreamDataBidiRemote;
        this.initialMaxStreamDataUni = other.initialMaxStreamDataUni;
        this.initialMaxStreamsBidi = other.initialMaxStreamsBidi;
        this.initialMaxStreamsUni = other.initialMaxStreamsUni;
        this.ackDelayExponent = other.ackDelayExponent;
        this.maxAckDelay = other.maxAckDelay;
        this.activeConnectionIdLimit = other.activeConnectionIdLimit;
    }

    public long maxIdleTimeout() {
        return maxIdleTimeout;
    }

    public QuicSettings maxIdleTimeout(long value) {
        this.maxIdleTimeout = value;
        return this;
    }

    public long maxUdpPayloadSize() {
        return maxUdpPayloadSize;
    }

    public QuicSettings maxUdpPayloadSize(long value) {
        if (value < 1200) {
            throw new IllegalArgumentException("max_udp_payload_size must be at least 1200: " + value);
        }
        this.maxUdpPayloadSize = value;
        return this;
    }

    public long initialMaxData() {
        return initialMaxData;
    }

    public QuicSettings initialMaxData(long value) {
        this.initialMaxData = value;
        return this;
    }

    public long initialMaxStreamDataBidiLocal() {
        return initialMaxStreamDataBidiLocal;
    }

    public QuicSettings initialMaxStreamDataBidiLocal(long value) {
        this.initialMaxStreamDataBidiLocal = value;
        return this;
    }

    public long initialMaxStreamDataBidiRemote() {
        return initialMaxStreamDataBidiRemote;
    }

    public QuicSettings initialMaxStreamDataBidiRemote(long value) {
        this.initialMaxStreamDataBidiRemote = value;
        return this;
    }

    public long initialMaxStreamDataUni() {
        return initialMaxStreamDataUni;
    }

    public QuicSettings initialMaxStreamDataUni(long value) {
        this.initialMaxStreamDataUni = value;
        return this;
    }

    public long initialMaxStreamsBidi() {
        return initialMaxStreamsBidi;
    }

    public QuicSettings initialMaxStreamsBidi(long value) {
        this.initialMaxStreamsBidi = value;
        return this;
    }

    public long initialMaxStreamsUni() {
        return initialMaxStreamsUni;
    }

    public QuicSettings initialMaxStreamsUni(long value) {
        this.initialMaxStreamsUni = value;
        return this;
    }

    public int ackDelayExponent() {
        return ackDelayExponent;
    }

    public QuicSettings ackDelayExponent(int value) {
        if (value > 20) {
            throw new IllegalArgumentException("ack_delay_exponent must be at most 20: " + value);
        }
        this.ackDelayExponent = value;
        return this;
    }

    public long maxAckDelay() {
        return maxAckDelay;
    }

    public QuicSettings maxAckDelay(long value) {
        if (value >= 16384) {
            throw new IllegalArgumentException("max_ack_delay must be less than 2^14: " + value);
        }
        this.maxAckDelay = value;
        return this;
    }

    public long activeConnectionIdLimit() {
        return activeConnectionIdLimit;
    }

    public QuicSettings activeConnectionIdLimit(long value) {
        if (value < 2) {
            throw new IllegalArgumentException("active_connection_id_limit must be at least 2: " + value);
        }
        this.activeConnectionIdLimit = value;
        return this;
    }

    public void applyParameter(int paramId, long value) {
        switch (paramId) {
            case PARAM_MAX_IDLE_TIMEOUT:
                maxIdleTimeout(value);
                break;
            case PARAM_MAX_UDP_PAYLOAD_SIZE:
                maxUdpPayloadSize(value);
                break;
            case PARAM_INITIAL_MAX_DATA:
                initialMaxData(value);
                break;
            case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL:
                initialMaxStreamDataBidiLocal(value);
                break;
            case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE:
                initialMaxStreamDataBidiRemote(value);
                break;
            case PARAM_INITIAL_MAX_STREAM_DATA_UNI:
                initialMaxStreamDataUni(value);
                break;
            case PARAM_INITIAL_MAX_STREAMS_BIDI:
                initialMaxStreamsBidi(value);
                break;
            case PARAM_INITIAL_MAX_STREAMS_UNI:
                initialMaxStreamsUni(value);
                break;
            case PARAM_ACK_DELAY_EXPONENT:
                ackDelayExponent((int) value);
                break;
            case PARAM_MAX_ACK_DELAY:
                maxAckDelay(value);
                break;
            case PARAM_ACTIVE_CONNECTION_ID_LIMIT:
                activeConnectionIdLimit(value);
                break;
            default:
                break;
        }
    }
}
