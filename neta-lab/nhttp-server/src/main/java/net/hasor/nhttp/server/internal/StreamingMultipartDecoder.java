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
package net.hasor.nhttp.server.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufOutputStream;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.multipart.DefaultFileUpload;
import net.hasor.neta.codec.http.multipart.FileUpload;

/**
 * Streaming multipart/form-data decoder backed by {@link InternalBodyChannel}.
 *
 * <p>Unlike {@code MultipartDecoder}, this parser does not materialize the whole request body
 * into one aggregated buffer. Each part is decoded incrementally and stored in its own spillable
 * {@link ByteBuf}.</p>
 */
final class StreamingMultipartDecoder {
    private static final Logger logger = Logger.getLogger(StreamingMultipartDecoder.class);

    private StreamingMultipartDecoder() {
    }

    public static List<FileUpload> decode(InternalBodyChannel bodyChannel, String boundary, long chunkReadTimeoutMillis) {
        if (bodyChannel == null || boundary == null || boundary.isEmpty()) {
            return Collections.emptyList();
        }

        List<FileUpload> parts = new ArrayList<>(4);
        try (BodyChannelInputStream in = new BodyChannelInputStream(bodyChannel, chunkReadTimeoutMillis)) {
            if (!consumeFirstBoundary(in, boundary)) {
                return Collections.emptyList();
            }

            boolean finished = false;
            while (!finished) {
                Map<String, String> headers = readHeaders(in);
                if (headers == null) {
                    break;
                }

                PartContent partContent = readPartContent(in, boundary);
                FileUpload fileUpload = toFileUpload(headers, partContent.content);
                if (fileUpload != null) {
                    parts.add(fileUpload);
                } else if (partContent.content != null && partContent.content != ByteBuf.EMPTY) {
                    partContent.content.release();
                }
                finished = partContent.lastPart;
            }
        } catch (IOException e) {
            for (FileUpload part : parts) {
                if (part != null) {
                    part.release();
                }
            }
            logger.warn("Failed to decode multipart body in streaming mode", e);
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(parts);
    }

    private static boolean consumeFirstBoundary(InputStream in, String boundary) throws IOException {
        String expected = "--" + boundary;
        String expectedLast = expected + "--";
        String line;
        while ((line = readLine(in)) != null) {
            if (expected.equals(line)) {
                return true;
            }
            if (expectedLast.equals(line)) {
                return false;
            }
        }
        return false;
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(4, 1.0f);
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(key, value);
        }

        return headers.isEmpty() ? null : headers;
    }

    private static FileUpload toFileUpload(Map<String, String> headers, ByteBuf content) {
        String disposition = headers.get(HttpHeaderNames.CONTENT_DISPOSITION);
        String fieldName = extractParam(disposition, HttpHeaderValues.NAME);
        if (fieldName == null) {
            return null;
        }
        String filename = extractParam(disposition, HttpHeaderValues.FILENAME);
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        return new DefaultFileUpload(fieldName, filename, contentType, content, headers);
    }

    private static PartContent readPartContent(InputStream in, String boundary) throws IOException {
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int[] lps = buildLps(delimiter);

        ByteBuf contentBuffer = ByteBufAllocator.DEFAULT.swapFile();
        ByteBufOutputStream out = new ByteBufOutputStream(contentBuffer);
        ByteQueue pending = new ByteQueue(delimiter.length);
        int matched = 0;

        while (true) {
            int nextByte = in.read();
            if (nextByte < 0) {
                flushPending(out, pending);
                return finalizePart(contentBuffer, out, true);
            }

            byte value = (byte) nextByte;
            pending.addLast(value);
            matched = advanceKmp(delimiter, lps, matched, value);

            while (pending.size() > matched) {
                out.write(pending.removeFirst() & 0xFF);
            }

            if (matched == delimiter.length) {
                pending.clear();
                int suffix = in.read();
                if (suffix == '-') {
                    int suffix2 = in.read();
                    if (suffix2 == '-') {
                        consumeLineEnding(in);
                        return finalizePart(contentBuffer, out, true);
                    }
                    if (suffix2 >= 0) {
                        out.write('-');
                        out.write(suffix2);
                    } else {
                        out.write('-');
                    }
                    matched = 0;
                    continue;
                }
                if (suffix == '\r') {
                    int suffix2 = in.read();
                    if (suffix2 != '\n' && suffix2 >= 0) {
                        out.write('\r');
                        out.write(suffix2);
                        matched = 0;
                        continue;
                    }
                    return finalizePart(contentBuffer, out, false);
                }
                if (suffix == '\n' || suffix < 0) {
                    return finalizePart(contentBuffer, out, suffix < 0);
                }

                writeBytes(out, delimiter);
                out.write(suffix);
                matched = 0;
            }
        }
    }

    private static void flushPending(ByteBufOutputStream out, ByteQueue pending) throws IOException {
        while (!pending.isEmpty()) {
            out.write(pending.removeFirst() & 0xFF);
        }
    }

    private static void writeBytes(ByteBufOutputStream out, byte[] bytes) throws IOException {
        for (byte value : bytes) {
            out.write(value & 0xFF);
        }
    }

