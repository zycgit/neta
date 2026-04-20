package net.hasor.neta.bytebuf;

import java.util.concurrent.TimeUnit;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
public class ByteBufLeakBenchmark {
    private static final byte[] PAYLOAD = new byte[8192];

    private LeakMetricSnapshot before;

    @Setup(Level.Iteration)
    public void captureBaseline() {
        this.before = LeakMetricSnapshot.capture(ByteBufAllocator.DEFAULT.metric());
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int pooledBufferRoundTrip() {
        int written = 0;
        for (int i = 0; i < 256; i++) {
            ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(1024, 16384);
            try {
                buf.writeBytes(PAYLOAD, 0, 1024);
                written += 1024;
            } finally {
                buf.release();
            }
        }
        return written;
    }

    @Benchmark
    public int directBufferRoundTrip() {
        int written = 0;
        for (int i = 0; i < 256; i++) {
            ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(2048, 16384);
            try {
                buf.writeBytes(PAYLOAD, 0, 2048);
                written += 2048;
            } finally {
                buf.release();
            }
        }
        return written;
    }

    @Benchmark
    public int swapFileRoundTrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.swapFile(4096, 16384);
        try {
            for (int i = 0; i < 32; i++) {
                buf.writeBytes(PAYLOAD, 0, PAYLOAD.length);
            }
            return PAYLOAD.length * 32;
        } finally {
            buf.release();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(ByteBufLeakBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}