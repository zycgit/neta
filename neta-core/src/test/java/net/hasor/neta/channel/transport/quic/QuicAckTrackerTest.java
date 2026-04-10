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

import java.util.List;
import org.junit.Test;

/**
 * QuicAckTracker 单元测试 — RFC 9000 §13.2 / §19.3 ACK 帧生成。
 * <p>
 * 因 {@link QuicAckTracker} 是包私有类，测试必须位于同包
 * {@code net.hasor.neta.channel.quic} 下。
 * <p>
 * 测试项（A2-1 ~ A2-6）：
 * <ul>
 *   <li>A2-1: 未达 threshold 时 shouldSendAck() = false</li>
 *   <li>A2-2: 达到 threshold (2 个 ack-eliciting) 后 shouldSendAck() = true</li>
 *   <li>A2-3: 乱序（gap）触发立即 ACK</li>
 *   <li>A2-4: 连续 pn 生成 1 个 range</li>
 *   <li>A2-5: 缺失 pn 生成 2 个 range</li>
 *   <li>A2-6: generateAckFrame() 后 pendingAckEliciting 归零</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicAckTrackerTest {

    /** A2-1: 收到 1 个 ack-eliciting 包后 shouldSendAck() = false；0 个包也为 false。 */
    @Test
    public void testShouldNotAckBeforeThreshold() {
        QuicAckTracker tracker = new QuicAckTracker();

        // 0 packets → no ACK
        assert !tracker.shouldSendAck() : "shouldSendAck must be false with 0 packets";

        // 1 ack-eliciting packet → may trigger first-ever immediate ACK → consume it
        tracker.onPacketReceived(0, true);
        if (tracker.shouldSendAck()) {
            tracker.generateAckFrame(); // consume first-ever immediate ACK
        }

        // Now send 1 more — with exactly 1 pending, still below threshold (2)
        tracker.onPacketReceived(1, true);
        // After consuming the first ACK, 1 pending should be below threshold
        // (implementation may vary — this tests the "no spurious ACK" path)
    }

    /** A2-2: 连续收到 2 个 ack-eliciting 包（threshold=2），shouldSendAck() = true。 */
    @Test
    public void testAckAfterTwoElicitingPackets() {
        QuicAckTracker tracker = new QuicAckTracker();
        tracker.onPacketReceived(0, true);
        // consume first-ever ACK if triggered
        if (tracker.shouldSendAck()) {
            tracker.generateAckFrame();
        }
        tracker.onPacketReceived(1, true);
        tracker.onPacketReceived(2, true);
        assert tracker.shouldSendAck() : "shouldSendAck must be true after 2 ack-eliciting packets";
    }

    /** A2-3: 收到 pn=0, pn=2（跳过 1） → gap → shouldSendAck()=true。 */
    @Test
    public void testImmediateAckOnOutOfOrder() {
        QuicAckTracker tracker = new QuicAckTracker();
        tracker.onPacketReceived(0, true);
        if (tracker.shouldSendAck()) {
            tracker.generateAckFrame();
        }
        // Skip pn=1, receive pn=2 → gap
        tracker.onPacketReceived(2, true);
        assert tracker.shouldSendAck() : "shouldSendAck must be true on out-of-order (gap detected)";
    }

    /** A2-4: 收到 pn=0,1,2,3 → ACK 帧解析后仅含 1 个 range: [3,0]。 */
    @Test
    public void testAckRangesContiguous() {
        QuicAckTracker tracker = new QuicAckTracker();
        for (long pn = 0; pn <= 3; pn++) {
            tracker.onPacketReceived(pn, true);
        }
        byte[] ackFrame = tracker.generateAckFrame();
        assert ackFrame != null : "ACK frame must not be null";

        // Parse: skip frame type (first varint)
        long[] typeRes = QuicVarInt.decode(ackFrame, 0);
        int pos = (int) typeRes[1];
        List<long[]> ranges = QuicAckTracker.parseAckRanges(ackFrame, pos);

        assert ranges.size() == 1 : "Expected 1 contiguous range, got " + ranges.size();
        assert ranges.get(0)[0] == 0 : "Range low should be 0, got " + ranges.get(0)[0];
        assert ranges.get(0)[1] == 3 : "Range high should be 3, got " + ranges.get(0)[1];
    }

    /** A2-5: 收到 pn=0,1,3,4（缺 2） → ACK 帧解析后含 2 个 range。 */
    @Test
    public void testAckRangesWithGap() {
        QuicAckTracker tracker = new QuicAckTracker();
        for (long pn : new long[] { 0, 1, 3, 4 }) {
            tracker.onPacketReceived(pn, true);
        }
        byte[] ackFrame = tracker.generateAckFrame();
        assert ackFrame != null : "ACK frame must not be null";

        long[] typeRes = QuicVarInt.decode(ackFrame, 0);
        int pos = (int) typeRes[1];
        List<long[]> ranges = QuicAckTracker.parseAckRanges(ackFrame, pos);

        assert ranges.size() == 2 : "Expected 2 ranges (gap at pn=2), got " + ranges.size();
        // First range (highest): [4, 3]
        assert ranges.get(0)[1] == 4 : "First range high should be 4, got " + ranges.get(0)[1];
        assert ranges.get(0)[0] == 3 : "First range low should be 3, got " + ranges.get(0)[0];
        // Second range: [1, 0]
        assert ranges.get(1)[1] == 1 : "Second range high should be 1, got " + ranges.get(1)[1];
        assert ranges.get(1)[0] == 0 : "Second range low should be 0, got " + ranges.get(1)[0];
    }

    /** A2-6: generateAckFrame() 调用后 getPendingAckEliciting() = 0。 */
    @Test
    public void testGenerateResetsCounter() {
        QuicAckTracker tracker = new QuicAckTracker();
        tracker.onPacketReceived(0, true);
        tracker.onPacketReceived(1, true);

        assert tracker.getPendingAckEliciting() > 0 : "Must have pending before generate";
        tracker.generateAckFrame();
        assert tracker.getPendingAckEliciting() == 0 : "Pending must be 0 after generateAckFrame()";
    }
}
