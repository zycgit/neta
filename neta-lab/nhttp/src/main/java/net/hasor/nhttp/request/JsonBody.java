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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaderValues;

/** JSON body using {@code application/json} by default. */
public final class JsonBody extends ContentBody {
    private final String  jsonText;
    private final Charset charset;
    private final String  mediaType;

    private JsonBody(Builder builder) {
        this.jsonText = builder.jsonText == null ? "" : builder.jsonText;
        this.charset = builder.charset == null ? StandardCharsets.UTF_8 : builder.charset;
        this.mediaType = builder.mediaType == null ? HttpHeaderValues.APPLICATION_JSON : builder.mediaType;
    }

    /** Starts a JSON body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() {
        byte[] bytes = this.jsonText.getBytes(this.charset);
        return PreparedBody.single(ByteBuf.wrap(bytes), this.mediaType + "; charset=" + ContentBodySupport.charsetName(this.charset), (long) bytes.length);
    }

    public static final class Builder {
        private String  jsonText;
        private Charset charset   = StandardCharsets.UTF_8;
        private String  mediaType = HttpHeaderValues.APPLICATION_JSON;

        /** Sets the JSON text payload. */
        public Builder jsonText(String jsonText) {
            this.jsonText = jsonText;
            return this;
        }

        /** Sets the charset used to encode the JSON text. */
        public Builder charset(Charset charset) {
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            return this;
        }

        /** Sets the media type, defaulting to {@code application/json}. */
        public Builder mediaType(String mediaType) {
            this.mediaType = mediaType == null ? HttpHeaderValues.APPLICATION_JSON : mediaType;
            return this;
        }

        /** Builds the immutable JSON body. */
        public JsonBody build() {
            return new JsonBody(this);
        }
    }
}