package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
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
        LinkedBlockingQueue<PageChunkSplit> pageLists = new LinkedBlockingQueue<>();

        for (int i = 0; i < 32; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                AtomicInteger num = new AtomicInteger();
                while (!exit.get()) {
                    if (randomBoolean[num.incrementAndGet() % 1024]) {
                        PageChunkSplit pageList = pool.requestPages(randomInt[num.incrementAndGet() % 1024]);
                        if (pageList != null) {
                            allocCnt.incrementAndGet();
                            pageLists.add(pageList);
                        }
                    } else {
                        PageChunkSplit poll = pageLists.poll();
                        if (poll != null) {
                            poll.free();
                        }
                    }
                }
            });
        }

        ThreadUtils.daemonThread(true, (Runnable) () -> {
            long t = System.currentTimeMillis();
            while (!exit.get()) {
                ThreadUtils.sleep(1000);
                long cost = (System.currentTimeMillis() - t);

                int cntPerSec = (int) (allocCnt.get() / (cost / 1000));
                System.out.println("alloc :" + allocCnt.get() + ", " + cntPerSec + "/s, hold: " + pageLists.size());
            }
        });

        ThreadUtils.sleep(5000);
        exit.set(true);
    }

    //    @Test
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
        for (int i = 0; i < 4; i++) {
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
        for (int i = 0; i < 16; i++) {
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
        ThreadUtils.sleep(10000);
        exit.set(true);

        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }
    }

    @Test
    public void performance_BufferPool() {
        BufferPool pool = new BufferPool(1, c -> new BufferWrap(ByteBuffer.allocate(c)));

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);
        LinkedBlockingQueue<Buffer> buffers = new LinkedBlockingQueue<>();

        AtomicLong runCnt = new AtomicLong(0);
        for (int i = 0; i < 32; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();
                while (!exit.get()) {
                    if (RandomUtils.nextBoolean()) {
                        Buffer buffer = pool.requestBuffer(RandomUtils.nextInt(1, 128));
                        if (buffer != null) {
                            allocCnt.incrementAndGet();
                            buffers.add(buffer);
                        }
                    } else {
                        Buffer poll = buffers.poll();
                        if (poll != null) {
                            poll.free();
                        }
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

                int writePerSec = (int) (runCnt.get() / (cost / 1000));
                int readPerSec = (int) (runCnt.get() / (cost / 1000));

                System.out.println("read :" + readPerSec + "/s, write :" + writePerSec + "/s");
            }
        });

        ThreadUtils.sleep(3000);
        exit.set(true);
        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }

        Buffer buffer;
        while ((buffer = buffers.poll()) != null) {
            buffer.free();
        }

        System.out.println(pool);
    }
}
