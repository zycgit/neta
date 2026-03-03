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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * QuicFlowControl 单元测试 — RFC 9000 §4 流量控制 / §19.9 MAX_DATA / §19.10 MAX_STREAM_DATA。
 * <p>
 * 因 {@link QuicFlowControl} 是包私有类，测试必须位于同包
 * {@code net.hasor.neta.channel.quic} 下。
 * <p>
 * 测试项（FC-1 ~ FC-11）：
 * <ul>
 *   <li>FC-1 : 连接级数据在限额内正常接收（onConnectionDataReceived → true）</li>
 *   <li>FC-2 : 累计接收超出 MAX_DATA → 返回 false（违规检测）</li>
 *   <li>FC-3 : 流级数据在限额内（validateStreamData → true）</li>
 *   <li>FC-4 : 流级数据超出 MAX_STREAM_DATA → 返回 false</li>
 *   <li>FC-5 : 接收量低于 50% 时不扩展连接窗口（返回 -1）</li>
 *   <li>FC-6 : 接收量达 50% 时扩展连接窗口（返回新 MAX_DATA = 旧 × 2）</li>
 *   <li>FC-7 : 流级窗口不足 50% 不扩展（返回 -1）</li>
 *   <li>FC-8 : 流级窗口达 50% 时扩展（返回 streamMaxData × 2）</li>
 *   <li>FC-9 : buildMaxDataFrame 帧类型字节 = 0x10，小值完整帧验证</li>
 *   <li>FC-10: buildMaxStreamDataFrame 帧类型字节 = 0x11，小值完整帧验证</li>
 *   <li>FC-11: getters 与 updateConnectionMaxData（仅允许增大）</li>
 * </ul>
 */
public class QuicFlowControlTest {

    // ── FC-1: 连接级接收在限额内 ──────────────────────────────────────────
    @Test
    public void testConnectionDataWithinLimit() {
        QuicFlowControl fc = new QuicFlowControl(1000L);
        assertTrue("100 B received, limit=1000: within limit", fc.onConnectionDataReceived(100L));
        assertEquals("connectionBytesReceived should be 100", 100L, fc.getConnectionBytesReceived());
        assertEquals("connectionMaxData unchanged", 1000L, fc.getConnectionMaxData());
    }

    // ── FC-2: 累计超出 MAX_DATA → 违规 ────────────────────────────────────
    @Test
    public void testConnectionDataViolation() {
        QuicFlowControl fc = new QuicFlowControl(1000L);
        assertTrue("600 B: within limit", fc.onConnectionDataReceived(600L));
        assertFalse("600+500=1100 B: exceeds MAX_DATA(1000) → violation", fc.onConnectionDataReceived(500L));
        assertEquals("total received = 1100 (violation recorded)", 1100L, fc.getConnectionBytesReceived());
    }

    // ── FC-3: 流级数据在限额内 ────────────────────────────────────────────
    @Test
    public void testValidateStreamDataValid() {
        QuicFlowControl fc = new QuicFlowControl(10000L);
        // offset=100, length=200 → totalStreamBytes=300 ≤ streamMaxData=500 → valid
        assertTrue("offset+length=300 ≤ streamMaxData=500: valid", fc.validateStreamData(100L, 200L, 500L));
        // Boundary: offset+length == streamMaxData
        assertTrue("offset+length == streamMaxData: valid (boundary)", fc.validateStreamData(0L, 500L, 500L));
    }

    // ── FC-4: 流级数据超出 MAX_STREAM_DATA → 违规 ─────────────────────────
    @Test
    public void testValidateStreamDataViolation() {
        QuicFlowControl fc = new QuicFlowControl(10000L);
        // offset=400, length=200 → totalStreamBytes=600 > streamMaxData=500 → violation
        assertFalse("offset+length=600 > streamMaxData=500: violation", fc.validateStreamData(400L, 200L, 500L));
        // Zero limit edge-case: any non-zero data violates
        assertFalse("streamMaxData=0: any data is a violation", fc.validateStreamData(0L, 1L, 0L));
    }

    // ── FC-5: 接收量低于 50% 时不自动扩展连接窗口 ────────────────────────
    @Test
    public void testConnectionWindowNoExpansionBelow50Pct() {
        QuicFlowControl fc = new QuicFlowControl(1000L);
        fc.onConnectionDataReceived(400L);  // 400/1000 = 40% < 50%
        assertEquals("below 50%: shouldExpandConnectionWindow returns -1", -1L, fc.shouldExpandConnectionWindow());
        assertEquals("connectionMaxData should be unchanged", 1000L, fc.getConnectionMaxData());
    }

    // ── FC-6: 接收量达 50% 时自动扩展连接窗口 ────────────────────────────
    @Test
    public void testConnectionWindowExpandAt50Pct() {
        QuicFlowControl fc = new QuicFlowControl(1000L);
        fc.onConnectionDataReceived(500L);  // 500/1000 = 50% >= 50%
        long newMax = fc.shouldExpandConnectionWindow();
        assertEquals("at 50%: new MAX_DATA = 1000 × 2 = 2000", 2000L, newMax);
        assertEquals("getConnectionMaxData() updated to 2000", 2000L, fc.getConnectionMaxData());
    }

