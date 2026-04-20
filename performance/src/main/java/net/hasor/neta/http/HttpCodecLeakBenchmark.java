package net.hasor.neta.http;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetConfig;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.DefaultHttpContent;
import net.hasor.neta.codec.http.DefaultHttpRequest;
import net.hasor.neta.codec.http.DefaultLastHttpContent;
import net.hasor.neta.codec.http.DefaultLastHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequestEncoder;
import net.hasor.neta.codec.http.HttpVersion;
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
public class HttpCodecLeakBenchmark {
    private static final byte[] BODY = "{\"name\":\"neta\",\"size\":1024}".getBytes(StandardCharsets.UTF_8);
    private static final ProtoContext CONTEXT = createContext();

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
    public int encodeFullRequest() throws Throwable {
        ByteBuf body = trackedBody(BODY);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/items", body);
        request.addHeader(HttpHeaderNames.HOST, "example.com");
        request.addHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        request.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(BODY.length));

        ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
        src.offerMessage(request);

        HttpRequestEncoder encoder = new HttpRequestEncoder();
        encoder.onMessage(CONTEXT, src, dst);
        return releaseOutbound(dst);
    }

    @Benchmark
    public int encodeChunkedRequest() throws Throwable {
        ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
        src.offerMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/chunked"));
        src.offerMessage(new DefaultLastHttpHeaders().addHeader(HttpHeaderNames.HOST, "example.com").addHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED));
        src.offerMessage(new DefaultHttpContent(trackedBody("Wiki".getBytes(StandardCharsets.US_ASCII))));
        src.offerMessage(new DefaultLastHttpContent(trackedBody("pedia".getBytes(StandardCharsets.US_ASCII))));

        HttpRequestEncoder encoder = new HttpRequestEncoder();
        encoder.onMessage(CONTEXT, src, dst);
        return releaseOutbound(dst);
    }

    private static ProtoContext createContext() {
        Map<Class<?>, Object> contextMap = new ConcurrentHashMap<>();
        NetConfig config = new NetConfig();
        config.setBufAllocator(ByteBufAllocator.DEFAULT);
        return (ProtoContext) Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            if ("getConfig".equals(method.getName())) {
                return config;
            }
            if ("context".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                }
                if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
            }
            if ("rootContext".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                }
                if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
            }
            return null;
        });
    }

    private static ByteBuf trackedBody(byte[] data) {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(data.length, data.length);
        body.writeBytes(data, 0, data.length);
        body.markWriter();
        return body;
    }

    private static int releaseOutbound(ProtoQueue<ByteBuf> dst) {
        int totalBytes = 0;
        while (dst.hasMore()) {
            ByteBuf out = dst.takeMessage();
            totalBytes += out.readableBytes();
            out.release();
        }
        return totalBytes;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(HttpCodecLeakBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}