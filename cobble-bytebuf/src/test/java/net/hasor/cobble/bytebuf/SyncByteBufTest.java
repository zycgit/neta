package net.hasor.cobble.bytebuf;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SyncByteBufTest {

    // 并发中的生产者，持续不断的产生 1～100 有序的数，这些数字写入到 byteBuf
    private void producer(AtomicBoolean stop, ByteBuf byteBuf, int interval) throws Exception {
        AtomicInteger i = new AtomicInteger(0);
        while (!stop.get()) {
            byteBuf.waitWriteable(buf -> {
                buf.writeByte((byte) i.incrementAndGet());
                buf.markWriter();
            });

            if (i.get() >= 100) {
                i.set(0);
            }

            ThreadUtils.sleep(interval);
        }
    }

    // 并发中的消费者，持续不断的消费 srcByteBuf 中的数据将其搬运到 dstByteBuf
    private void consumer(AtomicBoolean stop, ByteBuf srcByteBuf, ByteBuf dstByteBuf, int interval) throws Exception {
        while (!stop.get()) {
            srcByteBuf.waitReadable(buf -> {
                byte aByte = buf.readByte();
                buf.markReader();

                dstByteBuf.writeByte(aByte);
            });

            ThreadUtils.sleep(interval);
        }
    }

    private boolean allAtState(List<Thread> threads, Thread.State state) {
        for (Thread t : threads) {
            if (t.getState() != state) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void producerAndConsumer_01() throws InterruptedException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.arrayBuffer(2048);
        ByteBuf result = ByteBufAllocator.DEFAULT.arrayBuffer();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean ass = new AtomicBoolean(true);

        ThreadUtils.runDaemonThread(() -> {
            try {
                producer(stop, buf, 0);
            } catch (Exception e) {
                stop.set(true);
                ass.set(false);
            }
        });
        ThreadUtils.runDaemonThread(() -> {
            try {
                consumer(stop, buf, result, 100);
            } catch (Exception e) {
                stop.set(true);
                ass.set(false);
            }
        });

        int totalSec = 5000;
        long s = System.currentTimeMillis();
        while (true) {
            long cost = System.currentTimeMillis() - s;
            if (cost > totalSec) {
                break;
            }
            if (stop.get() || !ass.get()) {
                break;
            }

            Thread.sleep(100);
        }
        stop.set(true);

        assert ass.get();

        int rec = 0;
        result.markWriter();
        while ((result.readableBytes() > 0)) {
            result.readByte();
            rec++;
        }

        // rec 收到的个数乘以 consumer 间隔，铁定小于等于总运行时间
        assert (rec * 100) <= totalSec;
        // rec 收到的个数乘以 consumer 间隔，铁定大于总运行时间 - 0.5秒（ 0.5秒内约有 5 个 rec）
        assert (rec * 100) > (totalSec - 1000);
    }

    @Test
    public void producerAndConsumer_02() throws InterruptedException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.arrayBuffer(2048);
        ByteBuf result = ByteBufAllocator.DEFAULT.arrayBuffer();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean ass = new AtomicBoolean(true);

        ThreadUtils.runDaemonThread(() -> {
            try {
                producer(stop, buf, 0);
            } catch (Exception e) {
                stop.set(true);
                ass.set(false);
            }
        });
        ThreadUtils.runDaemonThread(() -> {
            try {
                consumer(stop, buf, result, 100);
            } catch (Exception e) {
                stop.set(true);
                ass.set(false);
            }
        });
        ThreadUtils.runDaemonThread(() -> {
            try {
                consumer(stop, buf, result, 100);
            } catch (Exception e) {
                stop.set(true);
                ass.set(false);
            }
        });

        int totalSec = 5000;
        long s = System.currentTimeMillis();
        while (true) {
            long cost = System.currentTimeMillis() - s;
            if (cost > totalSec) {
                break;
            }
            if (stop.get() || !ass.get()) {
                break;
            }

            Thread.sleep(100);
        }
        stop.set(true);

        assert ass.get();

        int rec = 0;
        result.markWriter();
        while ((result.readableBytes() > 0)) {
            result.readByte();
            rec++;
        }

        // rec 收到的个数乘以 consumer 间隔，铁定大于总运行时间（有2个消费者）
        assert (rec * 100) > totalSec;
        // rec 收到的个数乘以 consumer 间隔，铁定小于等于 2倍总运行时间（有2个消费者）
        assert (rec * 100) <= (totalSec * 2);
    }

    @Test
    public void producerAndConsumer_03() throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean ass = new AtomicBoolean(true);
        ByteBuf buf = ByteBufAllocator.DEFAULT.arrayBuffer(2048);

        // 创建 10个线程来消费者1000个数字
        List<ByteBuf> result = new ArrayList<>();
        List<Thread> resultThread = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            resultThread.add(ThreadUtils.runDaemonThread(() -> {
                ByteBuf dst = ByteBufAllocator.DEFAULT.arrayBuffer();
                result.add(dst);
                try {
                    consumer(stop, buf, dst, 100);
                } catch (Exception e) {
                    stop.set(true);
                    ass.set(false);
                }
            }));
        }

        // 顺序生成 1000 个 1～100之间的数字
        for (int i = 1; i < 1001; i++) {
            buf.writeByte((byte) (i % 100));
        }
        buf.markWriter();

        // 等待全部被消费完毕
        while (!allAtState(resultThread, Thread.State.WAITING)) {
            Thread.sleep(100);
        }
        assert ass.get();
        stop.set(true);
        for (Thread t : resultThread) {
            t.interrupt();
        }
        while (!allAtState(resultThread, Thread.State.TERMINATED)) {
            Thread.sleep(100);
        }

        //统计不同数字的个数
        Map<Byte, Integer> ori = new HashMap<>();
        Map<Byte, Integer> dst = new HashMap<>();

        for (byte dat : buf.array()) {
            if (dat == 0) {
                continue; // 0 是 buffer 中尚未使用的字节
            }
            ori.merge(dat, 1, Integer::sum);
        }

        for (ByteBuf tmp : result) {
            for (byte dat : tmp.array()) {
                if (dat == 0) {
                    continue; // 0 是 buffer 中尚未使用的字节
                }
                dst.merge(dat, 1, Integer::sum);
            }
        }

        ori.forEach((key, value) -> {
            if (!Objects.equals(dst.get(key), value)) {
                System.out.println(key + " failed > " + value + "," + dst.get(key));
                assert false;
            }
            assert Objects.equals(dst.get(key), value);
        });
    }
}