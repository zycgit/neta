/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.math.BigInteger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

public class ChunkPoolTest {
    private static boolean checkUsed(int form, int to, byte[] chunksMap) {
        int formByte = form / 8;
        int formMask = form % 8;
        int toByte = to / 8;
        int toMask = to % 8;

        if (formByte == toByte) {
            byte data = chunksMap[toByte];
            byte mask = PageChunkPool.useMask(formMask, toMask);
            return data == (data | mask);
        } else {
            byte data1 = chunksMap[formByte];
            byte data2 = chunksMap[toByte];

            byte mask1 = (byte) (0b11111111 >>> formMask);
            byte mask2 = PageChunkPool.useMask(0, toMask);

            for (int i = (formByte + 1); i < toByte; i++) {
                if (chunksMap[i] != -1) {
                    return false;
                }
            }
            return (data1 == (data1 | mask1)) && (data2 == (data2 | mask2));
        }
    }

    //    @Test
    //    public void splitTest_1() {
    //        PageChunkPool pool = new PageChunkPool(1234, 1, 12);
    //
    //        PageList pl1 = pool.reqAlloc(8);
    //        assert pl1.getFromPage() == 0 && pl1.getToPage() == 7;
    //
    //        PageList pl2 = pl1.split(3);
    //        assert pl1.getFromPage() == 4 && pl1.getToPage() == 7;
    //        assert pl2.getFromPage() == 0 && pl2.getToPage() == 3;
    //
    //        PageList pl3 = pl1.split(5);
    //        assert pl1.getFromPage() == 6 && pl1.getToPage() == 7;
    //        assert pl2.getFromPage() == 0 && pl2.getToPage() == 3;
    //        assert pl3.getFromPage() == 4 && pl3.getToPage() == 5;
    //
    //        assert pl1.isAvailable();
    //        assert pl2.isAvailable();
    //        assert pl3.isAvailable();
    //
    //        pl1.free();
    //
    //        assert !pl1.isAvailable();
    //        assert pl2.isAvailable();
    //        assert pl3.isAvailable();
    //
    //        PageList plTest1 = pool.reqAlloc(2);
    //        assert plTest1.getFromPage() == 6 && plTest1.getToPage() == 7;
    //        plTest1.free();
    //
    //    }

    private static boolean checkFree(int form, int to, byte[] chunksMap) {
        int formByte = form / 8;
        int formMask = form % 8;
        int toByte = to / 8;
        int toMask = to % 8;

        if (formByte == toByte) {
            byte data = chunksMap[toByte];
            byte mask = PageChunkPool.useMask(formMask, toMask);
            return data == (data & ~mask);
        } else {
            byte data1 = chunksMap[formByte];
            byte data2 = chunksMap[toByte];

            byte mask1 = (byte) (0b11111111 >>> formMask);
            byte mask2 = PageChunkPool.useMask(0, toMask);

            for (int i = (formByte + 1); i < toByte; i++) {
                if (chunksMap[i] != 0) {
                    return false;
                }
            }
            return (data1 == (data1 & ~mask1)) && (data2 == (data2 & ~mask2));
        }
    }

    public static String binary(byte bytes) {
        return new BigInteger(1, new byte[] { bytes }).toString(2);
    }

