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
package net.hasor.neta.channel.transport.quic;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * QuicSentPacketTracker 单元测试 — RFC 9002 §5 RTT 估算 / §6 丢包检测 / §6.2 PTO。
 * <p>
 * 因 {@link QuicSentPacketTracker} 是包私有类，测试必须位于同包
 * {@code net.hasor.neta.channel.quic} 下。
 * <p>
 * 测试项（SP-1 ~ SP-10）：
 * <ul>
 *   <li>SP-1 : 初始状态（smoothedRtt=333, rttVar=166, bytesInFlight=0）</li>
 *   <li>SP-2 : ack-eliciting 包计入 bytesInFlight，确认后减去</li>
 *   <li>SP-3 : 非 ack-eliciting 包不计入 bytesInFlight</li>
 *   <li>SP-4 : unackedCount 随发送/确认正确增减</li>
 *   <li>SP-5 : 首次 RTT 样本初始化 smoothedRtt（replaces 333 estimate）</li>
 *   <li>SP-6 : minRtt 在首次 ACK 后被更新（不再是 MAX_VALUE）</li>
 *   <li>SP-7 : PACKET_THRESHOLD=3 丢包检测：gap≥3 则声明丢失</li>
 *   <li>SP-8 : gap＜PACKET_THRESHOLD 时不误报丢包</li>
 *   <li>SP-9 : 范围 ACK 正确移除多个包，bytesInFlight 归零</li>
 *   <li>SP-10: 所有包确认后 PTO 定时器重置，isPtoExpired() = false</li>
 * </ul>
 */
public class QuicSentPacketTrackerTest {

