package net.hasor.nhttp.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;
import net.hasor.neta.leak.LeakMetricSnapshot;
import net.hasor.nhttp.server.internal.DefaultSessionManager;
import net.hasor.nhttp.server.internal.StreamingServletRequest;
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
public class NHttpServerLeakBenchmark {
    private final DefaultSessionManager sessionManager = new DefaultSessionManager();
    private NetManager                  neta;
    private VrtChannel                  channel;
    private LeakMetricSnapshot          before;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.neta = new NetManager();
        this.channel = (VrtChannel) this.neta.connectSync(new VrtSocketAddress(199), ctx -> {
        }, VrtSoConfig.asServer());
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        if (this.neta != null) {
            this.neta.shutdown();
        }
    }

    @Setup(Level.Iteration)
    public void captureBaseline() {
        this.before = LeakMetricSnapshot.capture(ByteBufAllocator.DEFAULT.metric());
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int multipartRequestLifecycle() {
        MultipartEncoder encoder = new MultipartEncoder();
        encoder.addField("username", "charlie");
        encoder.addField("role", "admin");
        encoder.addFile("avatar", "photo.jpg", "image/jpeg", new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

        FullHttpRequest request = buildMultipartRequest("/api/upload", encoder);
        StreamingServletRequest servletRequest = null;
        try {
            servletRequest = StreamingServletRequest.fromFullHttpRequest(request, this.channel, false, this.sessionManager);
            return servletRequest.getFileUploads().size() + servletRequest.getParameterMap().size();
        } finally {
            if (servletRequest != null) {
                servletRequest.release();
            }
            request.release();
        }
    }

    @Benchmark
    public int discardUnreadBodyLifecycle() {
        FullHttpRequest request = buildFormRequest("/api/form", "username=alice&email=alice%40example.com&comment=body-not-read");
        StreamingServletRequest servletRequest = null;
        try {
            servletRequest = StreamingServletRequest.fromFullHttpRequest(request, this.channel, false, this.sessionManager);
            servletRequest.discardUnreadBody();
            return (int) servletRequest.getContentLength();
        } finally {
            if (servletRequest != null) {
                servletRequest.release();
            }
            request.release();
        }
    }

    private static FullHttpRequest buildFormRequest(String uri, String formBody) {
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = trackedBody(bodyBytes);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
        request.setHeader(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    private static FullHttpRequest buildMultipartRequest(String uri, MultipartEncoder encoder) {
        byte[] bodyBytes = encoder.encode();
        ByteBuf content = trackedBody(bodyBytes);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, encoder.contentType());
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
        request.setHeader(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    private static ByteBuf trackedBody(byte[] data) {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(data.length, data.length);
        body.writeBytes(data, 0, data.length);
        body.markWriter();
        return body;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(NHttpServerLeakBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}