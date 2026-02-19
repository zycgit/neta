/*
 * Multipart Example Test - validates the code examples from http_multipart.md
 */
package net.hasor.neta.example.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import net.hasor.neta.codec.http.multipart.DefaultFileUpload;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.multipart.MultipartDecoder;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests Multipart examples from http_multipart.md documentation.
 * Uses Neta HTTP server for multipart decoding and Java HttpURLConnection as client.
 */
public class HttpMultipartExampleTest {

    private NetManager neta;
    private int        port;

    private static void sendResponse(NetChannel channel, HttpStatus status, String body) {
        ByteBuf content = ByteBufAllocator.DEFAULT.buffer(256);
        content.writeString(body, StandardCharsets.UTF_8);
        content.markWriter();

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set("Content-Type", "text/plain; charset=utf-8");
        response.headers().set("Content-Length", String.valueOf(content.readableBytes()));
        channel.sendData(response);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null)
            return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    @Before
    public void setUp() throws Exception {
        port = findFreePort();
        neta = new NetManager();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    /**
     * Test: MultipartDecoder.extractBoundary (from http_multipart.md §MultipartDecoder API)
     */
    @Test
    public void testExtractBoundary() {
        // Standard Content-Type with boundary
        String ct = "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String boundary = MultipartDecoder.extractBoundary(ct);
        assertEquals("----WebKitFormBoundary7MA4YWxkTrZu0gW", boundary);

        // Quoted boundary
        String ctQuoted = "multipart/form-data; boundary=\"my-boundary\"";
        assertEquals("my-boundary", MultipartDecoder.extractBoundary(ctQuoted));

        // No boundary
        assertNull(MultipartDecoder.extractBoundary("application/json"));

        // null input
        assertNull(MultipartDecoder.extractBoundary(null));
    }

    /**
     * Test: MultipartEncoder basic usage (from http_multipart.md §MultipartEncoder 基本使用)
     * Encode fields and files, then decode and verify round-trip.
     */
    @Test
    public void testEncoderDecoderRoundTrip() {
        // Encode (from doc example)
        MultipartEncoder encoder = new MultipartEncoder();
        encoder.addField("username", "alice");
        encoder.addField("description", "My profile photo");
        encoder.addFile("avatar", "photo.png", "image/png", new byte[] { 1, 2, 3, 4, 5 });

        byte[] body = encoder.encode();
        String contentType = encoder.contentType();
        assertTrue(contentType.startsWith("multipart/form-data; boundary="));

        // Decode
        String boundary = MultipartDecoder.extractBoundary(contentType);
        assertNotNull(boundary);
        assertEquals(encoder.boundary(), boundary);

        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length);
        bodyBuf.writeBytes(body, 0, body.length);
        bodyBuf.markWriter();

        List<FileUpload> parts = MultipartDecoder.decode(bodyBuf, boundary);
        assertEquals(3, parts.size());

        // Field: username
        assertEquals("username", parts.get(0).name());
        assertNull(parts.get(0).filename());
        ByteBuf content0 = parts.get(0).content();
        assertEquals("alice", content0.readString(content0.readableBytes(), StandardCharsets.UTF_8));

        // Field: description
        assertEquals("description", parts.get(1).name());
        assertNull(parts.get(1).filename());
        ByteBuf content1 = parts.get(1).content();
        assertEquals("My profile photo", content1.readString(content1.readableBytes(), StandardCharsets.UTF_8));

        // File: avatar
        assertEquals("avatar", parts.get(2).name());
        assertEquals("photo.png", parts.get(2).filename());
        assertEquals("image/png", parts.get(2).contentType());
        assertEquals(5, parts.get(2).content().readableBytes());
    }

    /**
     * Test: MultipartEncoder chaining and repeatable encode (from doc §编码器特性)
     */
    @Test
    public void testEncoderChaining() {
        MultipartEncoder encoder = new MultipartEncoder();
        // chain calls
        MultipartEncoder same = encoder.addField("a", "1").addField("b", "2").addFile("f", "f.txt", "text/plain", new byte[] { 65 });
        assertSame(encoder, same);

        // encode is repeatable
        byte[] body1 = encoder.encode();
        byte[] body2 = encoder.encode();
        assertArrayEquals(body1, body2);
    }

