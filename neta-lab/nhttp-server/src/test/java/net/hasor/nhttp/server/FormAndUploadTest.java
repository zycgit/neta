/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufAllocatorMetric;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;
import net.hasor.nhttp.server.connector.BackpressureStrategy;
import net.hasor.nhttp.server.internal.DefaultSessionManager;
import net.hasor.nhttp.server.internal.StreamingServletRequest;

/**
 * Tests for form submission and file upload functionality in the nhttp module.
 * <p>
 * <b>Layer 1</b>: Unit tests operating directly on {@link StreamingServletRequest} with
 * constructed {@link FullHttpRequest} objects. Uses VrtChannel only as the required
 * {@link net.hasor.neta.channel.NetChannel} parameter — no network I/O, pure parsing logic.
 * <p>
 * <b>Layer 2</b>: Pipeline integration tests using VrtChannel + VrtTransfer, sending
 * raw HTTP bytes through the full {@link NetaHttpServer} pipeline.
 */
public class FormAndUploadTest {

    private static final DefaultSessionManager SESSION_MANAGER = new DefaultSessionManager();

    // =====================================================================
    //  Shared Helpers
    // =====================================================================

    /** Creates a minimal VrtChannel used to create request adapters without real network I/O. */
    private static VrtChannel createMockChannel(NetManager neta) throws IOException {
        return (VrtChannel) neta.connectSync(new VrtSocketAddress(99), ctx -> {
        }, VrtSoConfig.asServer());
    }

    /** Creates a URL-encoded form POST request. */
    private static FullHttpRequest buildFormRequest(String uri, String formBody) {
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = ByteBuf.wrap(bodyBytes);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
        request.setHeader(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    /** Creates a multipart/form-data POST request using MultipartEncoder. */
    private static FullHttpRequest buildMultipartRequest(String uri, MultipartEncoder encoder) {
        byte[] bodyBytes = encoder.encode();
        ByteBuf content = ByteBuf.wrap(bodyBytes);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, encoder.contentType());
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
        request.setHeader(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    private static void releaseQuietly(StreamingServletRequest request, FullHttpRequest httpRequest) {
        if (request != null) {
            request.release();
        }
        if (httpRequest != null) {
            httpRequest.release();
        }
    }

    // =====================================================================
    //  Pipeline test helpers (same as NetaHttpServerTest)
    // =====================================================================

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static ByteBuf toByteBuf(byte[] raw) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(raw.length);
        buf.writeBytes(raw, 0, raw.length);
        buf.markWriter();
        return buf;
    }

    private static Queue<String> subscribeAsString(VrtChannel channel) {
        Queue<String> queue = new ConcurrentLinkedQueue<>();
        channel.subscribe(d -> {
            Object data = d.getData();
            if (data instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) data;
                if (buf.readableBytes() > 0) {
                    queue.offer(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
                }
            }
        });
        return queue;
    }

    private static String collectStrings(Queue<String> queue) {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = queue.poll()) != null) {
            sb.append(msg);
        }
        return sb.toString();
    }

    // =========================================================================
    //  Layer 1: Unit Tests — StreamingServletRequest parsing (no network I/O)
    // =========================================================================

    // --- URL-encoded form parameter tests ---

