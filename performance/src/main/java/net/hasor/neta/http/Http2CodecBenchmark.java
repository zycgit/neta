package net.hasor.neta.http;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoQueue;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import net.hasor.neta.codec.http2.HpackDecoder;
import net.hasor.neta.codec.http2.HpackEncoder;
import net.hasor.neta.codec.http2.Http2FrameDecoder;
import net.hasor.neta.codec.http2.Http2FrameEncoder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark: HTTP/2 HPACK Header Compression and Frame Encode/Decode.
 * Compares Neta codec performance against Netty.
 */
@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class Http2CodecBenchmark {

    // ========================= HPACK Encode Benchmarks =========================

    @Benchmark
    public byte[] neta_hpackEncodeSimpleHeaders() {
        HpackEncoder encoder = new HpackEncoder(4096, true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/index.html");
        headers.add(":scheme", "https");
        headers.add(":authority", "www.example.com");
        headers.add("accept", "text/html,application/xhtml+xml");
        headers.add("accept-language", "en-US,en;q=0.9");
        return encoder.encode(headers);
    }

    @Benchmark
    public io.netty.buffer.ByteBuf neta_hpackEncodeSimpleHeaders_netty() throws Exception {
        Http2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        Http2Headers headers = new DefaultHttp2Headers();
        headers.method("GET");
        headers.path("/index.html");
        headers.scheme("https");
        headers.authority("www.example.com");
        headers.add("accept", "text/html,application/xhtml+xml");
        headers.add("accept-language", "en-US,en;q=0.9");
        io.netty.buffer.ByteBuf out = Unpooled.buffer(256);
        encoder.encodeHeaders(1, headers, out);
        io.netty.buffer.ByteBuf result = out;
        // don't release - returned as blackhole
        return result;
    }

    @Benchmark
    public byte[] neta_hpackEncodeManyHeaders() {
        HpackEncoder encoder = new HpackEncoder(4096, true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "POST");
        headers.add(":path", "/api/v2/users");
        headers.add(":scheme", "https");
        headers.add(":authority", "api.example.com");
        headers.add("content-type", "application/json");
        headers.add("content-length", "1024");
        headers.add("accept", "application/json");
        headers.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw");
        headers.add("cache-control", "no-cache, no-store, must-revalidate");
        headers.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        headers.add("x-forwarded-for", "192.168.1.1, 10.0.0.1");
        headers.add("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");
        return encoder.encode(headers);
    }

    @Benchmark
    public io.netty.buffer.ByteBuf neta_hpackEncodeManyHeaders_netty() throws Exception {
        Http2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        Http2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path("/api/v2/users");
        headers.scheme("https");
        headers.authority("api.example.com");
        headers.add("content-type", "application/json");
        headers.add("content-length", "1024");
        headers.add("accept", "application/json");
        headers.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw");
        headers.add("cache-control", "no-cache, no-store, must-revalidate");
        headers.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        headers.add("x-forwarded-for", "192.168.1.1, 10.0.0.1");
        headers.add("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");
        io.netty.buffer.ByteBuf out = Unpooled.buffer(512);
        encoder.encodeHeaders(1, headers, out);
        return out;
    }

    // ========================= HPACK Decode Benchmarks =========================

    // Pre-encoded bytes for decode benchmarks (produced by HPACK encoder)
    private byte[]                  simpleHpackBytes;
    private byte[]                  manyHpackBytes;
    private io.netty.buffer.ByteBuf simpleHpackNettyBuf;
    private io.netty.buffer.ByteBuf manyHpackNettyBuf;

    @Setup(Level.Trial)
    public void setupHpack() throws Exception {
        // Encode with Neta
        HpackEncoder netaEncoder = new HpackEncoder(4096, true);

        HttpHeaders simpleHeaders = new HttpHeaders();
        simpleHeaders.add(":method", "GET");
        simpleHeaders.add(":path", "/index.html");
        simpleHeaders.add(":scheme", "https");
        simpleHeaders.add(":authority", "www.example.com");
        simpleHeaders.add("accept", "text/html,application/xhtml+xml");
        simpleHeaders.add("accept-language", "en-US,en;q=0.9");
        simpleHpackBytes = netaEncoder.encode(simpleHeaders);

        HttpHeaders manyHeaders = new HttpHeaders();
        manyHeaders.add(":method", "POST");
        manyHeaders.add(":path", "/api/v2/users");
        manyHeaders.add(":scheme", "https");
        manyHeaders.add(":authority", "api.example.com");
        manyHeaders.add("content-type", "application/json");
        manyHeaders.add("content-length", "1024");
        manyHeaders.add("accept", "application/json");
        manyHeaders.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw");
        manyHeaders.add("cache-control", "no-cache, no-store, must-revalidate");
        manyHeaders.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        manyHeaders.add("x-forwarded-for", "192.168.1.1, 10.0.0.1");
        manyHeaders.add("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");
        manyHpackBytes = netaEncoder.encode(manyHeaders);

        // Encode with Netty for Netty decode benchmark
        Http2HeadersEncoder nettyEncoder = new DefaultHttp2HeadersEncoder();

        Http2Headers nettySimple = new DefaultHttp2Headers();
        nettySimple.method("GET");
        nettySimple.path("/index.html");
        nettySimple.scheme("https");
        nettySimple.authority("www.example.com");
        nettySimple.add("accept", "text/html,application/xhtml+xml");
        nettySimple.add("accept-language", "en-US,en;q=0.9");
        simpleHpackNettyBuf = Unpooled.buffer(256);
        nettyEncoder.encodeHeaders(1, nettySimple, simpleHpackNettyBuf);

        Http2Headers nettyMany = new DefaultHttp2Headers();
        nettyMany.method("POST");
        nettyMany.path("/api/v2/users");
        nettyMany.scheme("https");
        nettyMany.authority("api.example.com");
        nettyMany.add("content-type", "application/json");
        nettyMany.add("content-length", "1024");
        nettyMany.add("accept", "application/json");
        nettyMany.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw");
        nettyMany.add("cache-control", "no-cache, no-store, must-revalidate");
        nettyMany.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        nettyMany.add("x-forwarded-for", "192.168.1.1, 10.0.0.1");
        nettyMany.add("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)");
        manyHpackNettyBuf = Unpooled.buffer(512);
        nettyEncoder.encodeHeaders(1, nettyMany, manyHpackNettyBuf);
    }

    @Benchmark
    public HttpHeaders neta_hpackDecodeSimpleHeaders() {
        HpackDecoder decoder = new HpackDecoder(4096, 65536);
        return decoder.decode(simpleHpackBytes, 0, simpleHpackBytes.length);
    }

    @Benchmark
    public Http2Headers neta_hpackDecodeSimpleHeaders_netty() throws Exception {
        Http2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
        io.netty.buffer.ByteBuf buf = simpleHpackNettyBuf.retainedSlice();
        Http2Headers result = decoder.decodeHeaders(1, buf);
        buf.release();
        return result;
    }

    @Benchmark
    public HttpHeaders neta_hpackDecodeManyHeaders() {
        HpackDecoder decoder = new HpackDecoder(4096, 65536);
        return decoder.decode(manyHpackBytes, 0, manyHpackBytes.length);
    }

    @Benchmark
    public Http2Headers neta_hpackDecodeManyHeaders_netty() throws Exception {
        Http2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
        io.netty.buffer.ByteBuf buf = manyHpackNettyBuf.retainedSlice();
        Http2Headers result = decoder.decodeHeaders(1, buf);
        buf.release();
        return result;
    }

    // ========================= HTTP/2 Frame Encode Benchmarks =========================

    @Benchmark
    public void neta_h2EncodeGetRequest() throws Throwable {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/api/data");
        request.headers().set(":authority", "www.example.com");
        request.headers().set(":scheme", "https");
        request.headers().set("accept", "application/json");
        request.headers().set("user-agent", "Neta/1.0");

        Http2FrameEncoder encoder = new Http2FrameEncoder(false);
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
    public void neta_h2EncodePostRequest() throws Throwable {
        byte[] bodyBytes = "{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
        ByteBuf body = ByteBuf.wrap(bodyBytes);

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/users", body);
        request.headers().set(":authority", "api.example.com");
        request.headers().set(":scheme", "https");
        request.headers().set("content-type", "application/json");
        request.headers().set("content-length", String.valueOf(bodyBytes.length));

        Http2FrameEncoder encoder = new Http2FrameEncoder(false);
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
    public void neta_h2EncodeResponse() throws Throwable {
        byte[] bodyBytes = "{\"status\":\"ok\",\"data\":{\"id\":1,\"name\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
        ByteBuf body = ByteBuf.wrap(bodyBytes);

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
        response.headers().set("content-type", "application/json");
        response.headers().set("content-length", String.valueOf(bodyBytes.length));
        response.headers().set("server", "Neta/1.0");

        Http2FrameEncoder encoder = new Http2FrameEncoder(true);
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

    // ========================= HTTP/2 Frame Decode Benchmarks =========================

    private byte[] encodedH2GetRequest;
    private byte[] encodedH2PostRequest;
    private byte[] encodedH2Response;

    @Setup(Level.Trial)
    public void setupH2Frames() throws Throwable {
        // Encode frames using Neta encoder for decode benchmarks
        {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/api/data");
            request.headers().set(":authority", "www.example.com");
            request.headers().set(":scheme", "https");
            request.headers().set("accept", "application/json");

            Http2FrameEncoder encoder = new Http2FrameEncoder(false);
            ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
            ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
            src.offerMessage(request);
            src.sndSubmit();
            encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
            dst.sndSubmit();
            encodedH2GetRequest = collectBytes(dst);
            dst.rcvSubmit();
        }
        {
            byte[] bodyBytes = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            ByteBuf body = ByteBuf.wrap(bodyBytes);
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/users", body);
            request.headers().set(":authority", "api.example.com");
            request.headers().set(":scheme", "https");
            request.headers().set("content-type", "application/json");
            request.headers().set("content-length", String.valueOf(bodyBytes.length));

            Http2FrameEncoder encoder = new Http2FrameEncoder(false);
            ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
            ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
            src.offerMessage(request);
            src.sndSubmit();
            encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
            dst.sndSubmit();
            encodedH2PostRequest = collectBytes(dst);
            dst.rcvSubmit();
        }
        {
            byte[] bodyBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ByteBuf body = ByteBuf.wrap(bodyBytes);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "application/json");
            response.headers().set("content-length", String.valueOf(bodyBytes.length));

            Http2FrameEncoder encoder = new Http2FrameEncoder(true);
            ProtoQueue<HttpObject> src = new ProtoQueue<>(-1);
            ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
            src.offerMessage(response);
            src.sndSubmit();
            encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
            dst.sndSubmit();
            encodedH2Response = collectBytes(dst);
            dst.rcvSubmit();
        }
    }

    private byte[] collectBytes(ProtoQueue<ByteBuf> dst) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while (dst.hasMore()) {
            ByteBuf buf = dst.takeMessage();
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes, 0, bytes.length);
            try {
                baos.write(bytes);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            buf.free();
        }
        return baos.toByteArray();
    }

    @Benchmark
    public void neta_h2DecodeGetRequest() throws Throwable {
        ByteBuf input = ByteBuf.wrap(encodedH2GetRequest);
        Http2FrameDecoder decoder = new Http2FrameDecoder(true);
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            HttpObject obj = dst.takeMessage();
            if (obj instanceof HttpContent) {
                ((HttpContent) obj).content().free();
            }
        }
        dst.rcvSubmit();
        decoder.onClose(StubProtoContext.INSTANCE);
    }

    @Benchmark
    public void neta_h2DecodePostRequest() throws Throwable {
        ByteBuf input = ByteBuf.wrap(encodedH2PostRequest);
        Http2FrameDecoder decoder = new Http2FrameDecoder(true);
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            HttpObject obj = dst.takeMessage();
            if (obj instanceof HttpContent) {
                ((HttpContent) obj).content().free();
            }
        }
        dst.rcvSubmit();
        decoder.onClose(StubProtoContext.INSTANCE);
    }

    @Benchmark
    public void neta_h2DecodeResponse() throws Throwable {
        ByteBuf input = ByteBuf.wrap(encodedH2Response);
        Http2FrameDecoder decoder = new Http2FrameDecoder(false);
        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
        ProtoQueue<HttpObject> dst = new ProtoQueue<>(-1);
        src.offerMessage(input);
        src.sndSubmit();
        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
        dst.sndSubmit();
        while (dst.hasMore()) {
            HttpObject obj = dst.takeMessage();
            if (obj instanceof HttpContent) {
                ((HttpContent) obj).content().free();
            }
        }
        dst.rcvSubmit();
        decoder.onClose(StubProtoContext.INSTANCE);
    }

    // ========================= QPACK Encode/Decode Benchmarks =========================

    @Benchmark
    public byte[] neta_qpackEncodeSimpleHeaders() {
        net.hasor.neta.codec.http3.QpackEncoder encoder = new net.hasor.neta.codec.http3.QpackEncoder(4096, false);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/index.html");
        headers.add(":scheme", "https");
        headers.add(":authority", "www.example.com");
        headers.add("accept", "text/html");
        return encoder.encode(headers);
    }

    @Benchmark
    public byte[] neta_qpackEncodeManyHeaders() {
        net.hasor.neta.codec.http3.QpackEncoder encoder = new net.hasor.neta.codec.http3.QpackEncoder(4096, false);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "POST");
        headers.add(":path", "/api/v2/users");
        headers.add(":scheme", "https");
        headers.add(":authority", "api.example.com");
        headers.add("content-type", "application/json");
        headers.add("content-length", "1024");
        headers.add("accept", "application/json");
        headers.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");
        headers.add("cache-control", "no-cache");
        headers.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        return encoder.encode(headers);
    }

    // Pre-encoded QPACK bytes
    private byte[] simpleQpackBytes;
    private byte[] manyQpackBytes;

    @Setup(Level.Trial)
    public void setupQpack() {
        net.hasor.neta.codec.http3.QpackEncoder encoder = new net.hasor.neta.codec.http3.QpackEncoder(4096, false);

        HttpHeaders simpleHeaders = new HttpHeaders();
        simpleHeaders.add(":method", "GET");
        simpleHeaders.add(":path", "/index.html");
        simpleHeaders.add(":scheme", "https");
        simpleHeaders.add(":authority", "www.example.com");
        simpleHeaders.add("accept", "text/html");
        simpleQpackBytes = encoder.encode(simpleHeaders);

        HttpHeaders manyHeaders = new HttpHeaders();
        manyHeaders.add(":method", "POST");
        manyHeaders.add(":path", "/api/v2/users");
        manyHeaders.add(":scheme", "https");
        manyHeaders.add(":authority", "api.example.com");
        manyHeaders.add("content-type", "application/json");
        manyHeaders.add("content-length", "1024");
        manyHeaders.add("accept", "application/json");
        manyHeaders.add("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9");
        manyHeaders.add("cache-control", "no-cache");
        manyHeaders.add("x-request-id", "550e8400-e29b-41d4-a716-446655440000");
        manyQpackBytes = encoder.encode(manyHeaders);
    }

    @Benchmark
    public HttpHeaders neta_qpackDecodeSimpleHeaders() {
        net.hasor.neta.codec.http3.QpackDecoder decoder = new net.hasor.neta.codec.http3.QpackDecoder(4096, 65536);
        return decoder.decode(simpleQpackBytes, 0, simpleQpackBytes.length);
    }

    @Benchmark
    public HttpHeaders neta_qpackDecodeManyHeaders() {
        net.hasor.neta.codec.http3.QpackDecoder decoder = new net.hasor.neta.codec.http3.QpackDecoder(4096, 65536);
        return decoder.decode(manyQpackBytes, 0, manyQpackBytes.length);
    }

    // ========================= HPACK vs QPACK Comparison =========================

    @Benchmark
    public byte[] neta_hpackVsQpack_hpackEncode() {
        HpackEncoder encoder = new HpackEncoder(4096, true);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/api/resource");
        headers.add(":scheme", "https");
        headers.add(":authority", "example.com");
        headers.add("accept", "application/json");
        headers.add("user-agent", "Neta/1.0");
        return encoder.encode(headers);
    }

    @Benchmark
    public byte[] neta_hpackVsQpack_qpackEncode() {
        net.hasor.neta.codec.http3.QpackEncoder encoder = new net.hasor.neta.codec.http3.QpackEncoder(4096, false);
        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/api/resource");
        headers.add(":scheme", "https");
        headers.add(":authority", "example.com");
        headers.add("accept", "application/json");
        headers.add("user-agent", "Neta/1.0");
        return encoder.encode(headers);
    }

    // ========================= QuicVarInt Benchmark =========================

    @Benchmark
    public byte[] neta_quicVarIntEncode() {
        byte[] result = new byte[0];
        result = net.hasor.neta.codec.quic.QuicVarInt.encode(0);
        result = net.hasor.neta.codec.quic.QuicVarInt.encode(63);
        result = net.hasor.neta.codec.quic.QuicVarInt.encode(16383);
        result = net.hasor.neta.codec.quic.QuicVarInt.encode(1073741823L);
        result = net.hasor.neta.codec.quic.QuicVarInt.encode(4611686018427387903L);
        return result;
    }

    private static final byte[] VARINT_1BYTE = net.hasor.neta.codec.quic.QuicVarInt.encode(37);
    private static final byte[] VARINT_2BYTE = net.hasor.neta.codec.quic.QuicVarInt.encode(15293);
    private static final byte[] VARINT_4BYTE = net.hasor.neta.codec.quic.QuicVarInt.encode(494878333);
    private static final byte[] VARINT_8BYTE = net.hasor.neta.codec.quic.QuicVarInt.encode(151288809941952652L);

    @Benchmark
    public long neta_quicVarIntDecode() {
        long r = 0;
        r += net.hasor.neta.codec.quic.QuicVarInt.decode(VARINT_1BYTE, 0)[0];
        r += net.hasor.neta.codec.quic.QuicVarInt.decode(VARINT_2BYTE, 0)[0];
        r += net.hasor.neta.codec.quic.QuicVarInt.decode(VARINT_4BYTE, 0)[0];
        r += net.hasor.neta.codec.quic.QuicVarInt.decode(VARINT_8BYTE, 0)[0];
        return r;
    }

    // ========================= Main =========================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(Http2CodecBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }
}
