package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceTest {
    static boolean[] randomBoolean = new boolean[1024];
    static int[]     randomInt     = new int[1024];

    static {
        for (int i = 0; i < randomBoolean.length; i++) {
            randomBoolean[i] = RandomUtils.nextBoolean();
        }
        for (int i = 0; i < randomBoolean.length; i++) {
            randomInt[i] = RandomUtils.nextInt(1, 128);
        }
    }

    @Test
    public void performance_fullConflict() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 7);

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);
        Map<String, LinkedList<PageChunkSplit>> cacheMap = new ConcurrentHashMap<>();

        // test case
        for (int i = 0; i < 32; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                AtomicInteger threadCnt = new AtomicInteger();
                String tName = Thread.currentThread().getName();
                LinkedList<PageChunkSplit> objects = new LinkedList<>();
                cacheMap.put(tName, objects);

                while (!exit.get()) {
                    if (randomBoolean[threadCnt.incrementAndGet() % 1024]) {
                        PageChunkSplit pageList = pool.requestPages(randomInt[threadCnt.incrementAndGet() % 1024]);
                        if (pageList != null) {
                            allocCnt.incrementAndGet();
                            objects.add(pageList);
                        }
                    } else {
                        PageChunkSplit poll = objects.poll();
                        if (poll != null) {
                            poll.free();
                        }
                    }
                }
            });
        }

        // print performance
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            long t = System.currentTimeMillis();
            while (!exit.get()) {
                ThreadUtils.sleep(1000);
                long cost = (System.currentTimeMillis() - t);

                int cntPerSec = (int) (allocCnt.get() / (cost / 1000));
                System.out.println("alloc :" + allocCnt.get() + ", " + cntPerSec + "/s, hold: " + cacheMap.size());
            }
        });

        // run 5s
        ThreadUtils.sleep(10000);
        exit.set(true);
    }

    @Test
    public void performance_BufferRing() {
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

        AtomicLong readPerformance = new AtomicLong(0);
        AtomicLong writePerformance = new AtomicLong(0);

        // write thread
        for (int i = 0; i < 1; i++) {
            int finalI = i;
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                Thread.currentThread().setName("write " + finalI);
                String tName = Thread.currentThread().getName();
                LinkedList<GroupInt> objects = new LinkedList<>();
                cache.put(tName, objects);
                runCnt.incrementAndGet();

                AtomicInteger num = new AtomicInteger();
                while (!exit.get()) {
                    if (randomBoolean[num.incrementAndGet() % 1024]) {
                        GroupInt groupInt = new GroupInt(tName, numbers.incrementAndGet());
                        objects.add(groupInt);
                        ring.add(groupInt);
                        writePerformance.incrementAndGet();
                    } else if (objects.size() > 10) {
                        GroupInt poll = objects.poll();
                        if (poll != null) {
                            ring.remove(poll);
                            writePerformance.incrementAndGet();
                        }
                    }
                }
                runCnt.decrementAndGet();
            });
        }

        // read thread
        for (int i = 0; i < 32; i++) {
            int finalI = i;
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();
                Thread.currentThread().setName("read " + finalI);
                while (!exit.get()) {
                    ring.next();
                    readPerformance.incrementAndGet();
                }
                runCnt.decrementAndGet();
            });
        }

        // print performance
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            long t = System.currentTimeMillis();
            while (!exit.get()) {
                ThreadUtils.sleep(1000);
                long cost = (System.currentTimeMillis() - t);

                int writePerSec = (int) (writePerformance.get() / (cost / 1000));
                int readPerSec = (int) (readPerformance.get() / (cost / 1000));

                System.out.println("read :" + readPerSec + "/s, write :" + writePerSec + "/s");
            }
        });
        ThreadUtils.sleep(5000);
        exit.set(true);

        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }
    }

    @Test
    public void performance_BufferPool() {
        BufferPool pool = new BufferPool(1, 256, -1, c -> new BufferWrap(ByteBuffer.allocate(c)));

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);

        AtomicLong runCnt = new AtomicLong(0);
        for (int i = 0; i < 32; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();

                AtomicInteger num = new AtomicInteger();
                while (!exit.get()) {
                    Buffer buffer = pool.requestBuffer(randomInt[num.incrementAndGet() % 1024]);
                    if (buffer != null) {
                        allocCnt.incrementAndGet();
                        buffer.free();
                    }
                }

                runCnt.decrementAndGet();
            });
        }

        // print performance
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            long t = System.currentTimeMillis();
            while (!exit.get()) {
                ThreadUtils.sleep(1000);
                long cost = (System.currentTimeMillis() - t);

                int perSec = (int) (allocCnt.get() / (cost / 1000));
                System.out.println("allocCnt :" + allocCnt.get() + "/s, perSec :" + perSec + "/s");
            }
        });

        ThreadUtils.sleep(5000);
        exit.set(true);
        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }
    }

    @Test
    public void performance_BufferPool_vsNetty() throws IOException {
        BufferPool pool = new BufferPool(1, 64, -1, c -> new BufferWrap(ByteBuffer.allocate(c)));

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);

        AtomicLong runCnt = new AtomicLong(0);
        for (int i = 0; i < 64; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();

                AtomicInteger num = new AtomicInteger();
                while (!exit.get()) {
                    Buffer buffer = pool.requestBuffer(randomInt[num.incrementAndGet() % 1024]);
                    if (buffer != null) {
                        allocCnt.incrementAndGet();
                        buffer.free();
                    }
                }

                runCnt.decrementAndGet();
            });
        }

        // print performance
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            long t = System.currentTimeMillis();
            while (!exit.get()) {
                ThreadUtils.sleep(1000);
                long cost = (System.currentTimeMillis() - t);

                int perSec = (int) (allocCnt.get() / (cost / 1000));
                System.out.println("allocCnt :" + allocCnt.get() + "/s, perSec :" + perSec + "/s");
            }
        });

        System.in.read();
        ThreadUtils.sleep(20000);
        exit.set(true);
        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }
    }
}