    /**
     * Test: MultipartEncoder with custom boundary (from doc §MultipartEncoder API)
     */
    @Test
    public void testEncoderCustomBoundary() {
        MultipartEncoder encoder = new MultipartEncoder("my-boundary-123", StandardCharsets.UTF_8);
        assertEquals("my-boundary-123", encoder.boundary());
        assertEquals("multipart/form-data; boundary=my-boundary-123", encoder.contentType());
    }

    /**
     * Test: MultipartDecoder edge cases (from doc §解码注意事项)
     */
    @Test
    public void testDecoderEdgeCases() {
        // null body returns empty list
        List<FileUpload> empty1 = MultipartDecoder.decode(null, "boundary");
        assertTrue(empty1.isEmpty());

        // empty body returns empty list
        ByteBuf emptyBuf = ByteBufAllocator.DEFAULT.buffer(0);
        emptyBuf.markWriter();
        List<FileUpload> empty2 = MultipartDecoder.decode(emptyBuf, "boundary");
        assertTrue(empty2.isEmpty());

        // null boundary throws
        try {
            MultipartDecoder.decode(emptyBuf, null);
            fail("Should throw IllegalArgumentException for null boundary");
        } catch (IllegalArgumentException e) {
            // expected
        }

        // empty boundary throws
        try {
            MultipartDecoder.decode(emptyBuf, "");
            fail("Should throw IllegalArgumentException for empty boundary");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /**
     * Test: FileUpload interface - files vs fields (from doc §判断文件还是字段)
     */
    @Test
    public void testFileUploadFieldVsFile() {
        // from doc: DefaultFileUpload for field
        ByteBuf fieldContent = ByteBufAllocator.DEFAULT.buffer(64);
        fieldContent.writeString("alice", StandardCharsets.UTF_8);
        fieldContent.markWriter();
        FileUpload field = new DefaultFileUpload("username", fieldContent);

        assertEquals("username", field.name());
        assertNull("field has no filename", field.filename());
        assertNull("field has no content-type", field.contentType());
        ByteBuf fc = field.content();
        assertEquals("alice", fc.readString(fc.readableBytes(), StandardCharsets.UTF_8));

        // from doc: DefaultFileUpload for file
        byte[] imageBytes = { (byte) 0x89, 0x50, 0x4E, 0x47 }; // PNG magic bytes
        ByteBuf fileContent = ByteBufAllocator.DEFAULT.buffer(1024);
        fileContent.writeBytes(imageBytes, 0, imageBytes.length);
        fileContent.markWriter();
        FileUpload file = new DefaultFileUpload("avatar", "photo.png", "image/png", fileContent, new java.util.LinkedHashMap<>());

        assertEquals("avatar", file.name());
        assertEquals("photo.png", file.filename());
        assertEquals("image/png", file.contentType());
        assertEquals(4, file.content().readableBytes());
    }

    // -- helpers --

    /**
     * Test: addPart with existing FileUpload (from doc §MultipartEncoder API)
     */
    @Test
    public void testAddPartFromFileUpload() {
        ByteBuf fieldBuf = ByteBufAllocator.DEFAULT.buffer(16);
        fieldBuf.writeString("hello", StandardCharsets.UTF_8);
        fieldBuf.markWriter();
        FileUpload original = new DefaultFileUpload("greeting", fieldBuf);

        MultipartEncoder encoder = new MultipartEncoder();
        encoder.addPart(original);
        encoder.addField("extra", "value");

        byte[] body = encoder.encode();
        String boundary = encoder.boundary();

        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length);
        bodyBuf.writeBytes(body, 0, body.length);
        bodyBuf.markWriter();

        List<FileUpload> parts = MultipartDecoder.decode(bodyBuf, boundary);
        assertEquals(2, parts.size());
        assertEquals("greeting", parts.get(0).name());
        assertEquals("extra", parts.get(1).name());
    }

    /**
     * Test: Full server-side file upload handling (from http_multipart.md §完整示例 文件上传服务器)
     * Uses Neta HTTP server to receive multipart upload from HttpURLConnection client.
     */
    @Test
    public void testServerSideMultipartUpload() throws Exception {
        // Pipeline from doc §文件上传服务器
        ProtoInitializer proto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), proto, SoConfig.TCP());

