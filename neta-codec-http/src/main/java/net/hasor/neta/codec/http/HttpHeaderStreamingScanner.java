/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.data.ProtoRcvQueue;

final class HttpHeaderStreamingScanner {
    interface HeaderLineConsumer<S extends HttpContext.DecodeState<?>> {
        // The view is borrowed for this call. Retain it to keep any unresolved fields.
        void onHeaderLine(S state, ByteBuf line, int nameStart, int nameLength, int valueStart, int valueLength);
    }

    private HttpHeaderStreamingScanner() {
    }

    static <S extends HttpContext.DecodeState<?>> boolean scan(ProtoRcvQueue<ByteBuf> src, S decodeState, int maxHeaderSize, HeaderLineConsumer<S> consumer) {
        try {
            while (src.hasMore()) {
                ByteBuf buffer = src.peekMessage();
                if (buffer == null) {
                    src.skipMessage(1);
                    continue;
                }

                ByteBuf chunkView = null;
                int chunkReaderIndex = 0;
                try {
                    while (buffer.readableBytes() > 0) {
                        int relativeEnd = buffer.expect((byte) '\n', buffer.readableBytes());
                        if (relativeEnd < 0) {
                            int remaining = buffer.readableBytes();
                            appendScratch(decodeState, buffer, 0, remaining, maxHeaderSize);
                            buffer.skipReadableBytes(remaining);
                            break;
                        }

                        boolean partialLine = decodeState.headerLineLength > 0;
                        boolean chunkEndsWithCarriageReturn = relativeEnd > 0 && buffer.getByte(relativeEnd - 1) == '\r';
                        boolean lineHasCarriageReturn = chunkEndsWithCarriageReturn || scratchEndsWithCarriageReturn(decodeState);
                        ByteBuf line = buffer;
                        int lineStart = 0;
                        int lineLength = chunkEndsWithCarriageReturn ? relativeEnd - 1 : relativeEnd;
                        if (partialLine) {
                            if (relativeEnd > 0) {
                                appendScratch(decodeState, buffer, 0, lineLength, maxHeaderSize);
                            }
                            lineStart = decodeState.headerLineStart;
                            lineLength = decodeState.headerLineLength;
                            if (scratchEndsWithCarriageReturn(decodeState)) {
                                lineLength--;
                            }
                            line = finishScratchLine(decodeState, lineLength);
                        }

                        long headerBytes = (long) decodeState.headerBytes + lineLength + (lineHasCarriageReturn ? 2 : 1);
                        if (headerBytes > maxHeaderSize) {
                            decodeState.headerBytes = (int) Math.min(Integer.MAX_VALUE, headerBytes);
                            throw new HttpHeaderTooLargeException("HTTP headers too large: " + headerBytes + " > " + maxHeaderSize, maxHeaderSize, headerBytes);
                        }
                        decodeState.headerBytes = (int) headerBytes;

                        if (lineLength == 0) {
                            buffer.skipReadableBytes(relativeEnd + 1);
                            if (buffer.readableBytes() == 0) {
                                src.skipMessage(1);
                            }
                            decodeState.releaseHeaderLineScratch();
                            return true;
                        }

                        int lineEnd = lineStart + lineLength;
                        int colonIdx = partialLine ? findScratchColon(decodeState.headerLineScratch, lineStart, lineEnd) : line.expect((byte) ':', lineLength);
                        if (colonIdx < 0) {
                            throw new HttpBadRequestException("invalid header line (no colon)");
                        }

                        int nameStart = lineStart;
                        int nameEnd = colonIdx;
                        while (nameStart < nameEnd && isHorizontalWhitespace(line.getByte(nameStart))) {
                            nameStart++;
                        }
                        while (nameEnd > nameStart && isHorizontalWhitespace(line.getByte(nameEnd - 1))) {
                            nameEnd--;
                        }
                        if (nameStart >= nameEnd) {
                            throw new HttpBadRequestException("empty header name");
                        }

                        int valueStart = colonIdx + 1;
                        while (valueStart < lineEnd && isHorizontalWhitespace(line.getByte(valueStart))) {
                            valueStart++;
                        }
                        int valueEnd = lineEnd;
                        while (valueEnd > valueStart && isHorizontalWhitespace(line.getByte(valueEnd - 1))) {
                            valueEnd--;
                        }

                        int nameLength = nameEnd - nameStart;
                        int valueLength = valueEnd - valueStart;
                        if (!partialLine) {
                            // The scanner holds the view while consumers acquire their own ownership.
                            if (chunkView == null) {
                                chunkView = buffer.slice(0, buffer.readableBytes());
                                chunkReaderIndex = buffer.readerIndex();
                            }
                            int offset = buffer.readerIndex() - chunkReaderIndex;
                            nameStart += offset;
                            valueStart += offset;
                            line = chunkView;
                        }
                        consumer.onHeaderLine(decodeState, line, nameStart, nameLength, valueStart, valueLength);
                        buffer.skipReadableBytes(relativeEnd + 1);
                    }
                } finally {
                    if (chunkView != null) {
                        chunkView.free();
                    }
                }
                src.skipMessage(1);
            }
            return false;
        } catch (RuntimeException | Error e) {
            decodeState.releaseHeaderLineScratch();
            throw e;
        }
    }

