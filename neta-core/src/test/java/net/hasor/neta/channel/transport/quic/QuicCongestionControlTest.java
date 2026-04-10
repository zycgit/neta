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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * QuicCongestionControl 单元测试 — RFC 9002 §7 NewReno 拥塞控制。
 * <p>
 * 因 {@link QuicCongestionControl} 是包私有类，测试必须位于同包
 * {@code net.hasor.neta.channel.quic} 下。
 * <p>
 * 测试项（CC-1 ~ CC-12）：
 * <ul>
 *   <li>CC-1 : INITIAL_WINDOW = 14720 B（≈10 × 1472 MTU），初始状态 SLOW_START</li>
 *   <li>CC-2 : 慢启动阶段 cwnd += ackedBytes（RFC 9002 §7.3.1）</li>
 *   <li>CC-3 : cwnd ≥ ssthresh 时进入拥塞避免（CONGESTION_AVOIDANCE）</li>
 *   <li>CC-4 : 拥塞避免阶段 cwnd += MAX_DATAGRAM_SIZE * ackedBytes / cwnd（加法增加）</li>
 *   <li>CC-5 : 丢包进入 RECOVERY，ssthresh = max(cwnd/2, MINIMUM_WINDOW)</li>
 *   <li>CC-6 : MINIMUM_WINDOW = 2 × 1472 = 2944 B，cwnd 不低于此值</li>
 *   <li>CC-7 : 已处于 RECOVERY 时再次丢包不重复削减 cwnd</li>
 *   <li>CC-8 : PN > recoveryStartPn 的确认触发退出 RECOVERY → CONGESTION_AVOIDANCE</li>
 *   <li>CC-9 : 持续拥塞：cwnd 重置为 MINIMUM_WINDOW，回到 SLOW_START</li>
 *   <li>CC-10: reset() 恢复全部初始状态</li>
 *   <li>CC-11: canSend(bytesInFlight) 正确门控（< cwnd 允许，>= cwnd 拒绝）</li>
 *   <li>CC-12: ECN-CE 信号视同丢包进入 RECOVERY（RFC 9002 §7.1）</li>
 * </ul>
 */
public class QuicCongestionControlTest {

    // ── CC-1: 初始窗口与初始状态 ───────────────────────────────────────────
    @Test
    public void testInitialWindowAndState() {
        QuicCongestionControl cc = new QuicCongestionControl();
        assertEquals("INITIAL_WINDOW constant must be 14720 B", 14720L, QuicCongestionControl.INITIAL_WINDOW);
        assertEquals("MINIMUM_WINDOW constant must be 2944 B", 2944L, QuicCongestionControl.MINIMUM_WINDOW);
        assertEquals("MAX_DATAGRAM_SIZE constant must be 1472 B", 1472L, QuicCongestionControl.MAX_DATAGRAM_SIZE);
        assertEquals("initial cwnd = INITIAL_WINDOW", QuicCongestionControl.INITIAL_WINDOW, cc.getCwnd());
        assertEquals("initial ssthresh = Long.MAX_VALUE", Long.MAX_VALUE, cc.getSsthresh());
        assertEquals("initial state = SLOW_START", QuicCongestionControl.State.SLOW_START, cc.getState());
    }

    // ── CC-2: 慢启动阶段 cwnd 线性增长 ─────────────────────────────────────
    @Test
    public void testSlowStartCwndGrowth() {
        QuicCongestionControl cc = new QuicCongestionControl();
        long cwndBefore = cc.getCwnd();   // 14720
        cc.onPacketsAcked(1000L, 0L);
        assertEquals("slow start: cwnd += ackedBytes (1000)", cwndBefore + 1000L, cc.getCwnd());
        assertEquals("still in SLOW_START (cwnd < MAX_VALUE)", QuicCongestionControl.State.SLOW_START, cc.getState());
    }

    // ── CC-3: cwnd ≥ ssthresh 触发进入拥塞避免 ────────────────────────────
    @Test
    public void testSlowStartToAvoidanceTransition() {
        QuicCongestionControl cc = new QuicCongestionControl();
        // First set ssthresh via a loss event (cwnd=14720 → ssthresh=7360, enter RECOVERY)
        cc.onPacketLost(0L);
        // Exit recovery by acking PN > recoveryStartPn (0)
        cc.onPacketsAcked(1L, 1L);
        assertEquals("exit RECOVERY → CONGESTION_AVOIDANCE", QuicCongestionControl.State.CONGESTION_AVOIDANCE, cc.getState());
        // ssthresh should be 7360 now; any further ack should keep CA
        long cwndCA = cc.getCwnd();
        cc.onPacketsAcked(100L, 2L);
        // In CA: cwnd += 1472 * 100 / cwnd
        long expectedIncrease = QuicCongestionControl.MAX_DATAGRAM_SIZE * 100L / cwndCA;
        assertEquals("CA: additive increase applied", cwndCA + expectedIncrease, cc.getCwnd());
    }

