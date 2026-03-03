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
package net.hasor.neta.channel.quic.rfc;

import net.hasor.neta.channel.quic.QuicVarInt;
import org.junit.Test;

/**
 * QUIC RFC 合规测试 — 握手基础组件。
 * <p>
 * 覆盖握手和帧处理所依赖的底层编解码工具：
 * <ul>
 *   <li>§16 可变长整数 VarInt 编解码（A1-1 ~ A1-8）</li>
 * </ul>
 * <p>
 * 注意：ACK Tracker 和 Stream Reassembler 的单元测试位于同包测试类
 * {@code net.hasor.neta.channel.quic.QuicAckTrackerTest} 和
 * {@code net.hasor.neta.channel.quic.QuicStreamReassemblerTest}，
 * 因为被测类是包私有的。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCHandshakeTest {

    // ════════════════════════════════════════════════════════════════════
    //  A1: QuicVarInt — RFC 9000 §16 可变长整数
    // ════════════════════════════════════════════════════════════════════

    /** A1-1: 值 0、1、63 编码为 1 字节；首字节高 2 位 = 00。 */
    @Test
    public void testVarIntEncode1Byte() {
        for (long v : new long[] { 0, 1, 63 }) {
            byte[] enc = QuicVarInt.encode(v);
            assert enc.length == 1 : "Expected 1-byte encoding for " + v + ", got " + enc.length;
            assert (enc[0] & 0xC0) == 0x00 : "Top 2 bits must be 00 for 1-byte varint, value=" + v;
            long[] dec = QuicVarInt.decode(enc, 0);
            assert dec[0] == v : "Decode mismatch: expected " + v + ", got " + dec[0];
            assert dec[1] == 1 : "Consumed bytes should be 1, got " + dec[1];
        }
    }

    /** A1-2: 值 64、100、16383 编码为 2 字节；首字节高 2 位 = 01。 */
    @Test
    public void testVarIntEncode2Byte() {
        for (long v : new long[] { 64, 100, 16383 }) {
            byte[] enc = QuicVarInt.encode(v);
            assert enc.length == 2 : "Expected 2-byte encoding for " + v + ", got " + enc.length;
            assert (enc[0] & 0xC0) == 0x40 : "Top 2 bits must be 01 for 2-byte varint, value=" + v;
            long[] dec = QuicVarInt.decode(enc, 0);
            assert dec[0] == v : "Decode mismatch: expected " + v + ", got " + dec[0];
            assert dec[1] == 2 : "Consumed bytes should be 2, got " + dec[1];
        }
    }

    /** A1-3: 值 16384、500000、1073741823 编码为 4 字节；首字节高 2 位 = 10。 */
    @Test
    public void testVarIntEncode4Byte() {
        for (long v : new long[] { 16384, 500000, 1073741823L }) {
            byte[] enc = QuicVarInt.encode(v);
            assert enc.length == 4 : "Expected 4-byte encoding for " + v + ", got " + enc.length;
            assert (enc[0] & 0xC0) == 0x80 : "Top 2 bits must be 10 for 4-byte varint, value=" + v;
            long[] dec = QuicVarInt.decode(enc, 0);
            assert dec[0] == v : "Decode mismatch: expected " + v + ", got " + dec[0];
            assert dec[1] == 4 : "Consumed bytes should be 4, got " + dec[1];
        }
    }

    /** A1-4: 值 1073741824 编码为 8 字节；首字节高 2 位 = 11。 */
    @Test
    public void testVarIntEncode8Byte() {
        long v = 1073741824L;
        byte[] enc = QuicVarInt.encode(v);
        assert enc.length == 8 : "Expected 8-byte encoding for " + v + ", got " + enc.length;
        assert (enc[0] & 0xC0) == 0xC0 : "Top 2 bits must be 11 for 8-byte varint";
        long[] dec = QuicVarInt.decode(enc, 0);
        assert dec[0] == v : "Decode mismatch: expected " + v + ", got " + dec[0];
        assert dec[1] == 8 : "Consumed bytes should be 8, got " + dec[1];
    }

    /** A1-5: 边界值 0、63、64、16383、16384 编解码正确。 */
    @Test
    public void testVarIntBoundaryValues() {
        long[] boundaries = { 0, 63, 64, 16383, 16384, 1073741823L, 1073741824L };
        int[] expectedLens = { 1, 1, 2, 2, 4, 4, 8 };
        for (int i = 0; i < boundaries.length; i++) {
            byte[] enc = QuicVarInt.encode(boundaries[i]);
            assert enc.length == expectedLens[i] : "Boundary " + boundaries[i] + ": expected " + expectedLens[i] + " bytes, got " + enc.length;
            long[] dec = QuicVarInt.decode(enc, 0);
            assert dec[0] == boundaries[i] : "Boundary roundtrip failed for " + boundaries[i] + ", got " + dec[0];
        }
    }

    /** A1-6: decode(encode(v)) 对所有范围返回原始值，且 consumed 与编码长度一致。 */
    @Test
    public void testVarIntRoundTrip() {
        long[] values = { 0, 1, 42, 63, 64, 255, 1000, 16383, 16384, 65535, 500000, 1073741823L, 1073741824L, 4611686018427387903L };
        for (long v : values) {
            byte[] enc = QuicVarInt.encode(v);
            long[] dec = QuicVarInt.decode(enc, 0);
            assert dec[0] == v : "RoundTrip failed: encode→decode(" + v + ") = " + dec[0];
            assert dec[1] == enc.length : "Consumed bytes (" + dec[1] + ") != encode length (" + enc.length + ") for " + v;
        }
    }

    /** A1-7: encodedLength(v) 与 encode(v).length 结果一致。 */
    @Test
    public void testVarIntEncodedLength() {
        long[] values = { 0, 1, 63, 64, 16383, 16384, 1073741823L, 1073741824L, 4611686018427387903L };
        for (long v : values) {
            int predicted = QuicVarInt.encodedLength(v);
            int actual = QuicVarInt.encode(v).length;
            assert predicted == actual : "encodedLength(" + v + ")=" + predicted + " but encode().length=" + actual;
        }
    }

    /** A1-8: encode(-1) 抛出 IllegalArgumentException。 */
    @Test(expected = IllegalArgumentException.class)
    public void testVarIntEncodeNegativeThrows() {
        QuicVarInt.encode(-1);
    }
}
