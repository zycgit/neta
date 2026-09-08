package net.hasor.neta.codec.http;

import java.util.Arrays;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.data.ProtoRcvQueue;

final class HttpHeaderStreamingScanner {
    interface HeaderLineConsumer<S extends HttpContext.DecodeState<?>> {
        // Returning true takes one reference; offsets may address a shared input slice.
        boolean onHeaderLine(S state, ByteBuf line, int nameStart, int nameLength, int valueStart, int valueLength);
    }

    private HttpHeaderStreamingScanner() {
    }

    static <S extends HttpContext.DecodeState<?>> boolean scan(ProtoRcvQueue<ByteBuf> src, S decodeState, int maxHeaderSize, HeaderLineConsumer<S> consumer) {
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
                    int lineLength = chunkEndsWithCarriageReturn ? relativeEnd - 1 : relativeEnd;
                    if (partialLine) {
                        if (relativeEnd > 0) {
                            appendScratch(decodeState, buffer, 0, lineLength, maxHeaderSize);
                        }
                        line = detachScratch(decodeState);
                        lineLength = line.readableBytes();
                    }

                    boolean releaseLine = partialLine;
                    try {
                        decodeState.headerBytes += lineLength + (lineHasCarriageReturn ? 2 : 1);
                        if (decodeState.headerBytes > maxHeaderSize) {
                            throw new HttpHeaderTooLargeException("HTTP headers too large: " + decodeState.headerBytes + " > " + maxHeaderSize, maxHeaderSize, decodeState.headerBytes);
                        }

                        if (lineLength == 0) {
                            buffer.skipReadableBytes(relativeEnd + 1);
                            if (buffer.readableBytes() == 0) {
                                src.skipMessage(1);
                            }
                            return true;
                        }

                        int colonIdx = line.expect((byte) ':', lineLength);
                        if (colonIdx < 0) {
                            throw new HttpBadRequestException("invalid header line (no colon)");
                        }

                        int nameStart = 0;
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
                        while (valueStart < lineLength && isHorizontalWhitespace(line.getByte(valueStart))) {
                            valueStart++;
                        }
                        int valueEnd = lineLength;
                        while (valueEnd > valueStart && isHorizontalWhitespace(line.getByte(valueEnd - 1))) {
                            valueEnd--;
                        }

                        int nameLength = nameEnd - nameStart;
                        int valueLength = valueEnd - valueStart;
                        if (!partialLine) {
                            // Entries share stable indices, but each owns a separate reference.
                            if (chunkView == null) {
                                chunkView = buffer.slice(0, buffer.readableBytes());
                                chunkReaderIndex = buffer.readerIndex();
                            }
                            int offset = buffer.readerIndex() - chunkReaderIndex;
                            nameStart += offset;
                            valueStart += offset;
                            int nextLine = relativeEnd + 1;
                            int remaining = buffer.readableBytes() - nextLine;
                            if (remaining == 0 || buffer.getByte(nextLine) == '\n' || remaining > 1 && buffer.getByte(nextLine) == '\r' && buffer.getByte(nextLine + 1) == '\n') {
                                // The scanner is done with this view; transfer its reference to the final entry.
                                line = chunkView;
                                chunkView = null;
                            } else {
                                line = chunkView.retain();
                            }
                            releaseLine = true;
                        }
                        releaseLine = !consumer.onHeaderLine(decodeState, line, nameStart, nameLength, valueStart, valueLength);
                        buffer.skipReadableBytes(relativeEnd + 1);
                    } finally {
                        if (releaseLine) {
                            line.free();
                        }
                    }
                }
            } finally {
                if (chunkView != null) {
                    chunkView.free();
                }
            }
            src.skipMessage(1);
        }
        return false;
    }

    private static void appendScratch(HttpContext.DecodeState<?> decodeState, ByteBuf source, int offset, int length, int maxHeaderSize) {
        if (length <= 0) {
            return;
        }
        int currentLength = decodeState.headerLineLength;
        int nextLength = currentLength + length;
        int maxCapacity = (int) Math.min((long) maxHeaderSize + 2, Integer.MAX_VALUE);
        if (nextLength > maxCapacity) {
            throw new HttpHeaderTooLargeException("HTTP headers too large: " + nextLength + " > " + maxHeaderSize, maxHeaderSize, nextLength);
        }
        byte[] scratch = decodeState.headerLineScratch;
        if (scratch == null) {
            scratch = new byte[Math.min(Math.max(64, length), maxCapacity)];
            decodeState.headerLineScratch = scratch;
        } else if (scratch.length < nextLength) {
            scratch = Arrays.copyOf(scratch, Math.min(Math.max(nextLength, scratch.length * 2), maxCapacity));
            decodeState.headerLineScratch = scratch;
        }
        source.getBytes(offset, scratch, currentLength, length);
        decodeState.headerLineLength = nextLength;
    }

    private static ByteBuf detachScratch(HttpContext.DecodeState<?> decodeState) {
        byte[] scratch = decodeState.headerLineScratch;
        int length = decodeState.headerLineLength;
        decodeState.headerLineLength = 0;
        if (length > 0 && scratch[length - 1] == '\r') {
            length--;
        }
        // Keep scratch private to this message; emitted headers own their exact-sized copy.
        return length == 0 ? ByteBuf.EMPTY : ByteBuf.wrap(Arrays.copyOf(scratch, length));
    }

    private static boolean scratchEndsWithCarriageReturn(HttpContext.DecodeState<?> decodeState) {
        int length = decodeState.headerLineLength;
        return length > 0 && decodeState.headerLineScratch[length - 1] == '\r';
    }

    private static boolean isHorizontalWhitespace(int value) {
        return value == ' ' || value == '\t';
    }
}
