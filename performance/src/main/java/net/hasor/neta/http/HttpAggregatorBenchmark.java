package net.hasor.neta.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCounted;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequestAggregator;
import net.hasor.neta.codec.http.HttpRequestDecoder;
import net.hasor.neta.codec.http.HttpResponseAggregator;
import net.hasor.neta.codec.http.HttpResponseDecoder;
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
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
public class HttpAggregatorBenchmark {
    private static final int      MAX_CONTENT_LENGTH = 1024 * 1024;
    private static final byte[]   POST_BODY_BYTES    = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30,\"city\":\"Shanghai\",\"bio\":\"aggregator benchmark payload\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[]   SIMPLE_RESPONSE_BODY_BYTES = "Hello, Aggregator!".getBytes(StandardCharsets.UTF_8);
    private static final byte[]   LARGE_REQUEST_BODY_BYTES;
    private static final byte[]   LARGE_RESPONSE_BODY_BYTES;
    private static final byte[]   POST_REQUEST_BYTES;
    private static final byte[]   LARGE_REQUEST_BYTES;
    private static final byte[]   SIMPLE_RESPONSE_BYTES;
    private static final byte[]   LARGE_RESPONSE_BYTES;
    private static final byte[][] POST_REQUEST_STREAMS;
    private static final byte[][] LARGE_REQUEST_STREAMS;
    private static final byte[][] SIMPLE_RESPONSE_STREAMS;
    private static final byte[][] LARGE_RESPONSE_STREAMS;
    private NetManager            neta;
    private VirtualPipe           requestAggregatorPipe;
    private VirtualPipe           responseAggregatorPipe;
    private LeakMetricSnapshot    before;

