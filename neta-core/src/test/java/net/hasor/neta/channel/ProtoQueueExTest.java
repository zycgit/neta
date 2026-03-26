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
package net.hasor.neta.channel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ReferenceHolder;
import org.junit.Test;

/**
 * Extended tests for {@link ProtoQueue} covering edge cases not in ProtoQueueTest.
 * @author test
 */
public class ProtoQueueExTest {

    // --- capacity and slot tests ---

    @Test
    public void negativeCapacity_meansUnlimited() {
        ProtoQueue<Integer> q = new ProtoQueue<>(-1);
        assert q.getCapacity() == Integer.MAX_VALUE;
        assert q.slotSize() > 0;
    }

    @Test
    public void zeroCapacity_noSlot() {
        ProtoQueue<Integer> q = new ProtoQueue<>(0);
        assert q.getCapacity() == 0;
        assert q.slotSize() == 0;
        // offer should accept 0 items
        assert !q.offerMessage(1);
        assert q.offerMessage(Arrays.asList(1, 2, 3)) == 0;
    }

    @Test
    public void exactCapacity_fillAndOverflow() {
        ProtoQueue<Integer> q = new ProtoQueue<>(3);
        assert q.offerMessage(1);
        assert q.offerMessage(2);
        assert q.offerMessage(3);
        // slot full, cannot add more
        assert !q.offerMessage(4);
        assert q.slotSize() == 0;
        assert q.queueSize() == 3;
        assert q.slotSize() == 0;
    }

    // --- peek tests ---

    @Test
    public void peekMessage_doesNotConsume() {
        ProtoQueue<String> q = new ProtoQueue<>(10);
        q.offerMessage("a");
        q.offerMessage("b");
        q.offerMessage("c");

        List<String> peeked = q.peekMessage(2);
        assert peeked.size() == 2;
        assert peeked.get(0).equals("a");
        assert peeked.get(1).equals("b");
        // queue size unchanged
        assert q.queueSize() == 3;

        // peek again returns same items
        List<String> peeked2 = q.peekMessage(2);
        assert peeked2.size() == 2;
        assert peeked2.get(0).equals("a");
    }

