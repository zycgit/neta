package net.hasor.neta.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.multipart.MultipartDecoder;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark: Multipart Encoding and Decoding.
 * Compares Neta multipart codec performance against Netty.
 */
@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class MultipartBenchmark {

    // ========================= Test Data =========================

    private static final String BOUNDARY = "----WebKitFormBoundaryABC123";

    // Simple multipart body with 2 text fields
    private static final String SIMPLE_MULTIPART_BODY = "------WebKitFormBoundaryABC123\r\n" + "Content-Disposition: form-data; name=\"username\"\r\n" + "\r\n" + "alice\r\n" + "------WebKitFormBoundaryABC123\r\n" + "Content-Disposition: form-data; name=\"email\"\r\n" + "\r\n" + "alice@example.com\r\n" + "------WebKitFormBoundaryABC123--\r\n";

    // Complex multipart body with text + file
    private static final byte[] FILE_CONTENT;
    private static final String COMPLEX_MULTIPART_BODY;
    private static final byte[] SIMPLE_BODY_BYTES;
    private static final byte[] COMPLEX_BODY_BYTES;

    static {
        // 4KB file content
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            fc.append("0123456789");
        }
        FILE_CONTENT = fc.toString().getBytes(StandardCharsets.UTF_8);

        COMPLEX_MULTIPART_BODY = "------WebKitFormBoundaryABC123\r\n" + "Content-Disposition: form-data; name=\"title\"\r\n" + "\r\n" + "My Document\r\n" + "------WebKitFormBoundaryABC123\r\n" + "Content-Disposition: form-data; name=\"description\"\r\n" + "\r\n" + "This is a test file upload for benchmarking purposes.\r\n" + "------WebKitFormBoundaryABC123\r\n" + "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" + "Content-Type: text/plain\r\n" + "\r\n" + new String(FILE_CONTENT, StandardCharsets.UTF_8) + "\r\n" + "------WebKitFormBoundaryABC123--\r\n";
        SIMPLE_BODY_BYTES = SIMPLE_MULTIPART_BODY.getBytes(StandardCharsets.UTF_8);
        COMPLEX_BODY_BYTES = COMPLEX_MULTIPART_BODY.getBytes(StandardCharsets.UTF_8);
    }

    // ========================= Neta Multipart Decode =========================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(MultipartBenchmark.class.getSimpleName()).build();
        new Runner(opt).run();
    }

    @Benchmark
    public int neta_decodeSimpleMultipart() {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(SIMPLE_BODY_BYTES.length, Integer.MAX_VALUE);
        body.writeBytes(SIMPLE_BODY_BYTES, 0, SIMPLE_BODY_BYTES.length);
        body.markWriter();
        List<FileUpload> parts = MultipartDecoder.decode(body, BOUNDARY);
        int size = parts.size();
        for (FileUpload part : parts) {
            part.content().free();
        }
        body.free();
        return size;
    }

    @Benchmark
    public int netty_decodeSimpleMultipart() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/upload", Unpooled.wrappedBuffer(SIMPLE_BODY_BYTES));
        request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
        request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, SIMPLE_BODY_BYTES.length);

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(request);
        List<InterfaceHttpData> bodyData = decoder.getBodyHttpDatas();
        int size = bodyData.size();
        decoder.destroy();
        request.release();
        return size;
    }

    @Benchmark
    public int neta_decodeComplexMultipart() {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(COMPLEX_BODY_BYTES.length, Integer.MAX_VALUE);
        body.writeBytes(COMPLEX_BODY_BYTES, 0, COMPLEX_BODY_BYTES.length);
        body.markWriter();
        List<FileUpload> parts = MultipartDecoder.decode(body, BOUNDARY);
        int size = parts.size();
        for (FileUpload part : parts) {
            part.content().free();
        }
        body.free();
        return size;
    }

    // ========================= Neta Multipart Encode =========================

    @Benchmark
    public int netty_decodeComplexMultipart() {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/upload", Unpooled.wrappedBuffer(COMPLEX_BODY_BYTES));
        request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
        request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, COMPLEX_BODY_BYTES.length);

        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(request);
        List<InterfaceHttpData> bodyData = decoder.getBodyHttpDatas();
        int size = bodyData.size();
        decoder.destroy();
        request.release();
        return size;
    }

    @Benchmark
    public byte[] neta_encodeSimpleMultipart() {
        MultipartEncoder encoder = new MultipartEncoder(BOUNDARY, StandardCharsets.UTF_8);
        encoder.addField("username", "alice");
        encoder.addField("email", "alice@example.com");
        return encoder.encode();
    }

    @Benchmark
    public io.netty.buffer.ByteBuf netty_encodeSimpleMultipart() throws Exception {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/upload");
        io.netty.handler.codec.http.multipart.HttpPostRequestEncoder encoder = new io.netty.handler.codec.http.multipart.HttpPostRequestEncoder(request, true);
        encoder.addBodyAttribute("username", "alice");
        encoder.addBodyAttribute("email", "alice@example.com");
        io.netty.handler.codec.http.HttpRequest finalRequest = encoder.finalizeRequest();
        io.netty.buffer.ByteBuf content = null;
        if (finalRequest instanceof io.netty.handler.codec.http.FullHttpRequest) {
            content = ((io.netty.handler.codec.http.FullHttpRequest) finalRequest).content().copy();
        }
        encoder.cleanFiles();
        request.release();
        if (content != null) {
            content.release();
        }
        return content;
    }

    @Benchmark
    public byte[] neta_encodeFileMultipart() {
        MultipartEncoder encoder = new MultipartEncoder(BOUNDARY, StandardCharsets.UTF_8);
        encoder.addField("title", "My Document");
        encoder.addField("description", "This is a test file upload for benchmarking purposes.");
        encoder.addFile("file", "test.txt", "text/plain", FILE_CONTENT);
        return encoder.encode();
    }

    // ========================= Boundary Extraction =========================

    @Benchmark
    public io.netty.buffer.ByteBuf netty_encodeFileMultipart() throws Exception {
        io.netty.handler.codec.http.DefaultFullHttpRequest request = new io.netty.handler.codec.http.DefaultFullHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.POST, "/upload");
        io.netty.handler.codec.http.multipart.HttpPostRequestEncoder encoder = new io.netty.handler.codec.http.multipart.HttpPostRequestEncoder(request, true);
        encoder.addBodyAttribute("title", "My Document");
        encoder.addBodyAttribute("description", "This is a test file upload for benchmarking purposes.");
        io.netty.handler.codec.http.multipart.MemoryFileUpload fileUpload = new io.netty.handler.codec.http.multipart.MemoryFileUpload("file", "test.txt", "text/plain", "binary", null, FILE_CONTENT.length);
        fileUpload.setContent(Unpooled.wrappedBuffer(FILE_CONTENT));
        encoder.addBodyHttpData(fileUpload);
        io.netty.handler.codec.http.HttpRequest finalRequest = encoder.finalizeRequest();
        io.netty.buffer.ByteBuf content = null;
        if (finalRequest instanceof io.netty.handler.codec.http.FullHttpRequest) {
            content = ((io.netty.handler.codec.http.FullHttpRequest) finalRequest).content().copy();
        }
        encoder.cleanFiles();
        request.release();
        if (content != null) {
            content.release();
        }
        return content;
    }

    // ========================= Main =========================

    @Benchmark
    public String neta_extractBoundary() {
        return MultipartDecoder.extractBoundary("multipart/form-data; boundary=----WebKitFormBoundaryABC123");
    }
}