    @Test
    public void availableTest_1() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 12);

        PageChunkSplit pl1 = pool.requestPages(8);
        assert pl1.isAvailable();
        assert pl1.getFromPage() == 0 && pl1.getToPage() == 7;

        pl1.free();
        assert !pl1.isAvailable();

        PageChunkSplit pl2 = pool.requestPages(8);
        assert pl2.isAvailable();
        assert pl2.getFromPage() == 0 && pl2.getToPage() == 7;
    }

    @Test
    public void test_0() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 1);

        PageChunkSplit pl1 = pool.requestPages(2);

        assert pl1.getFromPage() == 0 && pl1.getToPage() == 1;
        assert pool.chunksMap[0] == -64;

        assert pl1.isAvailable();

        pl1.free();
        assert pool.chunksMap[0] == 0;

        assert !pl1.isAvailable();
    }

    @Test
    public void test_1() {
        assert binary(PageChunkPool.useMask(0, 0)).equals("10000000");
        assert binary(PageChunkPool.useMask(0, 1)).equals("11000000");
        assert binary(PageChunkPool.useMask(0, 2)).equals("11100000");
        assert binary(PageChunkPool.useMask(1, 2)).equals("1100000");
    }

    @Test
    public void test_2() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 6);
        PageChunkSplit pl1 = pool.requestPages(1);
        assert binary(pool.chunksMap[0]).equals("10000000");

        PageChunkSplit pl2 = pool.requestPages(2);
        assert binary(pool.chunksMap[0]).equals("10110000");

        PageChunkSplit pl3 = pool.requestPages(3);
        assert binary(pool.chunksMap[0]).equals("10111111");

        PageChunkSplit pl4 = pool.requestPages(1);
        assert binary(pool.chunksMap[0]).equals("11111111");

        pl3.free();
        assert binary(pool.chunksMap[0]).equals("11110000");

        PageChunkSplit pl5 = pool.requestPages(2);
        assert binary(pool.chunksMap[0]).equals("11111100");

        PageChunkSplit pl6 = pool.requestPages(3);
        assert binary(pool.chunksMap[0]).equals("11111100");
        assert binary(pool.chunksMap[1]).equals("11110000");

        PageChunkSplit pl7 = pool.requestPages(1);
        assert binary(pool.chunksMap[0]).equals("11111110");
        assert binary(pool.chunksMap[1]).equals("11110000");

        PageChunkSplit pl8 = pool.requestPages(4);
        assert binary(pool.chunksMap[0]).equals("11111110");
        assert binary(pool.chunksMap[1]).equals("11111111");

        PageChunkSplit pl9 = pool.requestPages(1);
        assert binary(pool.chunksMap[0]).equals("11111111");
        assert binary(pool.chunksMap[1]).equals("11111111");

        pl5.free();
        assert binary(pool.chunksMap[0]).equals("11110011");
        pl8.free();
        assert binary(pool.chunksMap[0]).equals("11110011");
        assert binary(pool.chunksMap[1]).equals("11110000");

        PageChunkSplit pl10 = pool.requestPages(32);
        assert binary(pool.chunksMap[0]).equals("11110011");
        assert binary(pool.chunksMap[1]).equals("11110000");
        assert binary(pool.chunksMap[2]).equals("0");
        assert binary(pool.chunksMap[3]).equals("0");
        assert binary(pool.chunksMap[4]).equals("11111111");
        assert binary(pool.chunksMap[5]).equals("11111111");
        assert binary(pool.chunksMap[6]).equals("11111111");
        assert binary(pool.chunksMap[7]).equals("11111111");

        pl10.free();
        assert binary(pool.chunksMap[0]).equals("11110011");
        assert binary(pool.chunksMap[1]).equals("11110000");
        assert binary(pool.chunksMap[2]).equals("0");
        assert binary(pool.chunksMap[3]).equals("0");
        assert binary(pool.chunksMap[4]).equals("0");
        assert binary(pool.chunksMap[5]).equals("0");
        assert binary(pool.chunksMap[6]).equals("0");
        assert binary(pool.chunksMap[7]).equals("0");
    }

    @Test
    public void test_3() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 12);

        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicLong allocCnt = new AtomicLong(0);
        LinkedBlockingQueue<PageChunkSplit> pageLists = new LinkedBlockingQueue<>();

        AtomicLong runCnt = new AtomicLong(0);
        for (int i = 0; i < 100; i++) {
            ThreadUtils.daemonThread(true, (Runnable) () -> {
                runCnt.incrementAndGet();
                while (!exit.get()) {
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
                runCnt.decrementAndGet();
            });
        }

        ThreadUtils.sleep(3000);
        exit.set(true);
        while (runCnt.get() > 0) {
            ThreadUtils.sleep(100);
        }

        PageChunkSplit pageList = null;
        while ((pageList = pageLists.poll()) != null) {
            int fromPage = pageList.getFromPage();
            int toPage = pageList.getToPage();
            assert checkUsed(fromPage, toPage, pool.chunksMap);
            pageList.free();
            assert checkFree(fromPage, toPage, pool.chunksMap);
        }

        for (int i = 0; i < pool.chunksMap.length; i++) {
            assert pool.chunksMap[i] == 0;
        }
    }

    @Test
    public void test_4() {
        PageChunkPool pool = new PageChunkPool(1234, 1, 3);
        assert pool.getUsage() == 0;

        PageChunkSplit pl1 = pool.requestPages(1);
        assert pool.getUsage() == 12.5;
        PageChunkSplit pl2 = pool.requestPages(1);
        assert pool.getUsage() == 25.0;
        PageChunkSplit pl3 = pool.requestPages(1);
        assert pool.getUsage() == 37.5;
        PageChunkSplit pl4 = pool.requestPages(1);
        assert pool.getUsage() == 50.0;

        pl1.free();
        assert pool.getUsage() == 37.5;
        pl2.free();
        assert pool.getUsage() == 25.0;
        pl3.free();
        assert pool.getUsage() == 12.5;
        PageChunkSplit pl5 = pool.requestPages(3);
        assert pool.getUsage() == 62.5;
        pl4.free();
        assert pool.getUsage() == 50.0;
        PageChunkSplit pl6 = pool.requestPages(3);
        assert pool.getUsage() == 100.0;

        pl5.free();
        assert pool.getUsage() == 50.0;
        pl6.free();
        assert pool.getUsage() == 0.0;
    }

    @Test
    public void testIsFree_multiByteSpan() {
        // Create a pool with 16 pages (2 bytes in chunksMap)
        PageChunkPool pool = new PageChunkPool(9999, 1, 16);

        // All pages should initially be free
        assert checkFree(0, 15, pool.chunksMap) : "all pages should be free initially";

        // Allocate 4 pages (will get pages 0-3)
        PageChunkSplit pl1 = pool.requestPages(4);
        assert pl1 != null;

        // Pages 0-3 used, 4-15 free
        assert checkUsed(0, 3, pool.chunksMap) : "pages 0-3 should be used";
        assert checkFree(4, 7, pool.chunksMap) : "pages 4-7 should be free";

        // Allocate 4 more pages (will get pages 4-7)
        PageChunkSplit pl2 = pool.requestPages(4);
        assert pl2 != null;

        // Pages 0-7 used (crosses byte boundary), 8-15 free
        assert checkUsed(0, 7, pool.chunksMap) : "pages 0-7 should be used";
        assert checkFree(8, 15, pool.chunksMap) : "pages 8-15 should be free";

        // Free first allocation
        pl1.free();

        // Pages 0-3 free, 4-7 used, 8-15 free
        assert checkFree(0, 3, pool.chunksMap) : "pages 0-3 should be free after free";
        assert checkUsed(4, 7, pool.chunksMap) : "pages 4-7 should still be used";
        assert checkFree(8, 15, pool.chunksMap) : "pages 8-15 should be free";

        // Free second allocation
        pl2.free();

        // All pages free again, crosses byte boundary
        assert checkFree(0, 15, pool.chunksMap) : "all pages should be free after freeing all";
    }
}
