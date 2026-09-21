/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCounted;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.openjdk.jmh.annotations.*;
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
    private static final int                MAX_CONTENT_LENGTH         = 1024 * 1024;
    private static final byte[]             POST_BODY_BYTES            = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30,\"city\":\"Shanghai\",\"bio\":\"aggregator benchmark payload\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[]             SIMPLE_RESPONSE_BODY_BYTES = "Hello, Aggregator!".getBytes(StandardCharsets.UTF_8);
    private static final byte[]             LARGE_REQUEST_BODY_BYTES;
    private static final byte[]             LARGE_RESPONSE_BODY_BYTES;
    private static final byte[]             POST_REQUEST_BYTES;
    private static final byte[]             LARGE_REQUEST_BYTES;
    private static final byte[]             SIMPLE_RESPONSE_BYTES;
    private static final byte[]             LARGE_RESPONSE_BYTES;
    private static final byte[][]           POST_REQUEST_STREAMS;
    private static final byte[][]           LARGE_REQUEST_STREAMS;
    private static final byte[][]           SIMPLE_RESPONSE_STREAMS;
    private static final byte[][]           LARGE_RESPONSE_STREAMS;
    private              NetManager         neta;
    private              VirtualPipe        requestAggregatorPipe;
    private              VirtualPipe        responseAggregatorPipe;
    private              EmbeddedChannel    nettyRequestChannel;
    private              EmbeddedChannel    nettyResponseChannel;
    private              LeakMetricSnapshot before;

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
        this.nettyRequestChannel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder(), new io.netty.handler.codec.http.HttpObjectAggregator(MAX_CONTENT_LENGTH));
        this.nettyResponseChannel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseDecoder(), new io.netty.handler.codec.http.HttpObjectAggregator(MAX_CONTENT_LENGTH));
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
        this.nettyRequestChannel.finishAndReleaseAll();
        this.nettyResponseChannel.finishAndReleaseAll();
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
    public int neta_aggregatePostRequest() throws Throwable {
        return aggregateNetaRequest(this.requestAggregatorPipe, POST_REQUEST_STREAMS);
    }

    @Benchmark
    public int netty_aggregatePostRequest() {
        return aggregateNettyRequest(POST_REQUEST_STREAMS);
    }

    @Benchmark
    public int neta_aggregateLargeRequest() throws Throwable {
        return aggregateNetaRequest(this.requestAggregatorPipe, LARGE_REQUEST_STREAMS);
    }

    @Benchmark
    public int netty_aggregateLargeRequest() {
        return aggregateNettyRequest(LARGE_REQUEST_STREAMS);
    }

    @Benchmark
    public int neta_aggregateSimpleResponse() throws Throwable {
        return aggregateNetaResponse(this.responseAggregatorPipe, SIMPLE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int netty_aggregateSimpleResponse() {
        return aggregateNettyResponse(SIMPLE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int neta_aggregateLargeResponse() throws Throwable {
        return aggregateNetaResponse(this.responseAggregatorPipe, LARGE_RESPONSE_STREAMS);
    }

    @Benchmark
    public int netty_aggregateLargeResponse() {
        return aggregateNettyResponse(LARGE_RESPONSE_STREAMS);
    }

    private static int aggregateNetaRequest(VirtualPipe pipe, byte[][] chunks) throws Throwable {
        Object[] out = null;
        for (int i = 0; i < chunks.length; i++) {
            out = pipe.channel().receiveDataAndReturning(ByteBuf.wrap(chunks[i]));
        }
        return releaseAggregatedReturned(out, true);
    }

    private static int aggregateNetaResponse(VirtualPipe pipe, byte[][] chunks) throws Throwable {
        Object[] out = null;
        for (int i = 0; i < chunks.length; i++) {
            out = pipe.channel().receiveDataAndReturning(ByteBuf.wrap(chunks[i]));
        }
        return releaseAggregatedReturned(out, false);
    }

    private int aggregateNettyRequest(byte[][] chunks) {
        feedNettyInbound(this.nettyRequestChannel, chunks);
        return releaseAggregatedNettyInbound(this.nettyRequestChannel, true);
    }

    private int aggregateNettyResponse(byte[][] chunks) {
        feedNettyInbound(this.nettyResponseChannel, chunks);
        return releaseAggregatedNettyInbound(this.nettyResponseChannel, false);
    }

    private static void feedNettyInbound(EmbeddedChannel channel, byte[][] chunks) {
        for (int i = 0; i < chunks.length; i++) {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(chunks[i]));
        }
    }

    private static int releaseAggregatedReturned(Object[] out, boolean request) {
        int observed = 0;
        int count = 0;
        if (out != null) {
            for (Object item : out) {
                observed += measureAggregatedVirtual(item, request);
                count++;
            }
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

    private VirtualPipe openVirtualPipe(ProtoInitializer initializer, VrtSoConfig config, int addressId) throws IOException {
        VrtChannel channel = (VrtChannel) this.neta.connectSync(new VrtSocketAddress(addressId), initializer, config);
        return new VirtualPipe(channel);
    }

    private static byte[] buildPostRequest(byte[] bodyBytes) {
        String request = "POST /api/users HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Accept: application/json\r\n" + "Connection: keep-alive\r\n" + "\r\n" + new String(bodyBytes, StandardCharsets.UTF_8);
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeRequest(byte[] bodyBytes) {
        String request = "POST /api/bulk/users HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Accept: application/json\r\n" + "X-Trace-Id: agg-benchmark-round0\r\n" + "X-Client: neta-performance\r\n" + "Connection: keep-alive\r\n" + "\r\n" + new String(bodyBytes, StandardCharsets.UTF_8);
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildSimpleResponse(byte[] bodyBytes) {
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain; charset=UTF-8\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Server: Neta/1.0\r\n" + "Connection: keep-alive\r\n" + "\r\n" + new String(bodyBytes, StandardCharsets.UTF_8);
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeResponse(byte[] bodyBytes) {
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json; charset=UTF-8\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Server: Neta/1.0\r\n" + "Cache-Control: no-cache, no-store, must-revalidate\r\n" + "X-Trace-Id: resp-agg-benchmark\r\n" + "Connection: keep-alive\r\n" + "\r\n" + new String(bodyBytes, StandardCharsets.UTF_8);
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] buildLargeRequestBody() {
        StringBuilder body = new StringBuilder();
        body.append("{\"users\":[");
        for (int i = 0; i < 18; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"id\":").append(i).append(",\"name\":\"user").append(i).append("\",\"email\":\"user").append(i).append("@example.com\",\"enabled\":true}");
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
            body.append("{\"id\":").append(i).append(",\"title\":\"item-").append(i).append("\",\"price\":").append(100 + i).append(",\"stock\":").append(1000 - i).append('}');
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
        private final VrtChannel channel;

        private VirtualPipe(VrtChannel channel) {
            this.channel = channel;
        }

        private VrtChannel channel() {
            return this.channel;
        }
    }
}
