package net.hasor.neta.bytebuf;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.io.IOUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.All)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class PerformanceTest {
    static boolean[] randomBoolean = new boolean[1000];
    static int[]     randomInt     = new int[1000];

    static {
        for (int i = 0; i < randomBoolean.length; i++) {
            randomBoolean[i] = RandomUtils.nextBoolean();
        }
        for (int i = 0; i < randomBoolean.length; i++) {
            randomInt[i] = RandomUtils.nextInt(8 * 1024, 8 * 1024 * 4);
        }
    }

    @Threads(16)
    @Benchmark
    public void requestNetaBuffer() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(77);
        IOUtils.closeQuietly(buf);
    }

    @Threads(16)
    @Benchmark
    public void requestNettyBuffer() {
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.ByteBufAllocator.DEFAULT.heapBuffer(77);
        buffer.release();
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(PerformanceTest.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}
