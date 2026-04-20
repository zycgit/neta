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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Stream-backed request body.
 * <p>
 * The body is read into ordered parts during {@link #doPrepare()}, but when no explicit byte count
 * is supplied it intentionally preserves the "unknown length" state so HTTP/1.1 serialization can
 * later select chunked transfer coding.
 */
public final class StreamBody extends ContentBody {
    private final StreamSource streamSource;
    private final Long         contentLength;
    private final int          chunkSize;
    private final String       defaultContentType;

    private StreamBody(Builder builder) {
        this.streamSource = Objects.requireNonNull(builder.streamSource, "streamSource");
        this.contentLength = builder.contentLength;
        this.chunkSize = builder.chunkSize <= 0 ? ContentBody.DEFAULT_STREAM_CHUNK_SIZE : builder.chunkSize;
        this.defaultContentType = builder.defaultContentType;
    }

    /** Starts a stream body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() throws IOException {
        try (InputStream inputStream = this.streamSource.openStream()) {
            return PreparedBody.ofStreamParts(ContentBodySupport.readStreamParts(inputStream, this.chunkSize), this.defaultContentType, this.contentLength);
        }
    }

    public static final class Builder {
        private StreamSource streamSource;
        private Long         contentLength;
        private int          chunkSize = ContentBody.DEFAULT_STREAM_CHUNK_SIZE;
        private String       defaultContentType;

        /** Sets the lazy stream source used when the body is prepared. */
        public Builder source(StreamSource streamSource) {
            this.streamSource = streamSource;
            return this;
        }

        /**
         * Sets a concrete input stream.
         * <p>
         * The stream is consumed when the request body is prepared, so callers should treat it as a
         * single-use source.
         */
        public Builder inputStream(InputStream inputStream) {
            Objects.requireNonNull(inputStream, "inputStream");
            this.streamSource = () -> inputStream;
            return this;
        }

        /** Treats a {@link ByteBuf} as a stream source with the buffer kept open after reading. */
        public Builder byteBuf(ByteBuf byteBuf) {
            return this.byteBuf(byteBuf, false);
        }

        /** Treats a {@link ByteBuf} as a stream source and optionally releases it when the stream closes. */
        public Builder byteBuf(ByteBuf byteBuf, boolean releaseOnClose) {
            Objects.requireNonNull(byteBuf, "byteBuf");
            this.streamSource = StreamSource.of(byteBuf, releaseOnClose);
            return this;
        }

        /**
         * Sets an explicit content length so later HTTP serialization can emit
         * {@code Content-Length} instead of chunked transfer coding.
         */
        public Builder contentLength(Long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        /** Sets an explicit content length. */
        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        /** Sets the local buffering chunk size used while splitting the stream into payload parts. */
        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        /** Sets the default media type when the request did not define {@code Content-Type}. */
        public Builder contentType(String defaultContentType) {
            this.defaultContentType = defaultContentType;
            return this;
        }

        /** Builds the immutable stream body. */
        public StreamBody build() {
            return new StreamBody(this);
        }
    }
}