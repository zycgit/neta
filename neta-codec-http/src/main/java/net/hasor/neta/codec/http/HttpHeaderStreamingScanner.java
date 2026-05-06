package net.hasor.neta.codec.http;

import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.data.ProtoRcvQueue;

final class HttpHeaderStreamingScanner {
    interface HeaderLineConsumer {
        boolean onHeaderLine(ByteBuf line, int nameStart, int nameLength, int valueStart, int valueLength);
    }

    private static final class ScanCursor {
        private int     skipCount;
        private boolean completed;
        private boolean stop;
    }

    static final class ScanResult {
        private final boolean complete;

        ScanResult(boolean complete) {
            this.complete = complete;
        }

        boolean isComplete() {
            return this.complete;
        }
    }

    private HttpHeaderStreamingScanner() {
    }

    static ScanResult scan(ProtoRcvQueue<ByteBuf> src, HttpContext.DecodeState<?> decodeState, int maxHeaderSize, HeaderLineConsumer consumer) {
        if (!src.hasMore()) {
            return new ScanResult(false);
        }

        ScanCursor cursor = new ScanCursor();
        src.peekEachMessage(buffer -> {
            if (cursor.stop) {
                return;
            }
            if (buffer == null) {
                cursor.skipCount++;
                return;
            }

            int startReader = buffer.readerIndex();
            int scanIndex = startReader;
            int limit = startReader + buffer.readableBytes();
            while (scanIndex < limit) {
                if (buffer.getUInt8(scanIndex - startReader) != '\n') {
                    scanIndex++;
                    continue;
                }

                boolean releaseLine = true;
                int relativeEnd = scanIndex - startReader;
                boolean chunkEndsWithCarriageReturn = relativeEnd > 0 && buffer.getUInt8(relativeEnd - 1) == '\r';
                boolean lineHasCarriageReturn = chunkEndsWithCarriageReturn || scratchEndsWithCarriageReturn(decodeState);
                ByteBuf line;
                if (decodeState.headerLineScratch != null && decodeState.headerLineScratch.writerIndex() > 0) {
                    if (relativeEnd > 0) {
                        int appendLength = chunkEndsWithCarriageReturn ? relativeEnd - 1 : relativeEnd;
                        appendScratch(decodeState, buffer, 0, appendLength, maxHeaderSize);
                    }
                    line = detachScratch(decodeState);
                } else {
                    int lineLength = chunkEndsWithCarriageReturn ? relativeEnd - 1 : relativeEnd;
                    line = ByteBufUtils.stableSlice(buffer, 0, lineLength);
                }

                try {
                    int lineLength = line.readableBytes();
                    decodeState.headerBytes += lineLength + (lineHasCarriageReturn ? 2 : 1);
                    if (decodeState.headerBytes > maxHeaderSize) {
                        throw new HttpHeaderTooLargeException("HTTP headers too large: " + decodeState.headerBytes + " > " + maxHeaderSize, maxHeaderSize, decodeState.headerBytes);
                    }

                    if (lineLength == 0) {
                        cursor.completed = true;
                        cursor.stop = true;
                        buffer.skipReadableBytes(relativeEnd + 1);
                        if (buffer.readableBytes() == 0) {
                            cursor.skipCount++;
                        }
                        return;
                    }

                    int colonIdx = line.expect((byte) ':', lineLength);
                    if (colonIdx < 0) {
                        throw new HttpBadRequestException("invalid header line (no colon)");
                    }

                    int nameStart = 0;
                    int nameEnd = colonIdx;
                    while (nameStart < nameEnd && isHorizontalWhitespace(line.getUInt8(nameStart))) {
                        nameStart++;
                    }
                    while (nameEnd > nameStart && isHorizontalWhitespace(line.getUInt8(nameEnd - 1))) {
                        nameEnd--;
                    }
                    if (nameStart >= nameEnd) {
                        throw new HttpBadRequestException("empty header name");
                    }

                    int valueStart = colonIdx + 1;
                    while (valueStart < lineLength && isHorizontalWhitespace(line.getUInt8(valueStart))) {
                        valueStart++;
                    }
                    int valueEnd = lineLength;
                    while (valueEnd > valueStart && isHorizontalWhitespace(line.getUInt8(valueEnd - 1))) {
                        valueEnd--;
                    }

                    int nameLength = nameEnd - nameStart;
                    int valueLength = valueEnd - valueStart;
                    releaseLine = !consumer.onHeaderLine(line, nameStart, nameLength, valueStart, valueLength);

                    buffer.skipReadableBytes(relativeEnd + 1);
                    startReader = buffer.readerIndex();
                    scanIndex = startReader;
                    limit = startReader + buffer.readableBytes();
                    if (buffer.readableBytes() == 0) {
                        cursor.skipCount++;
                        return;
                    }
                } finally {
                    if (releaseLine) {
                        line.free();
                    }
                }
            }

            if (buffer.readableBytes() > 0) {
                int remaining = buffer.readableBytes();
                appendScratch(decodeState, buffer, 0, remaining, maxHeaderSize);
                buffer.skipReadableBytes(remaining);
                cursor.skipCount++;
            }
        });

        if (cursor.skipCount > 0) {
            src.skipMessage(cursor.skipCount);
        }
        return new ScanResult(cursor.completed);
    }

