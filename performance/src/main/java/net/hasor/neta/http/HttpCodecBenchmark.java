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
public class HttpCodecBenchmark {
    private static final byte[]             POST_BODY_BYTES             = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
    private static final byte[]             RESPONSE_BODY_BYTES         = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final byte[]             SIMPLE_REQUEST_BYTES;
    private static final String[]           SIMPLE_REQUEST_HEADER_NAMES = { "Host", "Accept", "Accept-Language", "Connection" };
    private static final byte[]             POST_REQUEST_BYTES;
    private static final byte[]             SIMPLE_RESPONSE_BYTES;
    private static final byte[]             LARGE_RESPONSE_BYTES;
    private              NetManager         neta;
    private              VirtualPipe        requestEncoderPipe;
    private              VirtualPipe        responseEncoderPipe;
    private              VirtualPipe        requestDecoderPipe;
    private              VirtualPipe        responseDecoderPipe;
    private              EmbeddedChannel    nettyRequestEncoder;
    private              EmbeddedChannel    nettyResponseEncoder;
    private              EmbeddedChannel    nettyRequestDecoder;
    private              EmbeddedChannel    nettyResponseDecoder;
    private              LeakMetricSnapshot before;

    static {
        SIMPLE_REQUEST_BYTES = ("GET /index.html HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Accept: text/html,application/xhtml+xml\r\n" + "Accept-Language: en-US,en;q=0.9\r\n" + "Connection: keep-alive\r\n" + "\r\n").getBytes(StandardCharsets.US_ASCII);

        POST_REQUEST_BYTES = ("POST /api/users HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + POST_BODY_BYTES.length + "\r\n" + "Accept: application/json\r\n" + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9\r\n" + "\r\n" + new String(POST_BODY_BYTES, StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);

        SIMPLE_RESPONSE_BYTES = ("HTTP/1.1 200 OK\r\n" + "Content-Type: text/html; charset=UTF-8\r\n" + "Content-Length: " + RESPONSE_BODY_BYTES.length + "\r\n" + "Server: Neta/1.0\r\n" + "Connection: keep-alive\r\n" + "\r\n" + new String(RESPONSE_BODY_BYTES, StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);

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
            body.append("{\"id\":").append(i).append(",\"name\":\"user").append(i).append("\",\"email\":\"user").append(i).append("@example.com\"}");
        }
        body.append("]}");
        largeResponse.append("Content-Length: ").append(body.length()).append("\r\n\r\n");
        largeResponse.append(body);
        LARGE_RESPONSE_BYTES = largeResponse.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.nettyRequestEncoder = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder());
        this.nettyResponseEncoder = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseEncoder());
        this.nettyRequestDecoder = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder());
        this.nettyResponseDecoder = new EmbeddedChannel(new io.netty.handler.codec.http.HttpResponseDecoder());
        this.neta = new NetManager();
        this.requestEncoderPipe = this.openVirtualPipe(ctx -> ctx.addLastEncoder("req-encoder", new HttpRequestEncoder()), VrtSoConfig.asClient(), 101);
        this.responseEncoderPipe = this.openVirtualPipe(ctx -> ctx.addLastEncoder("resp-encoder", new HttpResponseEncoder()), VrtSoConfig.asServer(), 102);
        this.requestDecoderPipe = this.openVirtualPipe(ctx -> ctx.addLastDecoder("req-decoder", new HttpRequestDecoder()), VrtSoConfig.asServer(), 103);
        this.responseDecoderPipe = this.openVirtualPipe(ctx -> ctx.addLastDecoder("resp-decoder", new HttpResponseDecoder()), VrtSoConfig.asClient(), 104);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        this.nettyRequestEncoder.finishAndReleaseAll();
        this.nettyResponseEncoder.finishAndReleaseAll();
        this.nettyRequestDecoder.finishAndReleaseAll();
        this.nettyResponseDecoder.finishAndReleaseAll();
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
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/index.html");
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
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/api/users", io.netty.buffer.Unpooled.wrappedBuffer(POST_BODY_BYTES));
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
        io.netty.handler.codec.http.DefaultFullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpResponseStatus.OK, io.netty.buffer.Unpooled.wrappedBuffer(RESPONSE_BODY_BYTES));
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
    public int neta_decodeSimpleRequestReadHeaders() throws Throwable {
        Object[] out = this.requestDecoderPipe.channel().receiveDataAndReturning(ByteBuf.wrap(SIMPLE_REQUEST_BYTES));
        int observed = 0;
        try {
            for (Object item : out) {
                if (item instanceof HttpHeaders) {
                    HttpHeaders headers = (HttpHeaders) item;
                    for (String name : SIMPLE_REQUEST_HEADER_NAMES) {
                        observed += headers.getString(name).hashCode();
                    }
                }
            }
            return observed;
        } finally {
            for (Object item : out) {
                if (item instanceof HttpObject) {
                    ((HttpObject) item).release();
                } else if (item instanceof ByteBuf) {
                    ((ByteBuf) item).release();
                }
            }
        }
    }

    @Benchmark
    public int netty_decodeSimpleRequestReadHeaders() {
        this.nettyRequestDecoder.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(SIMPLE_REQUEST_BYTES));
        int observed = 0;
        Object item;
        while ((item = this.nettyRequestDecoder.readInbound()) != null) {
            try {
                if (item instanceof io.netty.handler.codec.http.HttpMessage) {
                    io.netty.handler.codec.http.HttpHeaders headers = ((io.netty.handler.codec.http.HttpMessage) item).headers();
                    for (String name : SIMPLE_REQUEST_HEADER_NAMES) {
                        observed += headers.get(name).hashCode();
                    }
                }
            } finally {
                releaseNettyObject(item);
            }
        }
        return observed;
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

    @Benchmark
    public int neta_decodeHeaderCorpus(HeaderInputs inputs) throws Throwable {
        int observed = 0;
        VirtualPipe pipe = inputs.corpus.response ? this.responseDecoderPipe : this.requestDecoderPipe;
        for (byte[] packet : inputs.next().packets) {
            observed += decodeRequest(pipe, packet);
        }
        return observed;
    }

    @Benchmark
    public int netty_decodeHeaderCorpus(HeaderInputs inputs) {
        int observed = 0;
        EmbeddedChannel channel = inputs.corpus.response ? this.nettyResponseDecoder : this.nettyRequestDecoder;
        for (byte[] packet : inputs.next().packets) {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(packet));
            observed += releaseNettyInbound(channel);
        }
        return observed;
    }

    @State(Scope.Thread)
    public static class HeaderInputs {
        @Param({ "ordinary", "sameLength", "mixedCasePost", "fragmentedChunked", "response" })
        public  String           profile;
        private HttpHeaderCorpus corpus;
        private int              index;

        @Setup(Level.Trial)
        public void setup() {
            this.corpus = new HttpHeaderCorpus(this.profile);
            this.index = 0;
        }

        private HttpHeaderCorpus.Message next() {
            HttpHeaderCorpus.Message message = this.corpus.messages[this.index];
            this.index = (this.index + 1) % this.corpus.messages.length;
            return message;
        }
    }

    private static int encodeRequest(VirtualPipe pipe, DefaultFullHttpRequest request) throws Throwable {
        return releaseReturned(pipe.channel().sendDataAndReturning(request));
    }

    private static int encodeResponse(VirtualPipe pipe, DefaultFullHttpResponse response) throws Throwable {
        return releaseReturned(pipe.channel().sendDataAndReturning(response));
    }

    private static int decodeRequest(VirtualPipe pipe, byte[] data) throws Throwable {
        return releaseReturned(pipe.channel().receiveDataAndReturning(ByteBuf.wrap(data)));
    }

    private static int decodeResponse(VirtualPipe pipe, byte[] data) throws Throwable {
        return releaseReturned(pipe.channel().receiveDataAndReturning(ByteBuf.wrap(data)));
    }

    private int encodeNettyRequest(io.netty.handler.codec.http.DefaultFullHttpRequest request) {
        this.nettyRequestEncoder.writeOutbound(request);
        return releaseNettyOutbound(this.nettyRequestEncoder);
    }

    private int encodeNettyResponse(io.netty.handler.codec.http.DefaultFullHttpResponse response) {
        this.nettyResponseEncoder.writeOutbound(response);
        return releaseNettyOutbound(this.nettyResponseEncoder);
    }

    private int decodeNettyRequest(byte[] data) {
        this.nettyRequestDecoder.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(data));
        return releaseNettyInbound(this.nettyRequestDecoder);
    }

    private int decodeNettyResponse(byte[] data) {
        this.nettyResponseDecoder.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(data));
        return releaseNettyInbound(this.nettyResponseDecoder);
    }

    private static ByteBuf trackedBody(byte[] data) {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(data.length, data.length);
        body.writeBytes(data, 0, data.length);
        body.markWriter();
        return body;
    }

    private static int releaseReturned(Object[] out) {
        int observed = 0;
        if (out != null) {
            for (Object item : out) {
                observed += measureAndRelease(item);
            }
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
        VrtChannel channel = (VrtChannel) this.neta.connectSync(new VrtSocketAddress(addressId), initializer, config);
        return new VirtualPipe(channel);
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
