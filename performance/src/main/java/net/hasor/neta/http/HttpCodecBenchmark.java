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
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpMethod;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequestDecoder;
import net.hasor.neta.codec.http.HttpRequestEncoder;
import net.hasor.neta.codec.http.HttpResponseDecoder;
import net.hasor.neta.codec.http.HttpResponseEncoder;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.HttpVersion;
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
import net.hasor.neta.leak.LeakMetricSnapshot;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
public class HttpCodecBenchmark {
    private static final byte[] POST_BODY_BYTES = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE_BODY_BYTES = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIMPLE_REQUEST_BYTES;
    private static final byte[] POST_REQUEST_BYTES;
    private static final byte[] SIMPLE_RESPONSE_BYTES;
    private static final byte[] LARGE_RESPONSE_BYTES;
    private NetManager           neta;
    private VirtualPipe          requestEncoderPipe;
    private VirtualPipe          responseEncoderPipe;
    private VirtualPipe          requestDecoderPipe;
    private VirtualPipe          responseDecoderPipe;
    private LeakMetricSnapshot   before;

    static {
        SIMPLE_REQUEST_BYTES = ("GET /index.html HTTP/1.1\r\n"
                + "Host: www.example.com\r\n"
                + "Accept: text/html,application/xhtml+xml\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        POST_REQUEST_BYTES = ("POST /api/users HTTP/1.1\r\n"
                + "Host: api.example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + POST_BODY_BYTES.length + "\r\n"
                + "Accept: application/json\r\n"
                + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9\r\n"
                + "\r\n"
                + new String(POST_BODY_BYTES, StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);

        SIMPLE_RESPONSE_BYTES = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + RESPONSE_BODY_BYTES.length + "\r\n"
                + "Server: Neta/1.0\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + new String(RESPONSE_BODY_BYTES, StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);

        StringBuilder largeResponse = new StringBuilder();
        largeResponse.append("HTTP/1.1 200 OK\r\n");
        largeResponse.append("Content-Type: application/json; charset=UTF-8\r\n");
        largeResponse.append("Server: Neta/1.0\r\n");
        largeResponse.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        largeResponse.append("Pragma: no-cache\r\n");
        largeResponse.append("Expires: 0\r\n");
        largeResponse.append("X-Request-Id: 550e8400-e29b-41d4-a716-446655440000\r\n");
        largeResponse.append("X-RateLimit-Limit: 1000\r\n");
        largeResponse.append("X-RateLimit-Remaining: 999\r\n");
        largeResponse.append("Access-Control-Allow-Origin: *\r\n");
        largeResponse.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE\r\n");
        largeResponse.append("Vary: Accept-Encoding\r\n");

        StringBuilder body = new StringBuilder();
        body.append("{\"users\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"id\":").append(i)
                    .append(",\"name\":\"user").append(i)
                    .append("\",\"email\":\"user").append(i)
                    .append("@example.com\"}");
        }
        body.append("]}");
        largeResponse.append("Content-Length: ").append(body.length()).append("\r\n\r\n");
        largeResponse.append(body);
        LARGE_RESPONSE_BYTES = largeResponse.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.neta = new NetManager();
        this.requestEncoderPipe = this.openVirtualPipe(ctx -> ctx.addLastEncoder("req-encoder", new HttpRequestEncoder()), VrtSoConfig.asClient(), 101);
        this.responseEncoderPipe = this.openVirtualPipe(ctx -> ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder()), VrtSoConfig.asServer(), 102);
        this.requestDecoderPipe = this.openVirtualPipe(ctx -> ctx.addLastDecoder("req-decoder", new HttpRequestDecoder()), VrtSoConfig.asServer(), 103);
        this.responseDecoderPipe = this.openVirtualPipe(ctx -> ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder()), VrtSoConfig.asClient(), 104);
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
        this.requestEncoderPipe.reset();
        this.responseEncoderPipe.reset();
        this.requestDecoderPipe.reset();
        this.responseDecoderPipe.reset();
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        this.requestEncoderPipe.reset();
        this.responseEncoderPipe.reset();
        this.requestDecoderPipe.reset();
        this.responseDecoderPipe.reset();
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int neta_encodeSimpleRequest() throws Throwable {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/index.html");
        request.addHeader(HttpHeaderNames.HOST, "www.example.com");
        request.addHeader(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml");
        request.addHeader(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
        request.addHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        return encodeRequest(this.requestEncoderPipe, request);
    }

    @Benchmark
    public int netty_encodeSimpleRequest() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET,
                "/index.html");
        request.headers().add("Host", "www.example.com");
        request.headers().add("Accept", "text/html,application/xhtml+xml");
        request.headers().add("Accept-Language", "en-US,en;q=0.9");
        request.headers().add("Connection", "keep-alive");
        return encodeNettyRequest(request);
    }

    @Benchmark
    public int neta_encodePostRequest() throws Throwable {
        ByteBuf body = trackedBody(POST_BODY_BYTES);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/users", body);
        request.addHeader(HttpHeaderNames.HOST, "api.example.com");
        request.addHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        request.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(POST_BODY_BYTES.length));
        request.addHeader(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON);
        request.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9");
        return encodeRequest(this.requestEncoderPipe, request);
    }

    @Benchmark
    public int netty_encodePostRequest() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.POST,
                "/api/users",
                io.netty.buffer.Unpooled.wrappedBuffer(POST_BODY_BYTES));
        request.headers().add("Host", "api.example.com");
        request.headers().add("Content-Type", "application/json");
        request.headers().add("Content-Length", String.valueOf(POST_BODY_BYTES.length));
        request.headers().add("Accept", "application/json");
        request.headers().add("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");
        return encodeNettyRequest(request);
    }

    @Benchmark
    public int neta_encodeSimpleResponse() throws Throwable {
        ByteBuf body = trackedBody(RESPONSE_BODY_BYTES);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(RESPONSE_BODY_BYTES.length));
        response.addHeader(HttpHeaderNames.SERVER, "Neta/1.0");
        response.addHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        return encodeResponse(this.responseEncoderPipe, response);
    }

    @Benchmark
    public int netty_encodeSimpleResponse() {
        io.netty.handler.codec.http.DefaultFullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(RESPONSE_BODY_BYTES));
        response.headers().add("Content-Type", "text/html; charset=UTF-8");
        response.headers().add("Content-Length", String.valueOf(RESPONSE_BODY_BYTES.length));
        response.headers().add("Server", "Neta/1.0");
        response.headers().add("Connection", "keep-alive");
        return encodeNettyResponse(response);
    }

    @Benchmark
    public int neta_decodeSimpleRequest() throws Throwable {
        return decodeRequest(this.requestDecoderPipe, SIMPLE_REQUEST_BYTES);
    }

    @Benchmark
    public int netty_decodeSimpleRequest() {
        return decodeNettyRequest(SIMPLE_REQUEST_BYTES);
    }

    @Benchmark
    public int neta_decodePostRequest() throws Throwable {
        return decodeRequest(this.requestDecoderPipe, POST_REQUEST_BYTES);
    }

    @Benchmark
    public int netty_decodePostRequest() {
        return decodeNettyRequest(POST_REQUEST_BYTES);
    }

    @Benchmark
    public int neta_decodeSimpleResponse() throws Throwable {
        return decodeResponse(this.responseDecoderPipe, SIMPLE_RESPONSE_BYTES);
    }

    @Benchmark
    public int netty_decodeSimpleResponse() {
        return decodeNettyResponse(SIMPLE_RESPONSE_BYTES);
    }

    @Benchmark
    public int neta_decodeLargeResponse() throws Throwable {
        return decodeResponse(this.responseDecoderPipe, LARGE_RESPONSE_BYTES);
    }

    @Benchmark
    public int netty_decodeLargeResponse() {
        return decodeNettyResponse(LARGE_RESPONSE_BYTES);
    }

    private static int encodeRequest(VirtualPipe pipe, DefaultFullHttpRequest request) throws Throwable {
        pipe.reset();
        pipe.channel().sendData(request).get();
        assertNoErrors(pipe);
        return releaseVirtualOutbound(pipe.outbound());
    }

    private static int encodeResponse(VirtualPipe pipe, DefaultFullHttpResponse response) throws Throwable {
        pipe.reset();
        pipe.channel().sendData(response).get();
        assertNoErrors(pipe);
        return releaseVirtualOutbound(pipe.outbound());
    }

    private static int decodeRequest(VirtualPipe pipe, byte[] data) {
        pipe.reset();
        pipe.channel().receiveData(ByteBuf.wrap(data));
        assertNoErrors(pipe);
        return releaseVirtualInbound(pipe.inbound());
    }

    private static int decodeResponse(VirtualPipe pipe, byte[] data) {
        pipe.reset();
        pipe.channel().receiveData(ByteBuf.wrap(data));
        assertNoErrors(pipe);
        return releaseVirtualInbound(pipe.inbound());
    }

    private static int encodeNettyRequest(io.netty.handler.codec.http.DefaultFullHttpRequest request) {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder());
        try {
            channel.writeOutbound(request);
            return releaseNettyOutbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static int encodeNettyResponse(io.netty.handler.codec.http.DefaultFullHttpResponse response) {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseEncoder());
        try {
            channel.writeOutbound(response);
            return releaseNettyOutbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static int decodeNettyRequest(byte[] data) {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder());
        try {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(data));
            return releaseNettyInbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static int decodeNettyResponse(byte[] data) {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseDecoder());
        try {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(data));
            return releaseNettyInbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf trackedBody(byte[] data) {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(data.length, data.length);
        body.writeBytes(data, 0, data.length);
        body.markWriter();
        return body;
    }

    private static int releaseVirtualOutbound(Queue<Object> outbound) {
        int totalBytes = 0;
        Object item;
        while ((item = outbound.poll()) != null) {
            totalBytes += measureAndRelease(item);
        }
        return totalBytes;
    }

    private static int releaseVirtualInbound(Queue<Object> inbound) {
        int observed = 0;
        Object item;
        while ((item = inbound.poll()) != null) {
            observed += measureAndRelease(item);
        }
        return observed;
    }

    private static int measureAndRelease(Object item) {
        if (item instanceof ByteBuf) {
            ByteBuf buffer = (ByteBuf) item;
            int readable = buffer.readableBytes();
            if (!buffer.isFree()) {
                buffer.release();
            }
            return readable;
        }
        if (item instanceof HttpObject) {
            int observed = 1;
            if (item instanceof HttpContent) {
                ByteBuf body = ((HttpContent) item).content();
                if (body != null) {
                    observed += body.readableBytes();
                }
            }
            ((HttpObject) item).release();
            return observed;
        }
        return 1;
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

    private static int releaseNettyOutbound(EmbeddedChannel channel) {
        int totalBytes = 0;
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                totalBytes += ((io.netty.buffer.ByteBuf) msg).readableBytes();
            }
            releaseNettyObject(msg);
        }
        return totalBytes;
    }

    private static int releaseNettyInbound(EmbeddedChannel channel) {
        int observed = 0;
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            observed++;
            if (msg instanceof io.netty.handler.codec.http.HttpContent) {
                observed += ((io.netty.handler.codec.http.HttpContent) msg).content().readableBytes();
            }
            releaseNettyObject(msg);
        }
        return observed;
    }

    private static void releaseNettyObject(Object msg) {
        if (msg instanceof ReferenceCounted) {
            ((ReferenceCounted) msg).release();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(HttpCodecBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
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

        private Queue<Object> outbound() {
            return this.outbound;
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
                measureAndRelease(item);
            }
        }
    }
}
