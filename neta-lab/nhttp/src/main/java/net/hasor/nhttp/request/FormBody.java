/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaderValues;

/**
 * URL-encoded form body.
 * <p>
 * The prepared payload is emitted as {@code application/x-www-form-urlencoded} with a charset
 * parameter, matching the conventional body shape used by HTML form submissions.
 */
public final class FormBody extends ContentBody {
    private final Map<String, List<String>> fields;
    private final Charset                   charset;

    private FormBody(Builder builder) {
        this.fields = ContentBodySupport.copyFields(builder.fields);
        this.charset = builder.charset == null ? StandardCharsets.UTF_8 : builder.charset;
    }

    /** Starts a form body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() {
        byte[] formBytes = ContentBodySupport.encodeForm(this.fields, this.charset);
        return PreparedBody.single(ByteBuf.wrap(formBytes), HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED + "; charset=" + ContentBodySupport.charsetName(this.charset), (long) formBytes.length);
    }

    public static final class Builder {
        private final Map<String, List<String>> fields  = new LinkedHashMap<>();
        private Charset                         charset = StandardCharsets.UTF_8;

        /** Sets the charset used when percent-encoding names and values. */
        public Builder charset(Charset charset) {
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            return this;
        }

        /** Adds one form field value. Repeated names are preserved in insertion order. */
        public Builder add(String name, String value) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("form name must not be blank");
            }
            this.fields.computeIfAbsent(name, key -> new ArrayList<>()).add(value == null ? "" : value);
            return this;
        }

        /** Alias of {@link #add(String, String)} for readability in builder lambdas. */
        public Builder addField(String name, String value) {
            return this.add(name, value);
        }

        /** Builds the immutable form body. */
        public FormBody build() {
            return new FormBody(this);
        }
    }
}
