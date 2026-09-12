/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.io.IOException;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.multipart.MultipartEncoder;

/**
 * One logical part inside a {@link MultipartBody}.
 * <p>
 * A part can be either a plain field or a file-like body and is later written using multipart form
 * rules compatible with RFC 7578.
 */
public final class MultipartPart {
    private final String      name;
    private final String      value;
    private final String      fileName;
    private final ContentBody body;

    private MultipartPart(Builder builder) {
        this.name = builder.name;
        this.value = builder.value;
        this.fileName = builder.fileName;
        this.body = builder.body;
    }

    /** Starts a multipart part builder. */
    public static Builder builder() {
        return new Builder();
    }

    void writeTo(MultipartEncoder encoder) throws IOException {
        if (this.fileName == null) {
            encoder.addField(this.name, this.value == null ? "" : this.value);
            return;
        }
        PreparedBody prepared = this.body.prepare();
        String contentType = prepared.defaultContentType();
        if (StringUtils.isBlank(contentType)) {
            contentType = HttpHeaderValues.APPLICATION_OCTET_STREAM;
        }
        encoder.addFile(this.name, this.fileName, contentType, ContentBodySupport.flatten(prepared.parts()));
    }

    public static final class Builder {
        private String      name;
        private String      value;
        private String      fileName;
        private ContentBody body;

        /** Sets the part field-name used in {@code Content-Disposition}. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the plain field value for a non-file part. */
        public Builder value(String value) {
            this.value = value;
            return this;
        }

        /** Sets the transmitted filename for a file part. */
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        /** Sets the body used as the file part payload. */
        public Builder body(ContentBody body) {
            this.body = body;
            return this;
        }

        /** Configures this part as a regular field in a multipart form body. */
        public Builder field(String name, String value) {
            this.name = name;
            this.value = value == null ? "" : value;
            this.fileName = null;
            this.body = null;
            return this;
        }

        /** Configures this part as a file upload entry in a multipart form body. */
        public Builder file(String fieldName, String fileName, ContentBody body) {
            this.name = fieldName;
            this.fileName = fileName;
            this.body = body;
            this.value = null;
            return this;
        }

        /** Validates the part shape and builds the immutable multipart part. */
        public MultipartPart build() {
            if (StringUtils.isBlank(this.name)) {
                throw new IllegalArgumentException("multipart field name must not be blank");
            }
            if (this.fileName == null) {
                return new MultipartPart(this);
            }
            if (StringUtils.isBlank(this.fileName)) {
                throw new IllegalArgumentException("multipart file name must not be blank");
            }
            if (this.body == null) {
                throw new IllegalArgumentException("multipart body must not be null");
            }
            return new MultipartPart(this);
        }
    }
}
