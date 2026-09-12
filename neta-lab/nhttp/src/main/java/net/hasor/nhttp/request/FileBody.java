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
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Fixed-length body loaded from a file.
 * <p>
 * The file length is known up front, so later request serialization can emit
 * {@code Content-Length} without relying on chunked transfer coding.
 */
public final class FileBody extends ContentBody {
    private final File   file;
    private final String defaultContentType;

    private FileBody(Builder builder) {
        this.file = Objects.requireNonNull(builder.file, "file");
        this.defaultContentType = builder.defaultContentType;
    }

    /** Starts a file body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() throws IOException {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.swapFile();
        try (InputStream inputStream = Files.newInputStream(this.file.toPath())) {
            ContentBodySupport.copyToSwapFileBuffer(inputStream, buffer, ContentBody.DEFAULT_STREAM_CHUNK_SIZE);
        }
        buffer.markWriter();
        String contentType = this.defaultContentType == null ? Files.probeContentType(this.file.toPath()) : this.defaultContentType;
        return PreparedBody.single(buffer, contentType, this.file.length());
    }

    public static final class Builder {
        private File   file;
        private String defaultContentType;

        /** Sets the source file. */
        public Builder file(File file) {
            this.file = file;
            return this;
        }

        /** Sets the default media type, bypassing any type guessed from the file path. */
        public Builder contentType(String defaultContentType) {
            this.defaultContentType = defaultContentType;
            return this;
        }

        /** Builds the immutable file body. */
        public FileBody build() {
            return new FileBody(this);
        }
    }
}