    @Test
    public void test_urlEncoded_basicFormParams() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);
            httpReq = buildFormRequest("/api/form", "username=alice&email=alice%40example.com&message=Hello+World");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertFalse("url-encoded should not be multipart", req.isMultipart());
            assertEquals("alice", req.getParameter("username"));
            assertEquals("alice@example.com", req.getParameter("email"));
            assertEquals("Hello World", req.getParameter("message"));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_urlEncoded_multipleValues() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);
            httpReq = buildFormRequest("/api/form", "color=red&color=green&color=blue");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            List<String> colors = req.getParameterValues("color");
            assertNotNull(colors);
            assertEquals(3, colors.size());
            assertEquals("red", colors.get(0));
            assertEquals("green", colors.get(1));
            assertEquals("blue", colors.get(2));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_urlEncoded_queryAndBodyMerged() throws Exception {
        NetManager neta = new NetManager();
        DefaultFullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);
            String formBody = "body_param=from_body";
            byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
            httpReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/form?query_param=from_query", ByteBuf.wrap(bodyBytes));
            httpReq.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
            httpReq.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
            httpReq.setHeader(HttpHeaderNames.HOST, "localhost");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            Map<String, List<String>> params = req.getParameterMap();
            assertEquals("from_query", params.get("query_param").get(0));
            assertEquals("from_body", params.get("body_param").get(0));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_urlEncoded_specialCharacters() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);
            // name=张三, msg=hello & world = 你好世界!
            httpReq = buildFormRequest("/api/form", "name=%E5%BC%A0%E4%B8%89&msg=hello+%26+world+%3D+%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C%21");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertEquals("张三", req.getParameter("name"));
            assertEquals("hello & world = 你好世界!", req.getParameter("msg"));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_urlEncoded_emptyBody() throws Exception {
        NetManager neta = new NetManager();
        DefaultFullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);
            httpReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/form");
            httpReq.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
            httpReq.setHeader(HttpHeaderNames.HOST, "localhost");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertTrue("Empty body should have empty param map", req.getParameterMap().isEmpty());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Multipart detection ---

    @Test
    public void test_multipart_detection() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest multiReq = null;
        StreamingServletRequest req1 = null;
        DefaultFullHttpRequest jsonReq = null;
        StreamingServletRequest req2 = null;
        DefaultFullHttpRequest getReq = null;
        StreamingServletRequest req3 = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            // ① multipart → true
            MultipartEncoder enc = new MultipartEncoder();
            enc.addField("name", "test");
            multiReq = buildMultipartRequest("/api/upload", enc);
            req1 = StreamingServletRequest.fromFullHttpRequest(multiReq, channel, false, SESSION_MANAGER);
            assertTrue("multipart/form-data should be multipart", req1.isMultipart());

            // ② application/json → false
            jsonReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/data");
            jsonReq.setHeader(HttpHeaderNames.CONTENT_TYPE, "application/json");
            jsonReq.setHeader(HttpHeaderNames.HOST, "localhost");
            req2 = StreamingServletRequest.fromFullHttpRequest(jsonReq, channel, false, SESSION_MANAGER);
            assertFalse("JSON should not be multipart", req2.isMultipart());

            // ③ GET (no content-type) → false
            getReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            getReq.setHeader(HttpHeaderNames.HOST, "localhost");
            req3 = StreamingServletRequest.fromFullHttpRequest(getReq, channel, false, SESSION_MANAGER);
            assertFalse("GET should not be multipart", req3.isMultipart());
        } finally {
            releaseQuietly(req1, multiReq);
            releaseQuietly(req2, jsonReq);
            releaseQuietly(req3, getReq);
            neta.shutdown();
        }
    }

    // --- Multipart single file upload ---

    @Test
    public void test_multipart_singleFile() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            byte[] fileContent = "Hello, this is test file content.".getBytes(StandardCharsets.UTF_8);
            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addFile("file1", "test.txt", "text/plain", fileContent);

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertTrue(req.isMultipart());

            List<FileUpload> parts = req.getFileUploads();
            assertNotNull(parts);
            assertEquals(1, parts.size());

            FileUpload part = parts.get(0);
            assertEquals("file1", part.name());
            assertEquals("test.txt", part.filename());
            assertTrue(part.contentType().contains("text/plain"));
            assertEquals(fileContent.length, part.content().readableBytes());

            byte[] received = part.content().asByteArray();
            assertArrayEquals(fileContent, received);
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Multipart: multiple files + form fields ---

    @Test
    public void test_multipart_multipleFilesAndFields() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            byte[] file1 = "File 1 content".getBytes(StandardCharsets.UTF_8);
            byte[] file2 = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE };

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("description", "My test upload");
            encoder.addFile("file1", "readme.txt", "text/plain", file1);
            encoder.addFile("file2", "data.bin", "application/octet-stream", file2);

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertTrue(req.isMultipart());

            // text field accessible via getParameter
            assertEquals("My test upload", req.getParameter("description"));

            // all 3 parts (1 field + 2 files)
            List<FileUpload> parts = req.getFileUploads();
            assertNotNull(parts);
            assertEquals(3, parts.size());

            // locate file parts
            FileUpload f1 = null, f2 = null;
            for (FileUpload p : parts) {
                if ("file1".equals(p.name()) && p.filename() != null)
                    f1 = p;
                if ("file2".equals(p.name()) && p.filename() != null)
                    f2 = p;
            }

            assertNotNull("file1 should be present", f1);
            assertEquals("readme.txt", f1.filename());
            assertEquals(file1.length, f1.content().readableBytes());

            assertNotNull("file2 should be present", f2);
            assertEquals("data.bin", f2.filename());
            assertArrayEquals(file2, f2.content().asByteArray());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Multipart: getFileUpload(fieldName) lookup ---

    @Test
    public void test_multipart_getFileUploadByName() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addFile("myfile", "lookup.txt", "text/plain", "lookup data".getBytes(StandardCharsets.UTF_8));
            encoder.addField("extra", "value");

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            // find file by field name
            FileUpload found = req.getFileUpload("myfile");
            assertNotNull(found);
            assertEquals("lookup.txt", found.filename());

            // non-existent
            assertNull(req.getFileUpload("nonexistent"));

            // form field also findable (filename == null)
            FileUpload fieldPart = req.getFileUpload("extra");
            assertNotNull(fieldPart);
            assertNull("Plain field part should not have filename", fieldPart.filename());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Multipart: fields only (no files) ---

    @Test
    public void test_multipart_fieldsOnlyNoFiles() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("name", "Bob");
            encoder.addField("age", "30");

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertTrue(req.isMultipart());
            assertEquals("Bob", req.getParameter("name"));
            assertEquals("30", req.getParameter("age"));

            int fileCount = 0;
            for (FileUpload p : req.getFileUploads()) {
                if (p.filename() != null)
                    fileCount++;
            }
            assertEquals("No file parts expected", 0, fileCount);
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Multipart: large binary file ---

    @Test
    public void test_multipart_largeFile() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            byte[] bigContent = new byte[100 * 1024]; // 100 KB
            for (int i = 0; i < bigContent.length; i++) {
                bigContent[i] = (byte) (i & 0xFF);
            }

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addFile("bigfile", "large.dat", "application/octet-stream", bigContent);

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            FileUpload part = req.getFileUpload("bigfile");
            assertNotNull(part);
            assertEquals("large.dat", part.filename());
            assertEquals(bigContent.length, part.content().readableBytes());
            assertArrayEquals(bigContent, part.content().asByteArray());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Non-multipart POST ---

    @Test
    public void test_notMultipart_plainTextPost() throws Exception {
        NetManager neta = new NetManager();
        DefaultFullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            byte[] bodyBytes = "just plain text".getBytes(StandardCharsets.UTF_8);
            httpReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/upload", ByteBuf.wrap(bodyBytes));
            httpReq.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            httpReq.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
            httpReq.setHeader(HttpHeaderNames.HOST, "localhost");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertFalse(req.isMultipart());
            assertTrue(req.getFileUploads().isEmpty());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Caching behavior ---

    @Test
    public void test_multipart_cachingBehavior() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addFile("f", "a.txt", "text/plain", "data".getBytes(StandardCharsets.UTF_8));

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            List<FileUpload> first = req.getFileUploads();
            List<FileUpload> second = req.getFileUploads();
            assertSame("getFileUploads() should return the same cached list", first, second);
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_multipart_streamingDecodeDoesNotMaterializeRawBody() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("description", "streaming");
            encoder.addFile("file1", "hello.txt", "text/plain", "Hello World!".getBytes(StandardCharsets.UTF_8));

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertEquals(2, req.getFileUploads().size());

            Field bodyField = StreamingServletRequest.class.getDeclaredField("body");
            bodyField.setAccessible(true);
            assertNull("Multipart streaming decode should not cache a full raw body buffer", bodyField.get(req));

            try {
                req.getBody();
                fail("Expected raw body access to be unavailable after streaming multipart parsing");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Raw request body is unavailable"));
            }
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Empty multipart body ---

    @Test
    public void test_multipart_emptyBody() throws Exception {
        NetManager neta = new NetManager();
        DefaultFullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            httpReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/upload");
            httpReq.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=----TestBoundary123");
            httpReq.setHeader(HttpHeaderNames.HOST, "localhost");

            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertTrue(req.isMultipart());
            assertTrue("Empty body should yield empty parts", req.getFileUploads().isEmpty());
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    // --- Parameter map includes multipart text fields but NOT file parts ---

    @Test
    public void test_multipart_parameterMapIncludesFieldsOnly() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("username", "charlie");
            encoder.addField("role", "admin");
            encoder.addFile("avatar", "photo.jpg", "image/jpeg", new byte[] { 1, 2, 3 });

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            Map<String, List<String>> params = req.getParameterMap();
            assertNotNull(params.get("username"));
            assertEquals("charlie", params.get("username").get(0));
            assertNotNull(params.get("role"));
            assertEquals("admin", params.get("role").get(0));
            // file part should NOT appear as parameter
            assertNull("File part should not be in parameter map", params.get("avatar"));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }
    }

    @Test
    public void test_requestReleaseReclaimsMultipartBuffers() throws Exception {
        NetManager neta = new NetManager();
        FullHttpRequest httpReq = null;
        StreamingServletRequest req = null;
        ByteBufAllocatorMetric metric = ByteBufAllocator.DEFAULT.metric();
        long activeCountBefore = metric.totalActiveAllocations();
        long activeBytesBefore = metric.totalActiveBytes();
        try {
            VrtChannel channel = createMockChannel(neta);

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("username", "charlie");
            encoder.addField("role", "admin");
            encoder.addFile("avatar", "photo.jpg", "image/jpeg", new byte[] { 1, 2, 3, 4, 5, 6 });

            httpReq = buildMultipartRequest("/api/upload", encoder);
            req = StreamingServletRequest.fromFullHttpRequest(httpReq, channel, false, SESSION_MANAGER);

            assertEquals(3, req.getFileUploads().size());
            assertEquals("charlie", req.getParameter("username"));
        } finally {
            releaseQuietly(req, httpReq);
            neta.shutdown();
        }

        assertEquals(activeCountBefore, metric.totalActiveAllocations());
        assertEquals(activeBytesBefore, metric.totalActiveBytes());
    }

    // =========================================================================
    //  Layer 2: Pipeline Integration Tests (VrtChannel + VrtTransfer)
    // =========================================================================

    /**
     * Pipeline test: URL-encoded form POST processed through full HTTP pipeline.
     */
    @Test
    public void test_pipeline_formSubmit() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/api/form", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                String username = req.getParameter("username");
                String email = req.getParameter("email");
                resp.setContentType("text/plain");
                resp.write("user=" + username + ",email=" + email);
            }
        });
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            String body = "username=alice&email=alice%40example.com";
            String request = "POST /api/form HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
            client.sendData(toByteBuf(request)).get();
            Thread.sleep(500);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200: " + response, response.contains("200"));
            assertTrue("Response should contain user=alice: " + response, response.contains("user=alice"));
            assertTrue("Response should contain email: " + response, response.contains("email=alice@example.com"));
        } finally {
            neta.shutdown();
        }
    }

    /**
     * Pipeline test: Multipart file upload processed through full HTTP pipeline.
     */
    @Test
    public void test_pipeline_fileUpload() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.maxContentLength(10 * 1024 * 1024);
        httpServer.addServlet("/api/upload", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                if (!req.isMultipart()) {
                    resp.setStatus(400);
                    resp.write("not multipart");
                    return;
                }
                List<FileUpload> parts = req.getFileUploads();
                String desc = req.getParameter("description");
                int fileCount = 0;
                int totalSize = 0;
                for (FileUpload part : parts) {
                    if (part.filename() != null) {
                        fileCount++;
                        totalSize += part.content().readableBytes();
                    }
                }
                resp.setContentType("text/plain");
                resp.write("files=" + fileCount + ",size=" + totalSize + ",desc=" + desc);
            }
        });
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            // Build multipart body with encoder
            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("description", "TestUpload");
            encoder.addFile("file1", "hello.txt", "text/plain", "Hello World!".getBytes(StandardCharsets.UTF_8));    // 12 bytes
            encoder.addFile("file2", "data.bin", "application/octet-stream", new byte[] { 1, 2, 3, 4, 5 });                          // 5 bytes

            byte[] body = encoder.encode();
            String headerPart = "POST /api/upload HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: " + encoder.contentType() + "\r\n" + "Content-Length: " + body.length + "\r\n" + "\r\n";

            client.sendData(toByteBuf(headerPart)).get();
            for (int offset = 0; offset < body.length; offset += 7) {
                int len = Math.min(7, body.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(body, offset, chunk, 0, len);
                client.sendData(toByteBuf(chunk)).get();
            }
            Thread.sleep(500);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200: " + response, response.contains("200"));
            assertTrue("Response should contain files=2: " + response, response.contains("files=2"));
            assertTrue("Response should contain size=17: " + response, response.contains("size=17"));
            assertTrue("Response should contain desc=TestUpload: " + response, response.contains("desc=TestUpload"));
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void test_pipeline_largeUpload_slowConsumerDoesNot503() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.maxContentLength(10 * 1024 * 1024);
        httpServer.bodyQueueCapacity(2);
        httpServer.backpressureStrategy(BackpressureStrategy.limitedWait(250L));
        httpServer.addServlet("/api/upload", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }

                List<FileUpload> parts = req.getFileUploads();
                FileUpload part = req.getFileUpload("bigfile");
                resp.setContentType("text/plain");
                resp.write("parts=" + parts.size() + ",size=" + (part != null ? part.content().readableBytes() : -1));
            }
        });
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            byte[] payload = new byte[256 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0x7F);
            }

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("description", "SlowUpload");
            encoder.addFile("bigfile", "big.bin", "application/octet-stream", payload);

            byte[] body = encoder.encode();
            String headerPart = "POST /api/upload HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: " + encoder.contentType() + "\r\n" + "Content-Length: " + body.length + "\r\n" + "\r\n";
            client.sendData(toByteBuf(headerPart)).get();

            for (int offset = 0; offset < body.length; offset += 1024) {
                int len = Math.min(1024, body.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(body, offset, chunk, 0, len);
                client.sendData(toByteBuf(chunk)).get();
            }

            Thread.sleep(800L);

            String response = collectStrings(clientRcv);
            assertFalse("Response should not contain 503: " + response, response.contains("503 Service Unavailable"));
            assertTrue("Response should contain 200: " + response, response.contains("200"));
            assertTrue("Response should contain the uploaded size: " + response, response.contains("size=" + payload.length));
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void test_pipeline_oversizedUpload_returnsSingle413() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.maxContentLength(64 * 1024);
        httpServer.addServlet("/api/upload", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                req.getFileUploads();
                resp.setStatus(200);
                resp.write("unexpected");
            }
        });
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            byte[] payload = new byte[128 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0x7F);
            }

            MultipartEncoder encoder = new MultipartEncoder();
            encoder.addField("description", "TooLarge");
            encoder.addFile("bigfile", "big.bin", "application/octet-stream", payload);

            byte[] body = encoder.encode();
            String headerPart = "POST /api/upload HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: " + encoder.contentType() + "\r\n" + "Content-Length: " + body.length + "\r\n" + "\r\n";
            client.sendData(toByteBuf(headerPart)).get();

            for (int offset = 0; offset < body.length; offset += 1024) {
                int len = Math.min(1024, body.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(body, offset, chunk, 0, len);
                client.sendData(toByteBuf(chunk)).get();
            }

            Thread.sleep(800L);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 413: " + response, response.contains("413 Payload Too Large"));
            assertFalse("Response should not contain servlet success body: " + response, response.contains("unexpected"));

            Matcher matcher = Pattern.compile("413 Payload Too Large").matcher(response);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            assertEquals("Oversized upload should emit exactly one 413 response", 1, count);
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void test_pipeline_asyncSubscriberDoesNotSeeReleasedBuffers() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/api/form", new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.write("ok=" + req.getParameter("username"));
            }
        });
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = new ConcurrentLinkedQueue<>();
            Queue<Throwable> subscriberErrors = new ConcurrentLinkedQueue<>();
            client.subscribe(d -> {
                Object data = d.getData();
                if (data instanceof ByteBuf) {
                    try {
                        ByteBuf buf = (ByteBuf) data;
                        if (buf.readableBytes() > 0) {
                            clientRcv.offer(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
                        }
                    } catch (Throwable e) {
                        subscriberErrors.offer(e);
                    }
                }
            });

            String body = "username=alice";
            String request = "POST /api/form HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;

            for (int i = 0; i < 20; i++) {
                client.sendData(toByteBuf(request)).get();
            }

            Thread.sleep(500);

            assertTrue("async subscriber should not observe released ByteBuf: " + subscriberErrors, subscriberErrors.isEmpty());
            String response = collectStrings(clientRcv);
            assertTrue("Response should contain ok=alice: " + response, response.contains("ok=alice"));
        } finally {
            neta.shutdown();
        }
    }
}
