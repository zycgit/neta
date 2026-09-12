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

final class HttpInitialLineScanner {
    private HttpInitialLineScanner() {
    }

    // Complete lines borrow the input. Only fragments need bounded, private storage.
    static ByteBuf read(ProtoRcvQueue<ByteBuf> src, HttpContext.DecodeState<?> state, int maxLength) {
        int maxCapacity = (int) Math.min((long) maxLength + 2, Integer.MAX_VALUE);
        while (src.hasMore()) {
            ByteBuf input = src.peekMessage();
            if (input == null || input.readableBytes() == 0) {
                src.skipMessage(1);
                continue;
            }

            ByteBuf partial = state.initialLineBuffer;
            int pending = partial == null ? 0 : partial.readableBytes();
            int lineFeed = input.expect((byte) '\n', maxCapacity - pending);
            if (partial == null && lineFeed >= 0) {
                state.initialLineFeedIndex = lineFeed;
                return input;
            }

            int length = lineFeed >= 0 ? lineFeed + 1 : input.readableBytes();
            long required = (long) pending + length;
            if (lineFeed < 0) {
                long contentLength = required - (input.getByte(length - 1) == '\r' ? 1 : 0);
                if (contentLength > maxLength) {
                    throw new HttpInitialLineTooLongException("HTTP initial line too long: " + contentLength + " > " + maxLength, maxLength, contentLength);
                }
            }
            if (required > maxCapacity) {
                throw new HttpInitialLineTooLongException("HTTP initial line too long: " + required + " > " + maxLength, maxLength, required);
            }

            if (partial == null || partial.writableBytes() < length) {
                long grown = partial == null ? 64 : (long) partial.capacity() * 2;
                int capacity = (int) Math.min(maxCapacity, Math.max(required, grown));
                ByteBuf replacement = ByteBuf.wrap(new byte[capacity], true);
                try {
                    if (partial != null) {
                        replacement.writeBuffer(partial, pending);
                    }
                } catch (RuntimeException | Error e) {
                    replacement.release();
                    throw e;
                }
                if (partial != null) {
                    partial.release();
                }
                state.initialLineBuffer = partial = replacement;
            }
            partial.writeBuffer(input, length);
            partial.markWriter();
            if (lineFeed >= 0) {
                state.initialLineFeedIndex = (int) required - 1;
                return partial;
            }
            src.skipMessage(1);
        }
        return null;
    }

    static void consume(HttpContext.DecodeState<?> state, ByteBuf line, int consumedBytes) {
        if (line == state.initialLineBuffer) {
            state.releaseInitialLine();
        } else {
            line.skipReadableBytes(consumedBytes);
            HttpContext.DecodeState.markReaderDeferred(line);
        }
    }
}
