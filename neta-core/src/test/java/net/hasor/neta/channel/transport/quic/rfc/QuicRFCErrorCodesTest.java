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
package net.hasor.neta.channel.transport.quic.rfc;

import org.junit.Test;

import net.hasor.neta.channel.transport.quic.QuicErrorCode;

/**
 * QUIC RFC 合规测试 — 传输层错误码常量（RFC 9000 §20.1、RFC 9001 §4.8）。
 * <p>
 * 本测试仅校验 {@link QuicErrorCode} 中常量的取值是否与 RFC 规范完全一致，
 * 目的是作为防回归护栏，避免后续误改常量值。
 * <h3>RFC 9000 §20.1 —— 传输层错误码分配</h3>
 * <table>
 *   <tr><th>Code</th><th>Name</th></tr>
 *   <tr><td>0x00</td><td>NO_ERROR</td></tr>
 *   <tr><td>0x01</td><td>INTERNAL_ERROR</td></tr>
 *   <tr><td>0x02</td><td>CONNECTION_REFUSED</td></tr>
 *   <tr><td>0x03</td><td>FLOW_CONTROL_ERROR</td></tr>
 *   <tr><td>0x04</td><td>STREAM_LIMIT_ERROR</td></tr>
 *   <tr><td>0x05</td><td>STREAM_STATE_ERROR</td></tr>
 *   <tr><td>0x06</td><td>FINAL_SIZE_ERROR</td></tr>
 *   <tr><td>0x07</td><td>FRAME_ENCODING_ERROR</td></tr>
 *   <tr><td>0x08</td><td>TRANSPORT_PARAMETER_ERROR</td></tr>
 *   <tr><td>0x09</td><td>CONNECTION_ID_LIMIT_ERROR</td></tr>
 *   <tr><td>0x0a</td><td>PROTOCOL_VIOLATION</td></tr>
 *   <tr><td>0x0b</td><td>INVALID_TOKEN</td></tr>
 *   <tr><td>0x0c</td><td>APPLICATION_ERROR</td></tr>
 *   <tr><td>0x0d</td><td>CRYPTO_BUFFER_EXCEEDED</td></tr>
 *   <tr><td>0x0e</td><td>KEY_UPDATE_ERROR</td></tr>
 *   <tr><td>0x0f</td><td>AEAD_LIMIT_REACHED</td></tr>
 *   <tr><td>0x10</td><td>NO_VIABLE_PATH</td></tr>
 *   <tr><td>0x0100–0x01ff</td><td>CRYPTO_ERROR range（TLS 告警映射）</td></tr>
 * </table>
 * <h3>RFC 9001 §4.8 —— CRYPTO_ERROR 映射规则</h3>
 * <blockquote>A TLS alert is converted into a QUIC connection error. The alert description is added
 * to 0x0100 to produce a QUIC error code from the range reserved for CRYPTO_ERROR.</blockquote>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCErrorCodesTest {

    // ════════════════════════════════════════════════════════════════════
    //  Q1: RFC 9000 §20.1 传输层错误码常量校验
    // ════════════════════════════════════════════════════════════════════

    /** Q1-1: 基础错误码（0x00 ~ 0x10）完全匹配 RFC 9000 §20.1。 */
    @Test
    public void testTransportErrorCodeValues() {
        assert QuicErrorCode.NO_ERROR == 0x00L : "NO_ERROR must be 0x00";
        assert QuicErrorCode.INTERNAL_ERROR == 0x01L : "INTERNAL_ERROR must be 0x01";
        assert QuicErrorCode.CONNECTION_REFUSED == 0x02L : "CONNECTION_REFUSED must be 0x02";
        assert QuicErrorCode.FLOW_CONTROL_ERROR == 0x03L : "FLOW_CONTROL_ERROR must be 0x03";
        assert QuicErrorCode.STREAM_LIMIT_ERROR == 0x04L : "STREAM_LIMIT_ERROR must be 0x04";
        assert QuicErrorCode.STREAM_STATE_ERROR == 0x05L : "STREAM_STATE_ERROR must be 0x05";
        assert QuicErrorCode.FINAL_SIZE_ERROR == 0x06L : "FINAL_SIZE_ERROR must be 0x06";
        assert QuicErrorCode.FRAME_ENCODING_ERROR == 0x07L : "FRAME_ENCODING_ERROR must be 0x07";
        assert QuicErrorCode.TRANSPORT_PARAMETER_ERROR == 0x08L : "TRANSPORT_PARAMETER_ERROR must be 0x08";
        assert QuicErrorCode.CONNECTION_ID_LIMIT_ERROR == 0x09L : "CONNECTION_ID_LIMIT_ERROR must be 0x09";
        assert QuicErrorCode.PROTOCOL_VIOLATION == 0x0aL : "PROTOCOL_VIOLATION must be 0x0a";
        assert QuicErrorCode.INVALID_TOKEN == 0x0bL : "INVALID_TOKEN must be 0x0b";
        assert QuicErrorCode.APPLICATION_ERROR == 0x0cL : "APPLICATION_ERROR must be 0x0c";
        assert QuicErrorCode.CRYPTO_BUFFER_EXCEEDED == 0x0dL : "CRYPTO_BUFFER_EXCEEDED must be 0x0d";
        assert QuicErrorCode.KEY_UPDATE_ERROR == 0x0eL : "KEY_UPDATE_ERROR must be 0x0e";
        assert QuicErrorCode.AEAD_LIMIT_REACHED == 0x0fL : "AEAD_LIMIT_REACHED must be 0x0f";
        assert QuicErrorCode.NO_VIABLE_PATH == 0x10L : "NO_VIABLE_PATH must be 0x10";
    }

    /** Q1-2: 所有传输层错误码彼此互异（避免常量定义事故）。 */
    @Test
    public void testTransportErrorCodeUniqueness() {
        long[] all = { QuicErrorCode.NO_ERROR, QuicErrorCode.INTERNAL_ERROR, QuicErrorCode.CONNECTION_REFUSED,//
                QuicErrorCode.FLOW_CONTROL_ERROR, QuicErrorCode.STREAM_LIMIT_ERROR, QuicErrorCode.STREAM_STATE_ERROR,//
                QuicErrorCode.FINAL_SIZE_ERROR, QuicErrorCode.FRAME_ENCODING_ERROR, QuicErrorCode.TRANSPORT_PARAMETER_ERROR,//
                QuicErrorCode.CONNECTION_ID_LIMIT_ERROR, QuicErrorCode.PROTOCOL_VIOLATION, QuicErrorCode.INVALID_TOKEN,//
                QuicErrorCode.APPLICATION_ERROR, QuicErrorCode.CRYPTO_BUFFER_EXCEEDED, QuicErrorCode.KEY_UPDATE_ERROR,//
                QuicErrorCode.AEAD_LIMIT_REACHED, QuicErrorCode.NO_VIABLE_PATH };
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assert all[i] != all[j] : "Transport error codes must be unique: index " + i + " collides with " + j;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Q2: RFC 9001 §4.8 CRYPTO_ERROR 映射（TLS alert → 0x0100 + alert）
    // ════════════════════════════════════════════════════════════════════

    /** Q2-1: CRYPTO_ERROR 基址为 0x0100，落在 RFC 9000 §20.1 保留区间的起点。 */
    @Test
    public void testCryptoErrorBase() {
        assert QuicErrorCode.CRYPTO_ERROR_BASE == 0x0100L : "CRYPTO_ERROR_BASE must be 0x0100 per RFC 9000 §20.1";
    }

    /**
     * Q2-2: {@code CRYPTO_ERROR_UNEXPECTED_MESSAGE} 必须等于 {@code CRYPTO_ERROR_BASE + 10}
     * （TLS 告警 {@code unexpected_message=10}），此错误码在 QUIC 收到 TLS KeyUpdate 时按
     * RFC 9001 §6 发送。
     */
    @Test
    public void testCryptoErrorUnexpectedMessageMapping() {
        long expected = QuicErrorCode.CRYPTO_ERROR_BASE + 10L;
        assert QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE == expected //
                : "CRYPTO_ERROR_UNEXPECTED_MESSAGE must equal CRYPTO_ERROR_BASE + 10 (TLS unexpected_message), got "//
                        + Long.toHexString(QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE);
        assert QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE == 0x010aL : "CRYPTO_ERROR_UNEXPECTED_MESSAGE must be 0x010a";
    }

    /** Q2-3: CRYPTO_ERROR 区间（0x0100–0x01ff）不与传输层错误码（0x00–0x10）重叠。 */
    @Test
    public void testCryptoErrorRangeSeparation() {
        assert QuicErrorCode.CRYPTO_ERROR_BASE > QuicErrorCode.NO_VIABLE_PATH //
                : "CRYPTO_ERROR range must not overlap transport error code range";
        assert QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE < QuicErrorCode.CRYPTO_ERROR_BASE + 256L //
                : "CRYPTO_ERROR_UNEXPECTED_MESSAGE must stay within the CRYPTO_ERROR range (+0..+255)";
    }
}