    private static void appendScratch(HttpContext.DecodeState<?> decodeState, ByteBuf source, int offset, int length, int maxHeaderSize) {
        if (length <= 0) {
            return;
        }
        int currentLength = decodeState.headerLineLength;
        long nextLength = (long) currentLength + length;
        int maxCapacity = (int) Math.min((long) maxHeaderSize + 2, Integer.MAX_VALUE);
        if (nextLength > maxCapacity) {
            throw new HttpHeaderTooLargeException("HTTP headers too large: " + nextLength + " > " + maxHeaderSize, maxHeaderSize, nextLength);
        }
        byte[] scratch = decodeState.headerLineScratch;
        if (scratch == null) {
            scratch = new byte[Math.min(Math.max(128, length), maxCapacity)];
            decodeState.headerLineScratch = scratch;
        } else if ((long) decodeState.headerLineStart + nextLength > scratch.length) {
            int capacity = (int) Math.min(Math.max(nextLength, (long) scratch.length * 2), maxCapacity);
            byte[] next = new byte[capacity];
            // Published prefixes stay in their original page; move only the unfinished line.
            System.arraycopy(scratch, decodeState.headerLineStart, next, 0, currentLength);
            ByteBuf previous = decodeState.headerLineView;
            scratch = next;
            decodeState.headerLineScratch = scratch;
            decodeState.headerLineView = null;
            decodeState.headerLineStart = 0;
            if (previous != null) {
                previous.release();
            }
        }
        source.getBytes(offset, scratch, decodeState.headerLineStart + currentLength, length);
        decodeState.headerLineLength = (int) nextLength;
    }

    private static ByteBuf finishScratchLine(HttpContext.DecodeState<?> decodeState, int length) {
        decodeState.headerLineStart += decodeState.headerLineLength;
        decodeState.headerLineLength = 0;
        if (length == 0) {
            return ByteBuf.EMPTY;
        }
        if (decodeState.headerLineView == null) {
            // Keep one stable, zero-based view while later lines append after the published prefix.
            decodeState.headerLineView = ByteBuf.wrap(decodeState.headerLineScratch);
        }
        return decodeState.headerLineView;
    }

    private static int findScratchColon(byte[] scratch, int start, int end) {
        for (int i = start; i < end; i++) {
            if (scratch[i] == ':') {
                return i;
            }
        }
        return -1;
    }

    private static boolean scratchEndsWithCarriageReturn(HttpContext.DecodeState<?> decodeState) {
        int length = decodeState.headerLineLength;
        return length > 0 && decodeState.headerLineScratch[decodeState.headerLineStart + length - 1] == '\r';
    }

    private static boolean isHorizontalWhitespace(int value) {
        return value == ' ' || value == '\t';
    }
}