    // ── CC-4: 拥塞避免阶段加法增加公式 ────────────────────────────────────
    @Test
    public void testCongestionAvoidanceFormula() {
        QuicCongestionControl cc = new QuicCongestionControl();
        // Enter RECOVERY via loss (cwnd=14720 → ssthresh=7360, cwnd=7360)
        cc.onPacketLost(0L);
        assertEquals("after loss: cwnd = ssthresh = max(14720/2, 2944) = 7360", 7360L, cc.getCwnd());
        // Exit recovery: ack PN 1 (> recoveryStartPn=0)
        cc.onPacketsAcked(1L, 1L);
        assertEquals("exit recovery: cwnd unchanged at 7360", 7360L, cc.getCwnd());
        assertEquals("state = CONGESTION_AVOIDANCE", QuicCongestionControl.State.CONGESTION_AVOIDANCE, cc.getState());

        // CA formula: cwnd += MAX_DATAGRAM_SIZE * ackedBytes / cwnd
        // ackedBytes=1000: increase = 1472 * 1000 / 7360 = 200
        long increase = QuicCongestionControl.MAX_DATAGRAM_SIZE * 1000L / 7360L;
        assertEquals("1472 * 1000 / 7360 = 200", 200L, increase);
        cc.onPacketsAcked(1000L, 2L);
        assertEquals("CA cwnd = 7360 + 200 = 7560", 7360L + 200L, cc.getCwnd());
    }

    // ── CC-5: 丢包进入 RECOVERY，ssthresh / cwnd 正确削减 ─────────────────
    @Test
    public void testLossEntersRecovery() {
        QuicCongestionControl cc = new QuicCongestionControl();
        long beforeCwnd = cc.getCwnd();   // 14720
        cc.onPacketLost(5L);

        long expectedSsthresh = Math.max(beforeCwnd / 2, QuicCongestionControl.MINIMUM_WINDOW);
        assertEquals("ssthresh = max(cwnd/2, MINIMUM_WINDOW)", expectedSsthresh, cc.getSsthresh());
        assertEquals("cwnd = ssthresh after loss", expectedSsthresh, cc.getCwnd());
        assertEquals("state = RECOVERY after loss", QuicCongestionControl.State.RECOVERY, cc.getState());
    }

    // ── CC-6: MINIMUM_WINDOW 下限保护 ─────────────────────────────────────
    @Test
    public void testMinimumWindowEnforced() {
        QuicCongestionControl cc = new QuicCongestionControl();
        // Repeatedly halve cwnd until minimum kicks in
        // Cycle 1: cwnd=14720 → loss → cwnd=7360
        cc.onPacketLost(0L);
        cc.onPacketsAcked(1L, 1L); // exit recovery
        // Cycle 2: cwnd=7360 → loss → cwnd=3680
        cc.onPacketLost(2L);
        cc.onPacketsAcked(1L, 3L); // exit recovery
        // Cycle 3: cwnd=3680 → loss → max(1840, 2944)=2944
        cc.onPacketLost(4L);
        assertEquals("cwnd must not go below MINIMUM_WINDOW (2944)", QuicCongestionControl.MINIMUM_WINDOW, cc.getCwnd());
        assertEquals("ssthresh also capped at MINIMUM_WINDOW", QuicCongestionControl.MINIMUM_WINDOW, cc.getSsthresh());
    }

    // ── CC-7: 已在 RECOVERY 时再丢包不重复削减 ────────────────────────────
    @Test
    public void testNoDoubleReductionInRecovery() {
        QuicCongestionControl cc = new QuicCongestionControl();
        cc.onPacketLost(0L);
        long cwndAfterFirstLoss = cc.getCwnd();      // 7360
        long ssthreshAfterFirstLoss = cc.getSsthresh(); // 7360

        // Second loss while already in RECOVERY must be ignored
        cc.onPacketLost(1L);
        assertEquals("cwnd unchanged during RECOVERY", cwndAfterFirstLoss, cc.getCwnd());
        assertEquals("ssthresh unchanged during RECOVERY", ssthreshAfterFirstLoss, cc.getSsthresh());
        assertEquals("still in RECOVERY", QuicCongestionControl.State.RECOVERY, cc.getState());
    }

