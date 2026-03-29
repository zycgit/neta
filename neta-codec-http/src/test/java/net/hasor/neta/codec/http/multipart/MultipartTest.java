/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.multipart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the multipart package:
 * {@link MultipartDecoder}, {@link MultipartEncoder}, {@link DefaultFileUpload}.
 */
public class MultipartTest {

    // =========================================================================
    // Helper
    // =========================================================================

    /** Reads all readable bytes from a ByteBuf as a UTF-8 string. */
    private static String toString(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.UTF_8);
    }

    private static ByteBuf toBuf(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return buf;
    }

    private static ByteBuf toBuf(String text) {
        return toBuf(text.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // DefaultFileUpload
    // =========================================================================

    private static void writeStr(java.io.ByteArrayOutputStream bos, String s) {
        try {
            bos.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeBytes(java.io.ByteArrayOutputStream bos, byte[] data) {
        try {
            bos.write(data);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testDefaultFileUpload_basicFields() {
        ByteBuf content = toBuf("hello");
        DefaultFileUpload fu = new DefaultFileUpload("field1", "file.txt", "text/plain", content, null);
        assertEquals("field1", fu.name());
        assertEquals("file.txt", fu.filename());
        assertEquals("text/plain", fu.contentType());
        assertEquals(5, fu.content().readableBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultFileUpload_nullNameThrows() {
        new DefaultFileUpload(null, null, null, toBuf("x"), null);
    }

    // =========================================================================
    // MultipartDecoder.extractBoundary
    // =========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultFileUpload_nullContentThrows() {
        new DefaultFileUpload("field", null, null, null, null);
    }

    @Test
    public void testDefaultFileUpload_header() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/octet-stream");
        headers.put("x-custom", "value");
        DefaultFileUpload fu = new DefaultFileUpload("f", null, "application/octet-stream", toBuf("data"), headers);
        assertEquals("application/octet-stream", fu.header("content-type"));
        assertEquals("value", fu.header("x-custom"));
        assertNull(fu.header("missing"));
    }

    @Test
    public void testExtractBoundary_simple() {
        assertEquals("myboundary", MultipartDecoder.extractBoundary("multipart/form-data; boundary=myboundary"));
    }

    @Test
    public void testExtractBoundary_quoted() {
        assertEquals("myboundary", MultipartDecoder.extractBoundary("multipart/form-data; boundary=\"myboundary\""));
    }

    @Test
    public void testExtractBoundary_extraSpaces() {
        // boundary param without spaces around '='
        assertEquals("b1", MultipartDecoder.extractBoundary("multipart/form-data; boundary=b1"));
    }

    // =========================================================================
    // MultipartDecoder.decode – single text field
    // =========================================================================

    @Test
    public void testExtractBoundary_nullInput() {
        assertNull(MultipartDecoder.extractBoundary(null));
    }

    @Test
    public void testExtractBoundary_noBoundary() {
        assertNull(MultipartDecoder.extractBoundary("application/json"));
    }

    @Test
    public void testDecode_singleTextField() {
        String boundary = "testboundary";
        String body = "--testboundary\r\n" + "Content-Disposition: form-data; name=\"username\"\r\n" + "\r\n" + "alice\r\n" + "--testboundary--\r\n";
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(body), boundary);
        assertEquals(1, parts.size());
        FileUpload fu = parts.get(0);
        assertEquals("username", fu.name());
        assertNull(fu.filename());
        assertNull(fu.contentType());
        String value = toString(fu.content());
        assertEquals("alice", value);
    }

    @Test
    public void testDecode_singleFileField() {
        String boundary = "bound";
        String body = "--bound\r\n" + "Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n" + "Content-Type: text/plain\r\n" + "\r\n" + "file content here\r\n" + "--bound--\r\n";
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(body), boundary);
        assertEquals(1, parts.size());
        FileUpload fu = parts.get(0);
        assertEquals("file", fu.name());
        assertEquals("hello.txt", fu.filename());
        assertEquals("text/plain", fu.contentType());
        assertEquals("file content here", toString(fu.content()));
    }

    @Test
    public void testDecode_multipleParts() {
        String boundary = "bound";
        String body = "--bound\r\n" + "Content-Disposition: form-data; name=\"field1\"\r\n" + "\r\n" + "value1\r\n" + "--bound\r\n" + "Content-Disposition: form-data; name=\"field2\"\r\n" + "\r\n" + "value2\r\n" + "--bound--\r\n";
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(body), boundary);
        assertEquals(2, parts.size());
        assertEquals("field1", parts.get(0).name());
        assertEquals("value1", toString(parts.get(0).content()));
        assertEquals("field2", parts.get(1).name());
        assertEquals("value2", toString(parts.get(1).content()));
    }

    @Test
    public void testDecode_emptyBody() {
        List<FileUpload> parts = MultipartDecoder.decode(ByteBuf.EMPTY, "boundary");
        assertTrue(parts.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecode_nullBoundaryThrows() {
        MultipartDecoder.decode(toBuf("body"), null);
    }

    // =========================================================================
    // MultipartEncoder
    // =========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testDecode_emptyBoundaryThrows() {
        MultipartDecoder.decode(toBuf("body"), "");
    }

    @Test
    public void testDecode_binaryContent() {
        String boundary = "binbound";
        byte[] fileData = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE };
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeStr(bos, "--binbound\r\n");
        writeStr(bos, "Content-Disposition: form-data; name=\"bin\"; filename=\"data.bin\"\r\n");
        writeStr(bos, "Content-Type: application/octet-stream\r\n");
        writeStr(bos, "\r\n");
        writeBytes(bos, fileData);
        writeStr(bos, "\r\n--binbound--\r\n");
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(bos.toByteArray()), boundary);
        assertEquals(1, parts.size());
        FileUpload fu = parts.get(0);
        assertEquals("bin", fu.name());
        assertEquals("data.bin", fu.filename());
        assertEquals(fileData.length, fu.content().readableBytes());
        byte[] result = new byte[fileData.length];
        fu.content().readBytes(result, 0, result.length);
        assertArrayEquals(fileData, result);
    }

    @Test
    public void testEncoder_contentType() {
        MultipartEncoder enc = new MultipartEncoder("testbound", StandardCharsets.UTF_8);
        assertEquals("multipart/form-data; boundary=testbound", enc.contentType());
    }

    @Test
    public void testEncoder_singleField() {
        MultipartEncoder enc = new MultipartEncoder("b1", StandardCharsets.UTF_8);
        enc.addField("name", "alice");
        byte[] encoded = enc.encode();
        String body = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(body.contains("--b1\r\n"));
        assertTrue(body.contains("Content-Disposition: form-data; name=\"name\""));
        assertTrue(body.contains("\r\nalice\r\n"));
        assertTrue(body.contains("--b1--\r\n"));
    }

    // =========================================================================
    // Round-trip: Encoder → Decoder
    // =========================================================================

    @Test
    public void testEncoder_singleFile() {
        MultipartEncoder enc = new MultipartEncoder("b2", StandardCharsets.UTF_8);
        enc.addFile("avatar", "photo.png", "image/png", new byte[] { 1, 2, 3 });
        byte[] encoded = enc.encode();
        String body = new String(encoded, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("filename=\"photo.png\""));
        assertTrue(body.contains("Content-Type: image/png"));
    }

    @Test
    public void testEncoder_multipleParts() {
        MultipartEncoder enc = new MultipartEncoder("mb", StandardCharsets.UTF_8);
        enc.addField("f1", "v1").addField("f2", "v2");
        byte[] encoded = enc.encode();
        String body = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"f1\""));
        assertTrue(body.contains("\r\nv1\r\n"));
        assertTrue(body.contains("name=\"f2\""));
        assertTrue(body.contains("\r\nv2\r\n"));
    }

    @Test
    public void testRoundTrip_textField() {
        MultipartEncoder enc = new MultipartEncoder("rt1", StandardCharsets.UTF_8);
        enc.addField("username", "alice");
        byte[] encoded = enc.encode();

        String boundary = MultipartDecoder.extractBoundary(enc.contentType());
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(encoded), boundary);

        assertEquals(1, parts.size());
        assertEquals("username", parts.get(0).name());
        assertEquals("alice", toString(parts.get(0).content()));
    }

    @Test
    public void testRoundTrip_fileUpload() {
        byte[] fileData = "PNG IMAGE DATA".getBytes(StandardCharsets.UTF_8);
        MultipartEncoder enc = new MultipartEncoder("rt2", StandardCharsets.UTF_8);
        enc.addFile("photo", "img.png", "image/png", fileData);
        byte[] encoded = enc.encode();

        String boundary = MultipartDecoder.extractBoundary(enc.contentType());
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(encoded), boundary);

        assertEquals(1, parts.size());
        FileUpload fu = parts.get(0);
        assertEquals("photo", fu.name());
        assertEquals("img.png", fu.filename());
        assertEquals("image/png", fu.contentType());
        assertEquals("PNG IMAGE DATA", toString(fu.content()));
    }

    @Test
    public void testRoundTrip_multipleFields() {
        MultipartEncoder enc = new MultipartEncoder("rt3", StandardCharsets.UTF_8);
        enc.addField("a", "alpha").addField("b", "beta").addField("c", "gamma");
        byte[] encoded = enc.encode();

        String boundary = MultipartDecoder.extractBoundary(enc.contentType());
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(encoded), boundary);

        assertEquals(3, parts.size());
        assertEquals("alpha", toString(parts.get(0).content()));
        assertEquals("beta", toString(parts.get(1).content()));
        assertEquals("gamma", toString(parts.get(2).content()));
    }

    @Test
    public void testRoundTrip_addPart() {
        ByteBuf content = toBuf("data from part");
        DefaultFileUpload part = new DefaultFileUpload("file", "out.dat", "application/octet-stream", content, null);

        MultipartEncoder enc = new MultipartEncoder("rt4", StandardCharsets.UTF_8);
        enc.addPart(part);
        byte[] encoded = enc.encode();

        String boundary = MultipartDecoder.extractBoundary(enc.contentType());
        List<FileUpload> parts = MultipartDecoder.decode(toBuf(encoded), boundary);

        assertEquals(1, parts.size());
        assertEquals("file", parts.get(0).name());
        assertEquals("out.dat", parts.get(0).filename());
        assertEquals("data from part", toString(parts.get(0).content()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Test
    public void testEncoder_nullBoundaryThrows() {
        try {
            new MultipartEncoder(null, StandardCharsets.UTF_8);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // pass
        }
    }

    @Test
    public void testEncoder_emptyBoundaryThrows() {
        try {
            new MultipartEncoder("", StandardCharsets.UTF_8);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // pass
        }
    }
}