    // ── FC-7: 流级窗口低于 50% 不扩展 ────────────────────────────────────
    @Test
    public void testStreamWindowNoExpansionBelow50Pct() {
        QuicFlowControl fc = new QuicFlowControl(10000L);
        // streamBytesReceived=400, streamMaxData=1000 → 40% < 50% → -1
        assertEquals("stream below 50%: shouldExpandStreamWindow returns -1", -1L, fc.shouldExpandStreamWindow(400L, 1000L));
    }

    // ── FC-8: 流级窗口达 50% 时扩展 ──────────────────────────────────────
    @Test
    public void testStreamWindowExpandAt50Pct() {
        QuicFlowControl fc = new QuicFlowControl(10000L);
        // streamBytesReceived=500, streamMaxData=1000 → 50% >= 50% → 2000
        long newMax = fc.shouldExpandStreamWindow(500L, 1000L);
        assertEquals("stream at 50%: new limit = 1000 × 2 = 2000", 2000L, newMax);

        // Zero limit: returns -1 (guard against divide-by-zero)
        assertEquals("streamMaxData=0: returns -1", -1L, fc.shouldExpandStreamWindow(100L, 0L));
    }

    // ── FC-9: buildMaxDataFrame 帧格式（RFC 9000 §19.9） ──────────────────
    @Test
    public void testBuildMaxDataFrame() {
        // MAX_DATA type = 0x10 (single VarInt byte, 16 < 64)
        // For maxData=0: VarInt(0) = [0x00]; full frame = [0x10, 0x00]
        byte[] frame = QuicFlowControl.buildMaxDataFrame(0L);
        assertEquals("MAX_DATA frame for maxData=0: length=2", 2, frame.length);
        assertEquals("first byte is frame type 0x10", 0x10, frame[0] & 0xFF);
        assertEquals("second byte encodes maxData=0 as VarInt [0x00]", 0x00, frame[1] & 0xFF);

        // For maxData=32 (< 64 → single VarInt byte): frame = [0x10, 0x20]
        byte[] frame32 = QuicFlowControl.buildMaxDataFrame(32L);
        assertEquals("MAX_DATA(32): frame type byte", 0x10, frame32[0] & 0xFF);
        assertEquals("MAX_DATA(32): value byte = 0x20", 0x20, frame32[1] & 0xFF);

        // Frame must always start with type 0x10 regardless of value
        byte[] frameLarge = QuicFlowControl.buildMaxDataFrame(16383L); // max 2-byte VarInt value
        assertEquals("large maxData: type byte still 0x10", 0x10, frameLarge[0] & 0xFF);
        assertTrue("large maxData: frame length > 2", frameLarge.length > 2);
    }

    // ── FC-10: buildMaxStreamDataFrame 帧格式（RFC 9000 §19.10） ───────────
    @Test
    public void testBuildMaxStreamDataFrame() {
        // MAX_STREAM_DATA type = 0x11
        // For streamId=1, maxStreamData=0: frame = [0x11, 0x01, 0x00]
        byte[] frame = QuicFlowControl.buildMaxStreamDataFrame(1L, 0L);
        assertEquals("MAX_STREAM_DATA frame: length=3 for small ids+limit", 3, frame.length);
        assertEquals("first byte is frame type 0x11", 0x11, frame[0] & 0xFF);
        assertEquals("second byte encodes streamId=1 as VarInt [0x01]", 0x01, frame[1] & 0xFF);
        assertEquals("third byte encodes maxStreamData=0 as VarInt [0x00]", 0x00, frame[2] & 0xFF);

        // Verify via round-trip: type byte, streamId byte, limit byte for streamId=0, limit=63
        byte[] frame2 = QuicFlowControl.buildMaxStreamDataFrame(0L, 63L);
        assertEquals("type byte = 0x11", 0x11, frame2[0] & 0xFF);
        assertEquals("streamId=0 encoded as [0x00]", 0x00, frame2[1] & 0xFF);
        assertEquals("limit=63 encoded as [0x3f] (max 1-byte VarInt)", 0x3f, frame2[2] & 0xFF);
    }

    // ── FC-11: getter 与 updateConnectionMaxData ──────────────────────────
    @Test
    public void testGettersAndUpdateMaxData() {
        QuicFlowControl fc = new QuicFlowControl(1000L);
        assertEquals("getConnectionMaxData() = 1000", 1000L, fc.getConnectionMaxData());
        assertEquals("getConnectionBytesReceived() = 0 initially", 0L, fc.getConnectionBytesReceived());

        fc.onConnectionDataReceived(200L);
        assertEquals("after receiving 200: getConnectionBytesReceived() = 200", 200L, fc.getConnectionBytesReceived());

        // updateConnectionMaxData only goes up (max semantics)
        fc.updateConnectionMaxData(2000L);
        assertEquals("update to larger value: maxData = 2000", 2000L, fc.getConnectionMaxData());

        fc.updateConnectionMaxData(500L); // attempt to shrink
        assertEquals("attempt to shrink: maxData stays at 2000 (max semantics)", 2000L, fc.getConnectionMaxData());
    }
}