    private static void appendScratch(HttpContext.DecodeState<?> decodeState, ByteBuf source, int offset, int length, int maxHeaderSize) {
        if (length <= 0) {
            return;
        }
        ByteBuf scratch = decodeState.headerLineScratch;
        int maxCapacity = Math.max(maxHeaderSize + 2, length);
        int nextLength = (scratch == null || scratch.isFree()) ? length : scratch.readableBytes() + length;
        if (nextLength > maxCapacity) {
            throw new HttpHeaderTooLargeException("HTTP headers too large: " + nextLength + " > " + maxHeaderSize, maxHeaderSize, nextLength);
        }
        if (scratch == null || scratch.isFree()) {
            int initialCapacity = Math.max(64, length);
            scratch = ByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
            decodeState.headerLineScratch = scratch;
        } else if (scratch.writableBytes() < length) {
            int currentLength = scratch.readableBytes();
            int targetCapacity = Math.max(Math.max(64, currentLength + length), scratch.capacity() * 2);
            ByteBuf expanded = ByteBufAllocator.DEFAULT.heapBuffer(Math.min(targetCapacity, maxCapacity), maxCapacity);
            if (currentLength > 0) {
                scratch.getBuffer(0, expanded, currentLength);
                expanded.markWriter();
            }
            scratch.release();
            scratch = expanded;
            decodeState.headerLineScratch = scratch;
        }
        source.getBuffer(offset, scratch, length);
        scratch.markWriter();
    }

    private static ByteBuf detachScratch(HttpContext.DecodeState<?> decodeState) {
        ByteBuf scratch = decodeState.headerLineScratch;
        decodeState.headerLineScratch = null;
        if (scratch == null || scratch.isFree()) {
            return ByteBuf.EMPTY;
        }

        try {
            int length = scratch.readableBytes();
            if (length > 0 && scratch.getUInt8(length - 1) == '\r') {
                length--;
            }
            if (length <= 0) {
                return ByteBuf.EMPTY;
            }
            byte[] copy = new byte[length];
            scratch.getBytes(0, copy, 0, length);
            return ByteBuf.wrap(copy);
        } finally {
            scratch.release();
        }
    }

    private static boolean scratchEndsWithCarriageReturn(HttpContext.DecodeState<?> decodeState) {
        ByteBuf scratch = decodeState.headerLineScratch;
        return scratch != null && !scratch.isFree() && scratch.writerIndex() > 0 && scratch.getUInt8(scratch.writerIndex() - 1) == '\r';
    }

    private static boolean isHorizontalWhitespace(int value) {
        return value == ' ' || value == '\t';
    }
}
