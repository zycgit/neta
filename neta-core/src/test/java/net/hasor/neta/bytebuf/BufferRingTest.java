/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.Test;

import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;

public class BufferRingTest {
    @Test
    public void ringTest_01() {
        BufferRing<String> ring = new BufferRing<>();

        assert ring.size() == 0;
        assert ring.next() == null;
        assert ring.next() == null;
        assert ring.find(0) == null;
        assert ring.find(0) == null;
        assert ring.find(10) == null;
        assert ring.find(10) == null;
    }

    @Test
    public void ringTest_02() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("abc");

        assert ring.size() == 1;
        assert ring.next().equals("abc");
        assert ring.next().equals("abc");
        assert ring.find(0).equals("abc");
        assert ring.find(0).equals("abc");
        assert ring.find(10).equals("abc");
        assert ring.find(10).equals("abc");
    }

    @Test
    public void ringTest_03() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");

        assert ring.size() == 2;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.find(0).equals("b");
        assert ring.find(0).equals("b");
        assert ring.find(1).equals("a");
        assert ring.find(1).equals("a");
        assert ring.find(2).equals("b");
        assert ring.find(2).equals("b");
        assert ring.find(3).equals("a");
        assert ring.find(3).equals("a");
    }

    @Test
    public void ringTest_04() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");
        ring.add("d");
        ring.add("e");

        assert ring.size() == 5;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next().equals("c");
        assert ring.next().equals("d");
        assert ring.next().equals("e");

        ring.remove("c");
        assert ring.size() == 4;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next().equals("d");
        assert ring.next().equals("e");

        ring.remove("d");
        assert ring.size() == 3;
        assert ring.next().equals("a");
        assert ring.next().equals("b");
        assert ring.next().equals("e");

        ring.remove("a");
        assert ring.size() == 2;
        assert ring.next().equals("b");
        assert ring.next().equals("e");

        ring.remove("b");
        assert ring.size() == 1;
        assert ring.next().equals("e");

        ring.remove("e");
        assert ring.size() == 0;
        assert ring.next() == null;
    }

    @Test
    public void ringTest_05() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");

        ring.remove("a");

        assert ring.size() == 0;
        assert ring.next() == null;
    }

    @Test
    public void ringTest_06_1() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");

        ring.remove("a");
        ring.remove("b");

        assert ring.size() == 0;
        assert ring.next() == null;
    }

    @Test
    public void ringTest_06_2() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.next();
        ring.remove("a");
        ring.remove("b");

        assert ring.size() == 0;
        assert ring.next() == null;
    }

    @Test
    public void ringTest_07_1() {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("a");
        ring.add("b");
        ring.add("c");

        ring.remove("c");

        assert ring.size() == 2;
        assert ring.next().equals("a");
    }

    @Test
    public void concurrentTest_01() {
        class GroupInt {
            final String group;
            final Long   number;

            public GroupInt(String group, Long number) {
                this.group = group;
                this.number = number;
            }

            @Override
            public String toString() {
                return String.valueOf(this.number);
            }
        }
        BufferRing<GroupInt> ring = new BufferRing<>();

        AtomicBoolean exit = new AtomicBoolean(false);
        Map<String, LinkedList<GroupInt>> cache = new ConcurrentHashMap<>();

        AtomicLong runCnt = new AtomicLong(0);
        AtomicLong numbers = new AtomicLong(1);

        // write thread
        for (int i = 0; i < 3; i++) {
            int finalI = i;
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                Thread.currentThread().setName("write " + finalI);
                String tName = Thread.currentThread().getName();
                LinkedList<GroupInt> objects = new LinkedList<>();
                cache.put(tName, objects);
                runCnt.incrementAndGet();

                while (!exit.get()) {
                    if (RandomUtils.nextBoolean()) {
                        GroupInt groupInt = new GroupInt(tName, numbers.incrementAndGet());
                        objects.add(groupInt);
                        ring.add(groupInt);
                    } else if (objects.size() > 10) {
                        GroupInt poll = objects.poll();
                        if (poll != null) {
                            ring.remove(poll);
                        }
                    }
                }
                runCnt.decrementAndGet();
            });
        }

        // read thread
        for (int i = 0; i < 3; i++) {
            int finalI = i;
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();
                Thread.currentThread().setName("read " + finalI);
                while (!exit.get()) {
                    ring.next();
                }
                runCnt.decrementAndGet();
            });
        }

        ThreadUtils.sleep(2000);
        exit.set(true);
        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }

        // check result
        Map<String, List<Long>> checkCache = new ConcurrentHashMap<>();
        cache.forEach((group, ints) -> {
            List<Long> list = new LinkedList<>();
            checkCache.put(group, list);
            if (cache.get(group).isEmpty()) {
                return;
            }

            // first
            GroupInt firstInCache = cache.get(group).get(0);
            GroupInt start = ring.next();
            while (!(start.group.equals(group) && Objects.equals(firstInCache.number, start.number))) {
                start = ring.next();
            }

            list.add(start.number);
            while (true) {
                GroupInt next = ring.next();
                if (next == start) {
                    break;
                }
                if (StringUtils.equals(group, next.group)) {
                    list.add(next.number);
                }
            }
        });

        for (String cacheKey : cache.keySet()) {
            List<Long> long1 = cache.get(cacheKey).stream().map(groupInt -> groupInt.number).collect(Collectors.toList());
            List<Long> long2 = checkCache.get(cacheKey);
            assert long1.size() == long2.size();

            for (Long check : long1) {
                assert long2.contains(check);
            }
        }
    }
}
