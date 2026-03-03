package net.hasor.neta.recycler;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import org.junit.Test;

public class MemTest {
    static boolean[] randomBoolean = new boolean[1000];
    static int[]     randomInt     = new int[1000];

    static {
        for (int i = 0; i < randomBoolean.length; i++) {
            randomBoolean[i] = RandomUtils.nextBoolean();
        }
        for (int i = 0; i < randomBoolean.length; i++) {
            randomInt[i] = RandomUtils.nextInt(1, 128);
        }
    }

    @Test
    public void requestNetaBuffer_1() {
        for (int j : randomInt) {
            ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.pooledBuffer(j);
            IOUtils.closeQuietly(buf);
        }
    }

    @Test
    public void requestNetaBuffer() {
        Future<Object> future = new BasicFuture<>();
        for (int i = 0; i < 16; i++) {
            Thread t = ThreadUtils.daemonThread(true, (Runnable) () -> {
                while (!future.isDone()) {
                    for (int j : randomInt) {
                        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.pooledBuffer(j);
                        IOUtils.closeQuietly(buf);
                    }
                }
            });
        }

        ThreadUtils.sleep(1, TimeUnit.SECONDS);
        future.completed(new Object());
        System.out.println("requestNetaBuffer done.");
    }
}