    private static PartContent finalizePart(ByteBuf contentBuffer, ByteBufOutputStream out, boolean lastPart) {
        ByteBuf content;
        if (out.writtenBytes() > 0) {
            out.buffer().markWriter();
            content = out.buffer();
        } else {
            contentBuffer.free();
            content = ByteBuf.EMPTY;
        }
        return new PartContent(content, lastPart);
    }

    private static void consumeLineEnding(InputStream in) throws IOException {
        int next = in.read();
        if (next == '\r') {
            int maybeLf = in.read();
            if (maybeLf != '\n' && maybeLf >= 0) {
                logger.warn("Unexpected multipart epilogue byte: " + maybeLf);
            }
        }
    }

    private static int[] buildLps(byte[] pattern) {
        int[] lps = new int[pattern.length];
        int len = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (len > 0 && pattern[i] != pattern[len]) {
                len = lps[len - 1];
            }
            if (pattern[i] == pattern[len]) {
                len++;
                lps[i] = len;
            }
        }
        return lps;
    }

    private static int advanceKmp(byte[] pattern, int[] lps, int matched, byte value) {
        while (matched > 0 && value != pattern[matched]) {
            matched = lps[matched - 1];
        }
        if (value == pattern[matched]) {
            matched++;
        }
        return matched;
    }

    private static String extractParam(String header, String param) {
        if (header == null || param == null) {
            return null;
        }
        String searchKey = param + "=";
        int idx = indexOfIgnoreCase(header, searchKey, 0);
        if (idx < 0) {
            return null;
        }
        int start = idx + searchKey.length();
        if (start >= header.length()) {
            return null;
        }
        if (header.charAt(start) == '"') {
            int end = header.indexOf('"', start + 1);
            if (end < 0) {
                end = header.length();
            }
            return header.substring(start + 1, end);
        }

        int end = start;
        while (end < header.length() && header.charAt(end) != ';' && header.charAt(end) != ' ') {
            end++;
        }
        return header.substring(start, end);
    }

    private static int indexOfIgnoreCase(String str, String search, int fromIndex) {
        int searchLen = search.length();
        int maxIdx = str.length() - searchLen;
        for (int i = fromIndex; i <= maxIdx; i++) {
            if (str.regionMatches(true, i, search, 0, searchLen)) {
                return i;
            }
        }
        return -1;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        boolean sawByte = false;
        while (true) {
            int value = in.read();
            if (value < 0) {
                break;
            }
            sawByte = true;
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
        }

        if (!sawByte && line.size() == 0) {
            return null;
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static final class PartContent {
        private final ByteBuf  content;
        private final boolean  lastPart;

        private PartContent(ByteBuf content, boolean lastPart) {
            this.content = content;
            this.lastPart = lastPart;
        }
    }

    private static final class ByteQueue {
        private final byte[] buffer;
        private int          head;
        private int          size;

        private ByteQueue(int capacity) {
            this.buffer = new byte[Math.max(8, capacity + 2)];
        }

        private void addLast(byte value) {
            this.buffer[(this.head + this.size) % this.buffer.length] = value;
            this.size++;
        }

        private byte removeFirst() {
            byte value = this.buffer[this.head];
            this.head = (this.head + 1) % this.buffer.length;
            this.size--;
            return value;
        }

        private int size() {
            return this.size;
        }

        private boolean isEmpty() {
            return this.size == 0;
        }

        private void clear() {
            this.head = 0;
            this.size = 0;
        }
    }

    private static final class BodyChannelInputStream extends InputStream {
        private final InternalBodyChannel bodyChannel;
        private final long                chunkReadTimeoutMillis;
        private HttpContent               currentChunk;
        private ByteBuf                   currentContent;
        private boolean                   closed;
        private boolean                   endOfInput;

        private BodyChannelInputStream(InternalBodyChannel bodyChannel, long chunkReadTimeoutMillis) {
            this.bodyChannel = bodyChannel;
            this.chunkReadTimeoutMillis = chunkReadTimeoutMillis;
        }

        @Override
        public int read() throws IOException {
            if (this.closed) {
                return -1;
            }
            try {
                while (true) {
                    if (this.currentContent != null && this.currentContent.readableBytes() > 0) {
                        return this.currentContent.readByte() & 0xFF;
                    }
                    if (!loadNextChunk()) {
                        return -1;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading multipart body", e);
            }
        }

        private boolean loadNextChunk() throws InterruptedException {
            releaseCurrentChunk();
            if (this.endOfInput) {
                return false;
            }

            HttpContent chunk = this.bodyChannel.read(this.chunkReadTimeoutMillis, TimeUnit.MILLISECONDS);
            if (chunk == null) {
                this.endOfInput = true;
                return false;
            }

            this.currentChunk = chunk;
            this.currentContent = chunk.content();
            if (this.currentContent == null || this.currentContent.readableBytes() == 0) {
                releaseCurrentChunk();
                if (this.bodyChannel.isComplete()) {
                    this.endOfInput = true;
                    return false;
                }
                return loadNextChunk();
            }

            return true;
        }

        private void releaseCurrentChunk() {
            if (this.currentChunk != null) {
                this.currentChunk.release();
                this.currentChunk = null;
                this.currentContent = null;
            }
        }

        @Override
        public void close() {
            this.closed = true;
            releaseCurrentChunk();
        }
    }
}