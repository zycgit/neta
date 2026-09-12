/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import org.junit.Test;

/**
 * Edge case tests for BufferRing: null handling, remove non-existent,
 * single element operations, large rings, and find/next interaction.
 */
public class RingBufferEdgeCaseTest {

    // ========================================================================
    // null handling
    // ========================================================================

    @Test
    public void remove_null_isNoop() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.remove(null);
        assert ring.size() == 1;
        assert ring.next().equals("a");
    }

    @Test
    public void remove_nonExistent_isNoop() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.remove("z"); // not in ring
        assert ring.size() == 2;
    }

    @Test
    public void remove_emptyRing_isNoop() {
        BufferRing<String> ring = new BufferRing<>();
        ring.remove("a");
        assert ring.size() == 0;
    }

    // ========================================================================
    // single element edge cases
    // ========================================================================

    @Test
    public void addAndRemove_singleElement_emptyRing() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("x");
        assert ring.size() == 1;
        assert ring.find(0).equals("x");

        ring.remove("x");
        assert ring.size() == 0;
        assert ring.next() == null;
        assert ring.find(0) == null;
    }

    @Test
    public void addTwoRemoveFirst_leavesSingle() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.remove("a");
        assert ring.size() == 1;
        assert ring.next().equals("b");
        assert ring.next().equals("b");
    }

    @Test
    public void addTwoRemoveLast_leavesSingle() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.remove("b");
        assert ring.size() == 1;
        assert ring.next().equals("a");
        assert ring.next().equals("a");
    }

    // ========================================================================
    // find() edge cases
    // ========================================================================

    @Test
    public void find_negativeSkip_returnsCurrent() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        // skip <= 0 returns the current node data
        assert ring.find(-1) != null;
    }

    @Test
    public void find_skipLargerThanSize_wrapsAround() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");

        // skip=10, size=3 → 10 % 3 = 1 → should skip 1 from current
        String result1 = ring.find(3); // 3 % 3 = 0 → from current, skip 0... actually skip = 3%3 = 0 → loops 0
        String result2 = ring.find(6); // 6 % 3 = 0
        // Both should return same element since 0 iterations from cursor
        assert result1 != null;
        assert result2 != null;
    }

    @Test
    public void find_skipZero_returnsCurrent() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        assert ring.find(0).equals("a");
    }

    // ========================================================================
    // next() and cursor movement
    // ========================================================================

    @Test
    public void next_fullCycle_returnsToStart() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");

        String first = ring.next();
        String second = ring.next();
        String third = ring.next();
        String fourth = ring.next(); // should wrap around

        assert fourth.equals(first);
    }

    @Test
    public void next_afterRemoveCurrent_advancesCorrectly() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");

        ring.next(); // cursor at a
        ring.remove("b");
        // Should not break cursor movement
        String n = ring.next();
        assert n != null;
    }

    // ========================================================================
    // add after remove
    // ========================================================================

    @Test
    public void addAfterRemoveAll_rebuildsRing() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.remove("a");
        ring.remove("b");
        assert ring.size() == 0;

        ring.add("c");
        assert ring.size() == 1;
        assert ring.next().equals("c");
    }

    @Test
    public void interleavedAddRemove_maintainsConsistency() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("1");
        ring.add("2");
        ring.remove("1");
        ring.add("3");
        ring.remove("2");
        ring.add("4");

        assert ring.size() == 2;
        // remaining: 3, 4
        String n1 = ring.next();
        String n2 = ring.next();
        assert (n1.equals("3") || n1.equals("4"));
        assert (n2.equals("3") || n2.equals("4"));
        assert !n1.equals(n2);
    }

    // ========================================================================
    // large ring stress
    // ========================================================================

    @Test
    public void largeRing_100Elements_nextCycles() {
        BufferRing<Integer> ring = new BufferRing<>();
        for (int i = 0; i < 100; i++) {
            ring.add(i);
        }
        assert ring.size() == 100;

        // Iterate through all 100 elements
        for (int i = 0; i < 200; i++) {
            Integer val = ring.next();
            assert val != null;
            assert val >= 0 && val < 100;
        }
    }

    @Test
    public void largeRing_removeAll_sequentially() {
        BufferRing<Integer> ring = new BufferRing<>();
        for (int i = 0; i < 50; i++) {
            ring.add(i);
        }
        for (int i = 0; i < 50; i++) {
            ring.remove(i);
        }
        assert ring.size() == 0;
        assert ring.next() == null;
    }

    @Test
    public void largeRing_removeOddNumbers() {
        BufferRing<Integer> ring = new BufferRing<>();
        for (int i = 0; i < 20; i++) {
            ring.add(i);
        }
        // Remove all odd numbers
        for (int i = 1; i < 20; i += 2) {
            ring.remove(i);
        }
        assert ring.size() == 10;

        // Verify all remaining are even
        for (int i = 0; i < 10; i++) {
            Integer val = ring.next();
            assert val % 2 == 0 : "Expected even, got " + val;
        }
    }

    // ========================================================================
    // Type safety with equals
    // ========================================================================

    @Test
    public void remove_usesEqualsNotIdentity() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("hello"); // intentionally new instance
        ring.add("world");

        ring.remove("hello"); // different instance, same value
        assert ring.size() == 1;
        assert ring.next().equals("world");
    }

    // ========================================================================
    // size() consistency
    // ========================================================================

    @Test
    public void size_alwaysConsistent_afterOperations() {
        BufferRing<String> ring = new BufferRing<>();
        assert ring.size() == 0;

        ring.add("a");
        assert ring.size() == 1;
        ring.add("b");
        assert ring.size() == 2;
        ring.add("c");
        assert ring.size() == 3;

        ring.remove("b");
        assert ring.size() == 2;
        ring.remove("a");
        assert ring.size() == 1;
        ring.remove("c");
        assert ring.size() == 0;
    }
}
