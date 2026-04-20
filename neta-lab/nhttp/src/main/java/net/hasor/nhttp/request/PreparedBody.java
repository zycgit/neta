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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Immutable body representation after a {@link ContentBody} has been materialized for wire output.
 * <p>
 * This type is where request generation decides whether the body length is known up front, which in
 * turn controls later use of {@code Content-Length} versus chunked transfer coding in
 * {@link HttpObjectWriter}.
 */
public final class PreparedBody {
    private final List<ByteBuf> parts;
    private final String        defaultContentType;
    private final Long          contentLength;

    private PreparedBody(Builder builder) {
        ArrayList<ByteBuf> normalizedParts = new ArrayList<>(builder.parts.size());
        for (ByteBuf part : builder.parts) {
            normalizedParts.add(part == null ? ByteBuf.EMPTY : part);
        }
        this.parts = Collections.unmodifiableList(normalizedParts);
        this.defaultContentType = builder.defaultContentType;
        this.contentLength = resolveContentLength(builder.contentLength, this.parts, builder.inferContentLength);
    }

    /** Starts a prepared-body builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a prepared body from one contiguous part. */
    public static PreparedBody single(ByteBuf part, String defaultContentType, Long contentLength) {
        return builder().addPart(part).defaultContentType(defaultContentType).contentLength(contentLength).build();
    }

    /** Creates a prepared body from several already-buffered parts and may infer total length. */
    public static PreparedBody ofParts(List<ByteBuf> parts, String defaultContentType, Long contentLength) {
        return builder().parts(parts).defaultContentType(defaultContentType).contentLength(contentLength).build();
    }

    /**
     * Creates a prepared body from stream-derived parts while preserving unknown length when no
     * explicit byte count was supplied.
     * <p>
     * This allows later HTTP/1.1 serialization to choose {@code Transfer-Encoding: chunked} per
     * RFC 9112 Section 7.1 instead of synthesizing {@code Content-Length}.
     */
    public static PreparedBody ofStreamParts(List<ByteBuf> parts, String defaultContentType, Long contentLength) {
        return builder().parts(parts).defaultContentType(defaultContentType).contentLength(contentLength).inferContentLength(false).build();
    }

    /** Returns the prepared payload fragments in transmission order. */
    public List<ByteBuf> parts() {
        return this.parts;
    }

    /** Returns the body-supplied media type, or {@code null} when the body has no default. */
    public String defaultContentType() {
        return this.defaultContentType;
    }

    /** Returns the known length, or {@code null} when the length must remain unspecified. */
    public Long contentLength() {
        return this.contentLength;
    }

    /** Returns whether the writer can safely emit {@code Content-Length}. */
    public boolean hasKnownLength() {
        return this.contentLength != null;
    }

    private static Long resolveContentLength(Long contentLength, List<ByteBuf> parts, boolean inferContentLength) {
        if (contentLength != null) {
            return contentLength;
        }
        if (!inferContentLength) {
            return null;
        }
        long total = 0L;
        for (ByteBuf part : parts) {
            if (part == null) {
                continue;
            }
            total += part.readableBytes();
        }
        return Long.valueOf(total);
    }

    public static final class Builder {
        private final List<ByteBuf> parts              = new ArrayList<>();
        private String              defaultContentType;
        private Long                contentLength;
        private boolean             inferContentLength = true;

        /** Adds one prepared payload fragment. */
        public Builder addPart(ByteBuf part) {
            this.parts.add(part == null ? ByteBuf.EMPTY : part);
            return this;
        }

        /** Replaces all payload fragments with the provided ordered list. */
        public Builder parts(List<ByteBuf> parts) {
            this.parts.clear();
            if (parts != null) {
                for (ByteBuf part : parts) {
                    this.parts.add(part == null ? ByteBuf.EMPTY : part);
                }
            }
            return this;
        }

        /** Sets the body default media type used when the request itself did not specify one. */
        public Builder defaultContentType(String defaultContentType) {
            this.defaultContentType = defaultContentType;
            return this;
        }

        /** Sets the explicit byte length, bypassing later inference. */
        public Builder contentLength(Long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        /** Sets the explicit byte length, bypassing later inference. */
        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        /**
         * Controls whether the builder may sum part lengths automatically.
         * <p>
         * Disabling inference is what lets stream-backed bodies stay unknown-length and therefore
         * qualify for HTTP/1.1 chunked transfer coding later on.
         */
        public Builder inferContentLength(boolean inferContentLength) {
            this.inferContentLength = inferContentLength;
            return this;
        }

        /** Builds the immutable prepared body. */
        public PreparedBody build() {
            return new PreparedBody(this);
        }
    }
}