        // Server-side handling follows doc §基本使用 pattern
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            NetChannel channel = (NetChannel) payload.getSource();

            String contentType = request.headers().get("content-type");
            String boundary = MultipartDecoder.extractBoundary(contentType);

            if (boundary == null) {
                sendResponse(channel, HttpStatus.BAD_REQUEST, "Not a multipart request");
                return;
            }

            List<FileUpload> parts = MultipartDecoder.decode(request.content(), boundary);

            StringBuilder result = new StringBuilder();
            result.append("Received ").append(parts.size()).append(" parts:");

            for (FileUpload part : parts) {
                if (part.filename() != null) {
                    result.append(" file=").append(part.filename()).append("(").append(part.content().readableBytes()).append("b)");
                } else {
                    ByteBuf c = part.content();
                    String value = c.readString(c.readableBytes(), StandardCharsets.UTF_8);
                    result.append(" ").append(part.name()).append("=").append(value);
                }
            }

            sendResponse(channel, HttpStatus.OK, result.toString());
        });

        Thread.sleep(200);

        // Client: build multipart request using MultipartEncoder
        MultipartEncoder encoder = new MultipartEncoder();
        encoder.addField("title", "TestDoc");
        encoder.addField("category", "test");
        encoder.addFile("file1", "hello.txt", "text/plain", "Hello, World!".getBytes(StandardCharsets.UTF_8));

        byte[] body = encoder.encode();

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", encoder.contentType());
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        conn.getOutputStream().write(body);
        conn.getOutputStream().flush();

        assertEquals(200, conn.getResponseCode());
        String responseBody = readResponse(conn);
        assertTrue("Response should mention 3 parts: " + responseBody, responseBody.contains("3 parts"));
        assertTrue("Response should contain title=TestDoc: " + responseBody, responseBody.contains("title=TestDoc"));
        assertTrue("Response should contain category=test: " + responseBody, responseBody.contains("category=test"));
        assertTrue("Response should contain file=hello.txt: " + responseBody, responseBody.contains("file=hello.txt"));
        conn.disconnect();
    }

    /**
     * Test: Multiple files upload (from doc §完整示例)
     */
    @Test
    public void testMultipleFilesUpload() throws Exception {
        ProtoInitializer proto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), proto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            NetChannel channel = (NetChannel) payload.getSource();

            String contentType = request.headers().get("content-type");
            String boundary = MultipartDecoder.extractBoundary(contentType);

            List<FileUpload> parts = MultipartDecoder.decode(request.content(), boundary);

            int fileCount = 0;
            int fieldCount = 0;
            for (FileUpload part : parts) {
                if (part.filename() != null) {
                    fileCount++;
                } else {
                    fieldCount++;
                }
            }

            sendResponse(channel, HttpStatus.OK, "files=" + fileCount + ",fields=" + fieldCount);
        });

        Thread.sleep(200);

        // Client sends 2 files and 1 field
        MultipartEncoder encoder = new MultipartEncoder();
        encoder.addField("description", "test upload");
        encoder.addFile("file1", "a.txt", "text/plain", "content-a".getBytes(StandardCharsets.UTF_8));
        encoder.addFile("file2", "b.bin", "application/octet-stream", new byte[] { 0, 1, 2, 3 });

        byte[] body = encoder.encode();

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", encoder.contentType());
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        conn.getOutputStream().write(body);
        conn.getOutputStream().flush();

        assertEquals(200, conn.getResponseCode());
        String responseBody = readResponse(conn);
        assertTrue("Response should show files=2: " + responseBody, responseBody.contains("files=2"));
        assertTrue("Response should show fields=1: " + responseBody, responseBody.contains("fields=1"));
        conn.disconnect();
    }
}
