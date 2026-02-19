package net.hasor.neta.http;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.netty.channel.embedded.EmbeddedChannel;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoQueue;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark: HTTP Request/Response Encoding and Decoding.
 * Compares Neta codec performance against Netty.
 */
@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class HttpCodecBenchmark {

    // ========================= Test Data =========================

    // Simple GET request raw bytes
    private static final String SIMPLE_REQUEST_RAW = "GET /index.html HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Accept: text/html,application/xhtml+xml\r\n" + "Accept-Language: en-US,en;q=0.9\r\n" + "Connection: keep-alive\r\n" + "\r\n";

    // POST request with body
    private static final String POST_REQUEST_RAW = "POST /api/users HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: 52\r\n" + "Accept: application/json\r\n" + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9\r\n" + "\r\n" + "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}";

    // Simple 200 OK response raw bytes
    private static final String SIMPLE_RESPONSE_RAW = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html; charset=UTF-8\r\n" + "Content-Length: 13\r\n" + "Server: Neta/1.0\r\n" + "Connection: keep-alive\r\n" + "\r\n" + "Hello, World!";

    // Large response with many headers
    private static final String LARGE_RESPONSE_RAW;
    private static final byte[] SIMPLE_REQUEST_BYTES  = SIMPLE_REQUEST_RAW.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] POST_REQUEST_BYTES    = POST_REQUEST_RAW.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIMPLE_RESPONSE_BYTES = SIMPLE_RESPONSE_RAW.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LARGE_RESPONSE_BYTES  = LARGE_RESPONSE_RAW.getBytes(StandardCharsets.US_ASCII);

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: application/json; charset=UTF-8\r\n");
        sb.append("Server: Neta/1.0\r\n");
        sb.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        sb.append("Pragma: no-cache\r\n");
        sb.append("Expires: 0\r\n");
        sb.append("X-Request-Id: 550e8400-e29b-41d4-a716-446655440000\r\n");
        sb.append("X-RateLimit-Limit: 1000\r\n");
        sb.append("X-RateLimit-Remaining: 999\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE\r\n");
        sb.append("Vary: Accept-Encoding\r\n");
        // Generate a JSON body
        StringBuilder body = new StringBuilder();
        body.append("{\"users\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0)
                body.append(",");
            body.append("{\"id\":").append(i).append(",\"name\":\"user").append(i).append("\",\"email\":\"user").append(i).append("@example.com\"}");
        }
        body.append("]}");
        sb.append("Content-Length: ").append(body.length()).append("\r\n");
        sb.append("\r\n");
        sb.append(body);
        LARGE_RESPONSE_RAW = sb.toString();
    }

    // ========================= Neta Encode Benchmarks =========================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(HttpCodecBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }

    @Benchmark
    public void neta_encodeSimpleRequest() throws Throwable {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/index.html");
        request.headers().add("Host", "www.example.com");
        request.headers().add("Accept", "text/html,application/xhtml+xml");
        request.headers().add("Accept-Language", "en-US,en;q=0.9");
        request.headers().add("Connection", "keep-alive");

        HttpRequestEncoder encoder = new HttpRequestEncoder();
        ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
        src.offerMessage(request);
        src.sndSubmit();
        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            ByteBuf buf = dst.takeMessage();
            buf.free();
        }
        dst.rcvSubmit();
    }

    @Benchmark
    public void netty_encodeSimpleRequest() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/index.html");
        request.headers().add("Host", "www.example.com");
        request.headers().add("Accept", "text/html,application/xhtml+xml");
        request.headers().add("Accept-Language", "en-US,en;q=0.9");
        request.headers().add("Connection", "keep-alive");

        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder());
        channel.writeOutbound(request);
        io.netty.buffer.ByteBuf out = channel.readOutbound();
        if (out != null)
            out.release();
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_encodePostRequest() throws Throwable {
        byte[] bodyBytes = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
        ByteBuf body = ByteBuf.wrap(bodyBytes);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/users", body);
        request.headers().add("Host", "api.example.com");
        request.headers().add("Content-Type", "application/json");
        request.headers().add("Content-Length", String.valueOf(bodyBytes.length));
        request.headers().add("Accept", "application/json");
        request.headers().add("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");

        HttpRequestEncoder encoder = new HttpRequestEncoder();
        ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
        src.offerMessage(request);
        src.sndSubmit();
        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            ByteBuf buf = dst.takeMessage();
            buf.free();
        }
        dst.rcvSubmit();
    }

    @Benchmark
    public void netty_encodePostRequest() {
        byte[] bodyBytes = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/api/users", io.netty.buffer.Unpooled.wrappedBuffer(bodyBytes));
        request.headers().add("Host", "api.example.com");
        request.headers().add("Content-Type", "application/json");
        request.headers().add("Content-Length", String.valueOf(bodyBytes.length));
        request.headers().add("Accept", "application/json");
        request.headers().add("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");

        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder());
        channel.writeOutbound(request);
        io.netty.buffer.ByteBuf out = channel.readOutbound();
        if (out != null)
            out.release();
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_encodeSimpleResponse() throws Throwable {
        byte[] bodyBytes = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteBuf body = ByteBuf.wrap(bodyBytes);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
        response.headers().add("Content-Type", "text/html; charset=UTF-8");
        response.headers().add("Content-Length", String.valueOf(bodyBytes.length));
        response.headers().add("Server", "Neta/1.0");
        response.headers().add("Connection", "keep-alive");

        HttpResponseEncoder encoder = new HttpResponseEncoder();
        ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
        src.offerMessage(response);
        src.sndSubmit();
        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            ByteBuf buf = dst.takeMessage();
            buf.free();
        }
        dst.rcvSubmit();
    }

    // ========================= Neta Decode Benchmarks =========================

    @Benchmark
    public void netty_encodeSimpleResponse() {
        byte[] bodyBytes = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        io.netty.handler.codec.http.DefaultFullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpResponseStatus.OK, io.netty.buffer.Unpooled.wrappedBuffer(bodyBytes));
        response.headers().add("Content-Type", "text/html; charset=UTF-8");
        response.headers().add("Content-Length", String.valueOf(bodyBytes.length));
        response.headers().add("Server", "Neta/1.0");
        response.headers().add("Connection", "keep-alive");

        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseEncoder());
        channel.writeOutbound(response);
        io.netty.buffer.ByteBuf out = channel.readOutbound();
        if (out != null)
            out.release();
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_decodeSimpleRequest() throws Throwable {
        ByteBuf input = ByteBufAllocator.DEFAULT.buffer(SIMPLE_REQUEST_BYTES.length, Integer.MAX_VALUE);
        input.writeBytes(SIMPLE_REQUEST_BYTES, 0, SIMPLE_REQUEST_BYTES.length);
        input.markWriter();

        HttpRequestDecoder decoder = new HttpRequestDecoder();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            dst.takeMessage();
        }
        dst.rcvSubmit();
        input.free();
    }

    @Benchmark
    public void netty_decodeSimpleRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder());
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(SIMPLE_REQUEST_BYTES));
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) msg).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_decodePostRequest() throws Throwable {
        ByteBuf input = ByteBufAllocator.DEFAULT.buffer(POST_REQUEST_BYTES.length, Integer.MAX_VALUE);
        input.writeBytes(POST_REQUEST_BYTES, 0, POST_REQUEST_BYTES.length);
        input.markWriter();

        HttpRequestDecoder decoder = new HttpRequestDecoder();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            dst.takeMessage();
        }
        dst.rcvSubmit();
        input.free();
    }

    @Benchmark
    public void netty_decodePostRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder());
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(POST_REQUEST_BYTES));
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) msg).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_decodeSimpleResponse() throws Throwable {
        ByteBuf input = ByteBufAllocator.DEFAULT.buffer(SIMPLE_RESPONSE_BYTES.length, Integer.MAX_VALUE);
        input.writeBytes(SIMPLE_RESPONSE_BYTES, 0, SIMPLE_RESPONSE_BYTES.length);
        input.markWriter();

        HttpResponseDecoder decoder = new HttpResponseDecoder();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            dst.takeMessage();
        }
        dst.rcvSubmit();
        input.free();
    }

    @Benchmark
    public void netty_decodeSimpleResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseDecoder());
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(SIMPLE_RESPONSE_BYTES));
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) msg).release();
            }
        }
        channel.finishAndReleaseAll();
    }

    @Benchmark
    public void neta_decodeLargeResponse() throws Throwable {
        ByteBuf input = ByteBufAllocator.DEFAULT.buffer(LARGE_RESPONSE_BYTES.length, Integer.MAX_VALUE);
        input.writeBytes(LARGE_RESPONSE_BYTES, 0, LARGE_RESPONSE_BYTES.length);
        input.markWriter();

        HttpResponseDecoder decoder = new HttpResponseDecoder();
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            dst.takeMessage();
        }
        dst.rcvSubmit();
        input.free();
    }

    // ========================= Main =========================

    @Benchmark
    public void netty_decodeLargeResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseDecoder());
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(LARGE_RESPONSE_BYTES));
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) msg).release();
            }
        }
        channel.finishAndReleaseAll();
    }
}
