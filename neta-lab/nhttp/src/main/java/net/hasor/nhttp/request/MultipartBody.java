/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;

/**
 * Multipart request body used for {@code multipart/form-data} submissions.
 * <p>
 * The actual boundary parameter and per-part wire encoding are generated during
 * {@link #doPrepare()} using {@link MultipartEncoder}, matching RFC 7578-style upload bodies.
 */
public final class MultipartBody extends ContentBody {
    private final List<MultipartPart> parts;

    private MultipartBody(Builder builder) {
        this.parts = new ArrayList<>(builder.parts);
    }

    /** Starts a multipart body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() throws IOException {
        MultipartEncoder encoder = new MultipartEncoder();
        for (MultipartPart part : this.parts) {
            part.writeTo(encoder);
        }
        byte[] bodyBytes = encoder.encode();
        return PreparedBody.single(ByteBuf.wrap(bodyBytes), encoder.contentType(), (long) bodyBytes.length);
    }

    public static final class Builder {
        private final List<MultipartPart> parts = new ArrayList<>();

        /** Adds a fully constructed multipart part in order. */
        public Builder addPart(MultipartPart part) {
            this.parts.add(Objects.requireNonNull(part, "part"));
            return this;
        }

        /** Adds a regular form field part. */
        public Builder field(String name, String value) {
            return this.addPart(MultipartPart.builder().field(name, value).build());
        }

        /** Adds a file part backed by a local file. */
        public Builder file(String fieldName, File file) {
            File useFile = Objects.requireNonNull(file, "file");
            return this.addPart(MultipartPart.builder().file(fieldName, useFile.getName(), FileBody.builder().file(useFile).build()).build());
        }

        /** Adds a file part backed by a byte array. */
        public Builder file(String fieldName, String fileName, byte[] data) {
            return this.addPart(MultipartPart.builder().file(fieldName, fileName, ByteArrayBody.builder().bodyBytes(data).build()).build());
        }

        /** Adds an arbitrary multipart part backed by another {@link ContentBody}. */
        public Builder part(String fieldName, String fileName, ContentBody body) {
            return this.addPart(MultipartPart.builder().file(fieldName, fileName, Objects.requireNonNull(body, "body")).build());
        }

        /** Builds the immutable multipart body. */
        public MultipartBody build() {
            return new MultipartBody(this);
        }
    }
}