    @Test
    public void peekMessage_negativeCount_returnsAll() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);

        List<Integer> all = q.peekMessage(-1);
        assert all.size() == 2;
    }

    @Test
    public void peekMessage_returnsDefensiveCopy() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);

        List<Integer> peeked = q.peekMessage(2);
        peeked.clear(); // mutating the returned list

        // original queue should not be affected
        assert q.queueSize() == 2;
        List<Integer> peeked2 = q.peekMessage(2);
        assert peeked2.size() == 2;
    }

    @Test
    public void peekSingle_defaultMethod() {
        ProtoQueue<String> q = new ProtoQueue<>(10);
        q.offerMessage("hello");

        String val = q.peekMessage();
        assert "hello".equals(val);
        assert q.queueSize() == 1;
    }

    @Test
    public void peekSingle_emptyQueue_returnsNull() {
        ProtoQueue<String> q = new ProtoQueue<>(10);
        assert q.peekMessage() == null;
    }

    // --- skip tests ---

    @Test
    public void skipMessage_basic() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);
        q.offerMessage(3);

        q.skipMessage(2);
        assert q.queueSize() == 1;
        Integer val = q.takeMessage();
        assert val == 3;
    }

    @Test
    public void skipMessage_moreThanAvailable() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);

        q.skipMessage(100);
        assert q.queueSize() == 0;
    }

    // --- take tests ---

    @Test
    public void takeMessage_zeroCount_emptyList() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);

        List<Integer> taken = q.takeMessage(0);
        assert taken.isEmpty();
        assert q.queueSize() == 1;
    }

    @Test
    public void takeMessage_negativeCount_takesAll() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);
        q.offerMessage(3);

        List<Integer> all = q.takeMessage(-1);
        assert all.size() == 3;
        assert q.queueSize() == 0;
    }

    @Test
    public void takeSingle_defaultMethod() {
        ProtoQueue<String> q = new ProtoQueue<>(10);
        q.offerMessage("abc");

        String val = q.takeMessage();
        assert "abc".equals(val);
        assert q.queueSize() == 0;
    }

    @Test
    public void takeSingle_emptyQueue_returnsNull() {
        ProtoQueue<String> q = new ProtoQueue<>(10);
        assert q.takeMessage() == null;
    }

    // --- hasMore / hasSlot ---

    @Test
    public void hasMore_and_hasSlot() {
        ProtoQueue<Integer> q = new ProtoQueue<>(2);
        assert !q.hasMore();
        assert q.hasSlot();

        q.offerMessage(1);
        q.offerMessage(2);
        assert q.hasMore();
        assert !q.hasSlot();
        assert q.hasMore();
        assert !q.hasSlot();
    }

    // --- offerMessage with array ---

    @Test
    public void offerMessage_array() {
        ProtoQueue<Integer> q = new ProtoQueue<>(5);
        Integer[] data = { 10, 20, 30 };
        int accepted = q.offerMessage(data);
        assert accepted == 3;
        assert q.queueSize() == 3;

        List<Integer> items = q.takeMessage(-1);
        assert items.get(0) == 10;
        assert items.get(1) == 20;
        assert items.get(2) == 30;
    }

    @Test
    public void offerMessage_array_partialAccept() {
        ProtoQueue<Integer> q = new ProtoQueue<>(2);
        Integer[] data = { 1, 2, 3, 4, 5 };
        int accepted = q.offerMessage(data);
        assert accepted == 0;
        assert q.slotSize() == 2;
    }

    @Test
    public void offerMessage_fromRcvQueue_overflowKeepsSourceUntouched() {
        ProtoQueue<Integer> src = new ProtoQueue<>(10);
        src.offerMessage(1);
        src.offerMessage(2);
        src.offerMessage(3);

        ProtoQueue<Integer> dst = new ProtoQueue<>(2);
        int accepted = dst.offerMessage(src);
        assert accepted == 0;
        assert dst.queueSize() == 0;
        assert dst.slotSize() == 2;
        assert src.queueSize() == 3;
    }

    // --- offerMessage with ProtoRcvQueue ---

    @Test
    public void offerMessage_fromRcvQueue() {
        ProtoQueue<Integer> src = new ProtoQueue<>(10);
        src.offerMessage(1);
        src.offerMessage(2);
        src.offerMessage(3);

        ProtoQueue<Integer> dst = new ProtoQueue<>(10);
        int accepted = dst.offerMessage(src);
        assert accepted == 3;
        assert src.queueSize() == 0; // all taken
        assert dst.queueSize() == 3;
    }

    // --- immediate semantics ---

    @Test
    public void offer_is_immediately_visible() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);
        assert q.slotSize() == 8;
        assert q.queueSize() == 2;
    }

    @Test
    public void take_does_not_restore_taken() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        q.offerMessage(1);
        q.offerMessage(2);
        q.offerMessage(3);

        q.takeMessage(2);
        assert q.queueSize() == 1;
    }

    @Test
    public void skip_releasesReferenceHolder() {
        ProtoQueue<TestRefHolder> q = new ProtoQueue<>(10);
        TestRefHolder holder = new TestRefHolder();
        q.offerMessage(holder);

        assert holder.refCnt() == 1;
        q.skipMessage(1);
        assert q.queueSize() == 0;
        assert holder.refCnt() == 0;
        assert holder.releaseCount == 1;
    }

    @Test
    public void take_transfersReferenceHolderOwnership() {
        ProtoQueue<TestRefHolder> q = new ProtoQueue<>(10);
        TestRefHolder holder = new TestRefHolder();
        q.offerMessage(holder);

        TestRefHolder taken = q.takeMessage();
        assert taken == holder;
        assert q.queueSize() == 0;
        assert holder.refCnt() == 1;
        assert holder.releaseCount == 0;

        holder.release();
        assert holder.refCnt() == 0;
    }

    // --- toString ---

    @Test
    public void toString_unlimitedCapacity() {
        ProtoQueue<Integer> q = new ProtoQueue<>(-1);
        String s = q.toString();
        assert s.contains("INT_MAX_VALUE");
        assert s.contains("queueSize:0");
    }

    @Test
    public void toString_fixedCapacity() {
        ProtoQueue<Integer> q = new ProtoQueue<>(100);
        String s = q.toString();
        assert s.contains("capacity:100");
        assert s.contains("queueSize:0");
        assert s.contains("slotSize:100");
    }

    // --- offerMessage with empty list ---

    @Test
    public void offerMessage_emptyList() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        int cnt = q.offerMessage(Collections.emptyList());
        assert cnt == 0;
        assert q.queueSize() == 0;
        assert q.slotSize() == 10;
    }

    @Test
    public void offerMessage_emptyArray() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);
        int cnt = q.offerMessage(new Integer[0]);
        assert cnt == 0;
    }

    // --- interleaved offer/take cycles ---

    @Test
    public void interleavedCycles() {
        ProtoQueue<Integer> q = new ProtoQueue<>(10);

        // cycle 1: offer, take
        q.offerMessage(1);
        q.offerMessage(2);
        assert q.queueSize() == 2;

        q.takeMessage();
        assert q.queueSize() == 1;

        // cycle 2: offer more, take all
        q.offerMessage(3);
        assert q.queueSize() == 2;

        List<Integer> all = q.takeMessage(-1);
        assert all.size() == 2;
        assert all.get(0) == 2;
        assert all.get(1) == 3;

        assert q.queueSize() == 0;
        assert q.slotSize() == 10;
    }

    private static class TestRefHolder implements ReferenceHolder {
        private int refCnt = 1;
        private int releaseCount;

        @Override
        public int refCnt() {
            return this.refCnt;
        }

        @Override
        public ReferenceHolder retain() {
            return this.retain(1);
        }

        @Override
        public ReferenceHolder retain(int increment) {
            this.refCnt += increment;
            return this;
        }

        @Override
        public boolean release() {
            return this.release(1);
        }

        @Override
        public boolean release(int decrement) {
            this.releaseCount++;
            this.refCnt -= decrement;
            return this.refCnt == 0;
        }
    }
}
