/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.request;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.SwapFileByteBuf;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpObject;

public class RequestHttpObjectBinaryBodyTest extends RequestHttpObjectTestSupport {
    @Test
    public void testKnownLengthStreamProducesMultipleHttpContentObjects() throws Exception {
        byte[] payload = binaryPayload(ContentBody.DEFAULT_STREAM_CHUNK_SIZE * 3 + 233);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/stream/known")
                .contentType("application/octet-stream")
                .post(ContentBody.stream(() -> new ByteArrayInputStream(payload), payload.length))
                .build());
        // @formatter:on
        try {
            assertEquals(String.valueOf(payload.length), headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
            assertTrue(contents(objects).size() > 1);
            assertEquals(md5Hex(payload), md5Hex(objects));
            assertEquals(crc32(payload), crc32(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testUnknownLengthStreamUsesChunkedTransferForHttp11() throws Exception {
        byte[] payload = binaryPayload(ContentBody.DEFAULT_STREAM_CHUNK_SIZE * 2 + 17);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/stream/unknown")
                .contentType("application/octet-stream")
                .post(ContentBody.stream(() -> new ByteArrayInputStream(payload)))
                .build());
        // @formatter:on
        try {
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("chunked", headers(objects).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertTrue(contents(objects).size() > 1);
            assertEquals(md5Hex(payload), md5Hex(objects));
            assertEquals(crc32(payload), crc32(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testInputStreamFactoryWithContentLengthRetainsFixedLength() throws Exception {
        byte[] payload = binaryPayload(321);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/stream/input-stream")
                .contentType("application/octet-stream")
                .post(ContentBody.stream(new ByteArrayInputStream(payload), payload.length))
                .build());
        // @formatter:on
        try {
            assertEquals(String.valueOf(payload.length), headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
            assertEquals(md5Hex(payload), md5Hex(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testByteBufBodyDoesNotSplit() throws Exception {
        byte[] payload = binaryPayload(4096);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer(payload.length);
        byteBuf.writeBytes(payload);
        byteBuf.markWriter();

        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/buffer")
                .contentType("application/octet-stream")
                .post(ContentBody.byteBuf(byteBuf))
                .build());
        // @formatter:on
        try {
            assertEquals(1, contents(objects).size());
            assertSame(contents(objects).get(0).content(), byteBuf);
            assertEquals(md5Hex(payload), md5Hex(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testByteBufStreamSourceCanUseChunkedTransfer() throws Exception {
        byte[] payload = binaryPayload(ContentBody.DEFAULT_STREAM_CHUNK_SIZE + 51);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer(payload.length);
        byteBuf.writeBytes(payload);
        byteBuf.markWriter();

        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/buffer/stream")
                .contentType("application/octet-stream")
                .post(ContentBody.stream(byteBuf))
                .build());
        // @formatter:on
        try {
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals("chunked", headers(objects).getString(HttpHeaderNames.TRANSFER_ENCODING));
            assertTrue(contents(objects).size() > 1);
            assertEquals(md5Hex(payload), md5Hex(objects));
        } finally {
            release(objects);
            byteBuf.free();
        }
    }

    @Test
    public void testFileBodyUsesSwapFileByteBufWithoutSplit() throws Exception {
        byte[] payload = binaryPayload(ContentBody.DEFAULT_STREAM_CHUNK_SIZE * 2 + 99);
        File tempFile = File.createTempFile("request-http-object", ".bin");
        Files.write(tempFile.toPath(), payload);
        tempFile.deleteOnExit();

        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/file")
                .contentType("application/octet-stream")
                .post(ContentBody.file(tempFile))
                .build());
        // @formatter:on
        try {
            assertEquals(1, contents(objects).size());
            assertTrue(contents(objects).get(0).content() instanceof SwapFileByteBuf);
            assertEquals(String.valueOf(payload.length), headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals(md5Hex(payload), md5Hex(objects));
            assertEquals(crc32(payload), crc32(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testZeroLengthBodyStillEmitsSingleTerminalContent() throws Exception {
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/empty")
                .post(ContentBody.bytes(new byte[0]))
                .build());
        // @formatter:on
        try {
            assertEquals("0", headers(objects).getString(HttpHeaderNames.CONTENT_LENGTH));
            assertEquals(1, contents(objects).size());
            assertEquals(0, contents(objects).get(0).content().readableBytes());
        } finally {
            release(objects);
        }
    }

    @Test
    public void testCustomChunkSizeSplitsStreamAsConfigured() throws Exception {
        byte[] payload = new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 };
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/stream/chunks")
                .post(StreamBody.builder().inputStream(new ByteArrayInputStream(payload)).contentLength(payload.length).chunkSize(3).build())
                .build());
        // @formatter:on
        try {
            List<HttpContent> contents = contents(objects);
            assertEquals(3, contents.size());
            assertEquals(3, contents.get(0).content().readableBytes());
            assertEquals(3, contents.get(1).content().readableBytes());
            assertEquals(2, contents.get(2).content().readableBytes());
            assertEquals(md5Hex(payload), md5Hex(objects));
        } finally {
            release(objects);
        }
    }

    @Test
    public void testHttp10UnknownLengthBodyDoesNotUseChunkedTransfer() throws Exception {
        byte[] payload = binaryPayload(25);
        // @formatter:off
        HttpObject[] objects = write(new Request.Builder()
                .url("http://127.0.0.1/stream/http10")
                .post(ContentBody.stream(() -> new ByteArrayInputStream(payload)))
                .build(), net.hasor.neta.codec.http.HttpVersion.HTTP_1_0);
        // @formatter:on
        try {
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.TRANSFER_ENCODING));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(headers(objects).containsHeader(HttpHeaderNames.CONNECTION));
            assertEquals(md5Hex(payload), md5Hex(objects));
        } finally {
            release(objects);
        }
    }

    private static byte[] binaryPayload(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        return payload;
    }

    private static String md5Hex(byte[] payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(payload);
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static long crc32(byte[] payload) {
        CRC32 crc32 = new CRC32();
        crc32.update(payload, 0, payload.length);
        return crc32.getValue();
    }
}