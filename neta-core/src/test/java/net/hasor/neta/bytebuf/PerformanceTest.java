package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceTest {
    @Test
    public void performance_fullConflict() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 7);

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);
        LinkedBlockingQueue<PageChunkSplit> pageLists = new LinkedBlockingQueue<>();

        for (int i = 0; i < 32; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                while (!exit.get()) {
                    //                    ThreadUtils.sleep(5);
                    if (RandomUtils.nextBoolean()) {
                        PageChunkSplit pageList = pool.requestPages(RandomUtils.nextInt(1, 128));
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
}