    // ── SP-1: 初始状态 ────────────────────────────────────────────────────
    @Test
    public void testInitialValues() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        assertEquals("initial smoothedRtt should be 333 ms (RFC 9002 §6.2.2)", 333L, tracker.getSmoothedRtt());
        assertEquals("initial rttVar should be 166 ms (RTTVAR = SRTT/2)", 166L, tracker.getRttVar());
        assertEquals("initial bytesInFlight should be 0", 0L, tracker.getBytesInFlight());
        assertEquals("initial unackedCount should be 0", 0, tracker.getUnackedCount());
        assertFalse("PTO timer must not be expired on empty tracker", tracker.isPtoExpired());
    }

    // ── SP-2: ack-eliciting 包计入 / 减少 bytesInFlight ────────────────────
    @Test
    public void testBytesInFlightAckEliciting() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[] { 0x01 }, 100, true);
        assertEquals("after send PN0(100 B): bytesInFlight=100", 100L, tracker.getBytesInFlight());

        tracker.onPacketSent(1L, new byte[] { 0x02 }, 200, true);
        assertEquals("after send PN1(200 B): bytesInFlight=300", 300L, tracker.getBytesInFlight());

        // Ack only PN0; gap for PN0: largestAcked=0, PN0==largestAcked → just removed, no loss
        tracker.onAckReceived(Collections.singletonList(new long[] { 0L, 0L }));
        assertEquals("after ack PN0: bytesInFlight=200", 200L, tracker.getBytesInFlight());
    }

    // ── SP-3: 非 ack-eliciting 包不计入 bytesInFlight ─────────────────────
    @Test
    public void testBytesInFlightNonAckEliciting() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[] { 0x00 }, 500, false);
        assertEquals("non-ack-eliciting send: bytesInFlight must stay 0", 0L, tracker.getBytesInFlight());
    }

    // ── SP-4: unackedCount 随操作变化 ─────────────────────────────────────
    @Test
    public void testUnackedCountTracking() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[0], 50, true);
        tracker.onPacketSent(1L, new byte[0], 50, true);
        tracker.onPacketSent(2L, new byte[0], 50, true);
        assertEquals("3 sends: unackedCount=3", 3, tracker.getUnackedCount());

        // Ack PN2 only; gap(2-0)=2 < 3 and gap(2-1)=1 < 3 → no packet-threshold loss
        tracker.onAckReceived(Collections.singletonList(new long[] { 2L, 2L }));
        assertEquals("PN2 acked, PN0/PN1 remain: unackedCount=2", 2, tracker.getUnackedCount());
    }

    // ── SP-5: 首次 RTT 样本替换初始估算值 ──────────────────────────────────
    @Test
    public void testFirstRttSampleInitialization() throws Exception {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[0], 100, true);
        Thread.sleep(15); // ~15 ms observed RTT
        tracker.onAckReceived(Collections.singletonList(new long[] { 0L, 0L }));

        long srtt = tracker.getSmoothedRtt();
        long rvar = tracker.getRttVar();
        // First sample: smoothedRtt = rttSample, rttVar = rttSample/2
        assertTrue("first RTT sample replaces initial 333 ms estimate (srtt=" + srtt + ")", srtt != 333L);
        assertTrue("smoothedRtt must be positive after first sample", srtt > 0);
        assertEquals("rttVar = smoothedRtt / 2 after first sample", srtt / 2, rvar);
    }

    // ── SP-6: minRtt 在首次 ACK 后更新 ────────────────────────────────────
    @Test
    public void testMinRttTracking() throws Exception {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        // getMinRtt() returns smoothedRtt when no sample yet; after a sample it reflects real min
        long minBefore = tracker.getMinRtt();
        assertEquals("minRtt fallback to smoothedRtt (333) before any sample", 333L, minBefore);

        tracker.onPacketSent(0L, new byte[0], 100, true);
        Thread.sleep(10);
        tracker.onAckReceived(Collections.singletonList(new long[] { 0L, 0L }));

        long minAfter = tracker.getMinRtt();
        assertTrue("minRtt should reflect observed RTT (>0) after first ack", minAfter > 0);
        assertTrue("minRtt should be less than 1000 ms in test context", minAfter < 1000L);
    }

    // ── SP-7: PACKET_THRESHOLD=3 丢包声明 ─────────────────────────────────
    @Test
    public void testPacketThresholdLossDetection() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        byte[] payload0 = { 0x10, 0x20 };
        tracker.onPacketSent(0L, payload0, 100, true);
        tracker.onPacketSent(1L, new byte[] { 0x30 }, 100, true);
        tracker.onPacketSent(2L, new byte[] { 0x40 }, 100, true);
        tracker.onPacketSent(3L, new byte[] { 0x50 }, 100, true);

        // Ack PN3: largestAcked=3. Gap(3-0)=3 >= PACKET_THRESHOLD=3 → PN0 lost
        List<byte[]> lost = tracker.onAckReceived(Collections.singletonList(new long[] { 3L, 3L }));

        assertEquals("gap==PACKET_THRESHOLD: exactly 1 packet declared lost", 1, lost.size());
        assertArrayEquals("declared lost payload should be PN0 content", payload0, lost.get(0));
    }

    // ── SP-8: gap＜PACKET_THRESHOLD 时不误报 ──────────────────────────────
    @Test
    public void testNoFalseLossBeforeThreshold() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[] { 0x11 }, 100, true);
        tracker.onPacketSent(1L, new byte[] { 0x22 }, 100, true);
        tracker.onPacketSent(2L, new byte[] { 0x33 }, 100, true);

        // Ack PN2: gap(2-0)=2 < 3, gap(2-1)=1 < 3 → no packet-threshold loss
        List<byte[]> lost = tracker.onAckReceived(Collections.singletonList(new long[] { 2L, 2L }));

        assertEquals("gap < PACKET_THRESHOLD: no loss declared", 0, lost.size());
        assertEquals("PN0/PN1 still unacked after PN2 ack", 2, tracker.getUnackedCount());
    }

    // ── SP-9: 范围 ACK 确认多个包，bytesInFlight 归零 ─────────────────────
    @Test
    public void testRangeAckRemovesMultiplePackets() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        for (int i = 0; i < 5; i++) {
            tracker.onPacketSent(i, new byte[] { (byte) i }, 100, true);
        }
        assertEquals("5 sends: unackedCount=5", 5, tracker.getUnackedCount());
        assertEquals("5 × 100 B: bytesInFlight=500", 500L, tracker.getBytesInFlight());

        // Ack all 5 in one range [0,4]
        List<byte[]> lost = tracker.onAckReceived(Collections.singletonList(new long[] { 0L, 4L }));
        assertEquals("all acked in range: unackedCount=0", 0, tracker.getUnackedCount());
        assertEquals("all acked: bytesInFlight=0", 0L, tracker.getBytesInFlight());
        assertEquals("no loss when full range acked", 0, lost.size());
    }

    // ── SP-10: 全部已确认后 PTO 定时器重置 ────────────────────────────────
    @Test
    public void testPtoResetAfterAllAcked() {
        QuicSentPacketTracker tracker = new QuicSentPacketTracker();
        tracker.onPacketSent(0L, new byte[0], 100, true);
        // PTO timer is set (timer fires after ~997ms with initial estimates; don't wait)
        assertFalse("PTO not expired immediately after send", tracker.isPtoExpired());

        // Ack PN0 → sentPackets empty → ptoExpiry reset to 0
        tracker.onAckReceived(Collections.singletonList(new long[] { 0L, 0L }));
        assertFalse("PTO timer reset to 0 when all packets acked", tracker.isPtoExpired());
    }
}