    // ── CC-8: PN > recoveryStartPn 的确认退出 RECOVERY ────────────────────
    @Test
    public void testRecoveryExit() {
        QuicCongestionControl cc = new QuicCongestionControl();
        cc.onPacketLost(10L);  // recoveryStartPn = 10
        assertEquals("enters RECOVERY", QuicCongestionControl.State.RECOVERY, cc.getState());

        // Ack PN 10 (== recoveryStartPn): should NOT exit recovery
        cc.onPacketsAcked(100L, 10L);
        assertEquals("ack at recoveryStartPn: stay RECOVERY", QuicCongestionControl.State.RECOVERY, cc.getState());

        // Ack PN 11 (> recoveryStartPn): exits recovery
        cc.onPacketsAcked(100L, 11L);
        assertEquals("ack past recoveryStartPn: exit to CA", QuicCongestionControl.State.CONGESTION_AVOIDANCE, cc.getState());
    }

    // ── CC-9: 持续拥塞重置 cwnd 到 MINIMUM_WINDOW ─────────────────────────
    @Test
    public void testPersistentCongestion() {
        QuicCongestionControl cc = new QuicCongestionControl();
        cc.onPacketLost(0L); // enter RECOVERY first
        cc.onPersistentCongestion();

        assertEquals("persistent congestion: cwnd = MINIMUM_WINDOW", QuicCongestionControl.MINIMUM_WINDOW, cc.getCwnd());
        assertEquals("persistent congestion: ssthresh = cwnd", QuicCongestionControl.MINIMUM_WINDOW, cc.getSsthresh());
        assertEquals("persistent congestion: reset to SLOW_START", QuicCongestionControl.State.SLOW_START, cc.getState());
    }

    // ── CC-10: reset() 恢复全部初始状态 ────────────────────────────────────
    @Test
    public void testReset() {
        QuicCongestionControl cc = new QuicCongestionControl();
        cc.onPacketsAcked(5000L, 0L);
        cc.onPacketLost(1L);
        // Now state is dirty; reset it
        cc.reset();

        assertEquals("reset: cwnd = INITIAL_WINDOW", QuicCongestionControl.INITIAL_WINDOW, cc.getCwnd());
        assertEquals("reset: ssthresh = Long.MAX_VALUE", Long.MAX_VALUE, cc.getSsthresh());
        assertEquals("reset: state = SLOW_START", QuicCongestionControl.State.SLOW_START, cc.getState());
        assertTrue("reset: canSend(0) must be true", cc.canSend(0L));
    }

    // ── CC-11: canSend() 正确门控发送 ─────────────────────────────────────
    @Test
    public void testCanSend() {
        QuicCongestionControl cc = new QuicCongestionControl();
        long cwnd = cc.getCwnd(); // 14720

        assertTrue("canSend(0): bytesInFlight=0 < cwnd → allowed", cc.canSend(0L));
        assertTrue("canSend(cwnd-1): just below limit → allowed", cc.canSend(cwnd - 1));
        assertFalse("canSend(cwnd): bytesInFlight == cwnd → blocked", cc.canSend(cwnd));
        assertFalse("canSend(cwnd+1): exceeds cwnd → blocked", cc.canSend(cwnd + 1));
    }

    // ── CC-12: ECN-CE 信号视同丢包处理 ─────────────────────────────────────
    @Test
    public void testEcnCongestionTreatedAsLoss() {
        QuicCongestionControl cc = new QuicCongestionControl();
        long cwndBefore = cc.getCwnd(); // 14720

        // ECN-CE event: new ceCount=1, packet PN=7
        cc.onEcnCongestion(1L, 7L);
        assertEquals("ECN-CE enters RECOVERY", QuicCongestionControl.State.RECOVERY, cc.getState());
        assertEquals("ECN-CE: ssthresh = max(cwnd/2, MINIMUM_WINDOW)", Math.max(cwndBefore / 2, QuicCongestionControl.MINIMUM_WINDOW), cc.getSsthresh());

        // Duplicate ECN signal (ceCount <= stored 1) should be ignored
        long cwndAfterFirst = cc.getCwnd();
        cc.onEcnCongestion(1L, 8L);
        assertEquals("duplicate ECN-CE: cwnd unchanged", cwndAfterFirst, cc.getCwnd());
    }
}