    static {
        LARGE_REQUEST_BODY_BYTES = buildLargeRequestBody();
        LARGE_RESPONSE_BODY_BYTES = buildLargeResponseBody();

        POST_REQUEST_BYTES = buildPostRequest(POST_BODY_BYTES);
        LARGE_REQUEST_BYTES = buildLargeRequest(LARGE_REQUEST_BODY_BYTES);
        SIMPLE_RESPONSE_BYTES = buildSimpleResponse(SIMPLE_RESPONSE_BODY_BYTES);
        LARGE_RESPONSE_BYTES = buildLargeResponse(LARGE_RESPONSE_BODY_BYTES);

        POST_REQUEST_STREAMS = splitBytes(POST_REQUEST_BYTES, new int[] { 19, 27, 23, 31, 17 });
        LARGE_REQUEST_STREAMS = splitBytes(LARGE_REQUEST_BYTES, new int[] { 23, 31, 41, 29, 37, 43, 47 });
        SIMPLE_RESPONSE_STREAMS = splitBytes(SIMPLE_RESPONSE_BYTES, new int[] { 17, 24, 19, 13 });
        LARGE_RESPONSE_STREAMS = splitBytes(LARGE_RESPONSE_BYTES, new int[] { 21, 33, 29, 37, 41, 35, 43 });
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.neta = new NetManager();
        this.requestAggregatorPipe = this.openVirtualPipe(ctx -> {
            ctx.addLastDecoder("req-decoder", new HttpRequestDecoder());
            ctx.addLastDecoder("req-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH));
        }, VrtSoConfig.asServer(), 201);
        this.responseAggregatorPipe = this.openVirtualPipe(ctx -> {
            ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder());
            ctx.addLastDecoder("resp-aggregator", new HttpResponseAggregator(MAX_CONTENT_LENGTH));
        }, VrtSoConfig.asClient(), 202);
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
        this.requestAggregatorPipe.reset();
        this.responseAggregatorPipe.reset();
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        this.requestAggregatorPipe.reset();
        this.responseAggregatorPipe.reset();
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int neta_aggregatePostRequest() {
        return aggregateNetaRequest(this.requestAggregatorPipe, POST_REQUEST_STREAMS);
    }

    @Benchmark
    public int netty_aggregatePostRequest() {
        return aggregateNettyRequest(POST_REQUEST_STREAMS);
    }

    @Benchmark
    public int neta_aggregateLargeRequest() {
        return aggregateNetaRequest(this.requestAggregatorPipe, LARGE_REQUEST_STREAMS);
    }

    @Benchmark
    public int netty_aggregateLargeRequest() {
        return aggregateNettyRequest(LARGE_REQUEST_STREAMS);
    }

    @Benchmark
    public int neta_aggregateSimpleResponse() {
        return aggregateNetaResponse(this.responseAggregatorPipe, SIMPLE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int netty_aggregateSimpleResponse() {
        return aggregateNettyResponse(SIMPLE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int neta_aggregateLargeResponse() {
        return aggregateNetaResponse(this.responseAggregatorPipe, LARGE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int netty_aggregateLargeResponse() {
        return aggregateNettyResponse(LARGE_RESPONSE_STREAMS);
    }

    private static int aggregateNetaRequest(VirtualPipe pipe, byte[][] chunks) {
        pipe.reset();
        feedVirtualInbound(pipe, chunks);
        assertNoErrors(pipe);
        return releaseAggregatedVirtualInbound(pipe.inbound(), true);
    }

    private static int aggregateNetaResponse(VirtualPipe pipe, byte[][] chunks) {
        pipe.reset();
        feedVirtualInbound(pipe, chunks);
        assertNoErrors(pipe);
        return releaseAggregatedVirtualInbound(pipe.inbound(), false);
    }

    private static int aggregateNettyRequest(byte[][] chunks) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new io.netty.handler.codec.http.HttpRequestDecoder(),
                new io.netty.handler.codec.http.HttpObjectAggregator(MAX_CONTENT_LENGTH));
        try {
            feedNettyInbound(channel, chunks);
            return releaseAggregatedNettyInbound(channel, true);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static int aggregateNettyResponse(byte[][] chunks) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new io.netty.handler.codec.http.HttpResponseDecoder(),
                new io.netty.handler.codec.http.HttpObjectAggregator(MAX_CONTENT_LENGTH));
        try {
            feedNettyInbound(channel, chunks);
            return releaseAggregatedNettyInbound(channel, false);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void feedVirtualInbound(VirtualPipe pipe, byte[][] chunks) {
        for (int i = 0; i < chunks.length; i++) {
            pipe.channel().receiveData(ByteBuf.wrap(chunks[i]));
        }
    }

    private static void feedNettyInbound(EmbeddedChannel channel, byte[][] chunks) {
        for (int i = 0; i < chunks.length; i++) {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(chunks[i]));
        }
    }

    private static int releaseAggregatedVirtualInbound(Queue<Object> inbound, boolean request) {
        int observed = 0;
        int count = 0;
        Object item;
        while ((item = inbound.poll()) != null) {
            observed += measureAggregatedVirtual(item, request);
            count++;
        }
        if (count != 1) {
            throw new IllegalStateException("expected exactly one aggregated inbound object but got " + count);
        }
        return observed;
    }

    private static int measureAggregatedVirtual(Object item, boolean request) {
        if (request) {
            if (!(item instanceof FullHttpRequest)) {
                throw new IllegalStateException("expected FullHttpRequest but got " + item.getClass().getName());
            }
            FullHttpRequest fullRequest = (FullHttpRequest) item;
            int observed = fullRequest.uri().length() + fullRequest.content().readableBytes();
            fullRequest.release();
            return observed;
        }
        if (!(item instanceof FullHttpResponse)) {
            throw new IllegalStateException("expected FullHttpResponse but got " + item.getClass().getName());
        }
        FullHttpResponse fullResponse = (FullHttpResponse) item;
        int observed = fullResponse.status().code() + fullResponse.content().readableBytes();
        fullResponse.release();
        return observed;
    }

    private static int releaseAggregatedNettyInbound(EmbeddedChannel channel, boolean request) {
        int observed = 0;
        int count = 0;
        Object item;
        while ((item = channel.readInbound()) != null) {
            observed += measureAggregatedNetty(item, request);
            count++;
        }
        if (count != 1) {
            throw new IllegalStateException("expected exactly one aggregated netty inbound object but got " + count);
        }
        return observed;
    }

    private static int measureAggregatedNetty(Object item, boolean request) {
        if (request) {
            if (!(item instanceof io.netty.handler.codec.http.FullHttpRequest)) {
                throw new IllegalStateException("expected netty FullHttpRequest but got " + item.getClass().getName());
            }
            io.netty.handler.codec.http.FullHttpRequest fullRequest = (io.netty.handler.codec.http.FullHttpRequest) item;
            int observed = fullRequest.uri().length() + fullRequest.content().readableBytes();
            releaseNettyObject(fullRequest);
            return observed;
        }
        if (!(item instanceof io.netty.handler.codec.http.FullHttpResponse)) {
            throw new IllegalStateException("expected netty FullHttpResponse but got " + item.getClass().getName());
        }
        io.netty.handler.codec.http.FullHttpResponse fullResponse = (io.netty.handler.codec.http.FullHttpResponse) item;
        int observed = fullResponse.status().code() + fullResponse.content().readableBytes();
        releaseNettyObject(fullResponse);
        return observed;
    }

    private static void releaseNettyObject(Object msg) {
        if (msg instanceof ReferenceCounted) {
            ((ReferenceCounted) msg).release();
        }
    }

    private static void assertNoErrors(VirtualPipe pipe) {
        Throwable inboundError = pipe.inboundErrors().poll();
        if (inboundError != null) {
            throw new IllegalStateException("virtual inbound failed", inboundError);
        }
        Throwable outboundError = pipe.outboundErrors().poll();
        if (outboundError != null) {
            throw new IllegalStateException("virtual outbound failed", outboundError);
        }
    }

    private VirtualPipe openVirtualPipe(ProtoInitializer initializer, VrtSoConfig config, int addressId) throws IOException {
        Queue<Object> inbound = new ConcurrentLinkedQueue<>();
        Queue<Object> outbound = new ConcurrentLinkedQueue<>();
        Queue<Throwable> inboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> outboundErrors = new ConcurrentLinkedQueue<>();
        VrtChannel channel = (VrtChannel) this.neta.connectSync(new VrtSocketAddress(addressId), initializer, config);
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> {
            Object data = payload.getData();
            if (data != null) {
                inbound.offer(data);
            }
        });
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, payload -> {
            Object data = payload.getData();
            if (data == null) {
                return;
            }
            if (data instanceof ByteBuf) {
                outbound.offer(((ByteBuf) data).retain());
            } else {
                outbound.offer(data);
            }
        });
        channel.subscribe(p -> p.isInbound() && !p.isSuccess(), SubscribeMode.SYNC, payload -> inboundErrors.offer(payload.getError()));
        channel.subscribe(p -> p.isOutbound() && !p.isSuccess(), SubscribeMode.SYNC, payload -> outboundErrors.offer(payload.getError()));
        return new VirtualPipe(channel, inbound, outbound, inboundErrors, outboundErrors);
    }

    private static byte[] buildPostRequest(byte[] bodyBytes) {
        String request = "POST /api/users HTTP/1.1\r\n"
                + "Host: api.example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Accept: application/json\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + new String(bodyBytes, StandardCharsets.UTF_8);
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeRequest(byte[] bodyBytes) {
        String request = "POST /api/bulk/users HTTP/1.1\r\n"
                + "Host: api.example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Accept: application/json\r\n"
                + "X-Trace-Id: agg-benchmark-round0\r\n"
                + "X-Client: neta-performance\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + new String(bodyBytes, StandardCharsets.UTF_8);
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildSimpleResponse(byte[] bodyBytes) {
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Server: Neta/1.0\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + new String(bodyBytes, StandardCharsets.UTF_8);
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeResponse(byte[] bodyBytes) {
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Server: Neta/1.0\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "X-Trace-Id: resp-agg-benchmark\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + new String(bodyBytes, StandardCharsets.UTF_8);
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeRequestBody() {
        StringBuilder body = new StringBuilder();
        body.append("{\"users\":[");
        for (int i = 0; i < 18; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"id\":").append(i)
                    .append(",\"name\":\"user").append(i)
                    .append("\",\"email\":\"user").append(i)
                    .append("@example.com\",\"enabled\":true}");
        }
        body.append("]}");
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildLargeResponseBody() {
        StringBuilder body = new StringBuilder();
        body.append("{\"items\":[");
        for (int i = 0; i < 24; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"id\":").append(i)
                    .append(",\"title\":\"item-").append(i)
                    .append("\",\"price\":").append(100 + i)
                    .append(",\"stock\":").append(1000 - i)
                    .append('}');
        }
        body.append("]}");
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[][] splitBytes(byte[] source, int[] prefixSizes) {
        int totalPrefix = 0;
        for (int i = 0; i < prefixSizes.length; i++) {
            if (prefixSizes[i] <= 0) {
                throw new IllegalArgumentException("chunk size must be positive");
            }
            totalPrefix += prefixSizes[i];
        }
        if (totalPrefix >= source.length) {
            throw new IllegalArgumentException("prefix chunk sizes consume the whole payload");
        }

        byte[][] chunks = new byte[prefixSizes.length + 1][];
        int offset = 0;
        for (int i = 0; i < prefixSizes.length; i++) {
            int size = prefixSizes[i];
            byte[] chunk = new byte[size];
            System.arraycopy(source, offset, chunk, 0, size);
            chunks[i] = chunk;
            offset += size;
        }

        int tailSize = source.length - offset;
        byte[] tail = new byte[tailSize];
        System.arraycopy(source, offset, tail, 0, tailSize);
        chunks[chunks.length - 1] = tail;
        return chunks;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(HttpAggregatorBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }

    private static final class VirtualPipe {
        private final VrtChannel       channel;
        private final Queue<Object>    inbound;
        private final Queue<Object>    outbound;
        private final Queue<Throwable> inboundErrors;
        private final Queue<Throwable> outboundErrors;

        private VirtualPipe(VrtChannel channel, Queue<Object> inbound, Queue<Object> outbound, Queue<Throwable> inboundErrors, Queue<Throwable> outboundErrors) {
            this.channel = channel;
            this.inbound = inbound;
            this.outbound = outbound;
            this.inboundErrors = inboundErrors;
            this.outboundErrors = outboundErrors;
        }

        private VrtChannel channel() {
            return this.channel;
        }

        private Queue<Object> inbound() {
            return this.inbound;
        }

        private Queue<Throwable> inboundErrors() {
            return this.inboundErrors;
        }

        private Queue<Throwable> outboundErrors() {
            return this.outboundErrors;
        }

        private void reset() {
            clearQueue(this.inbound);
            clearQueue(this.outbound);
            this.inboundErrors.clear();
            this.outboundErrors.clear();
        }

        private static void clearQueue(Queue<Object> queue) {
            Object item;
            while ((item = queue.poll()) != null) {
                releaseQueueItem(item);
            }
        }

        private static void releaseQueueItem(Object item) {
            if (item instanceof ByteBuf) {
                ByteBuf buffer = (ByteBuf) item;
                if (!buffer.isFree()) {
                    buffer.release();
                }
                return;
            }
            if (item instanceof HttpObject) {
                ((HttpObject) item).release();
                return;
            }
            if (item instanceof ReferenceCounted) {
                ((ReferenceCounted) item).release();
            }
        }
    }
}