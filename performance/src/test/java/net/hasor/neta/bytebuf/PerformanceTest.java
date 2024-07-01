package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.io.IOUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
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

    @Benchmark
    public void requestNetaBuffer() {
        for (int i = 0; i < randomInt.length; i++) {
            ByteBuf buf = net.hasor.neta.bytebuf.ByteBufAllocator.DEFAULT.pooledBuffer(randomInt[i]);
            IOUtils.closeQuietly(buf);
        }
    }

    @Benchmark
    public void requestNettaBuffer() {
        for (int i = 0; i < randomInt.length; i++) {
            io.netty.buffer.ByteBuf buffer = io.netty.buffer.ByteBufAllocator.DEFAULT.heapBuffer(randomInt[i]);
            buffer.release();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(PerformanceTest.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}
