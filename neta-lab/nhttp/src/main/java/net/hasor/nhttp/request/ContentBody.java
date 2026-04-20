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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Represents only the HTTP message body.
 * <p>
 * A {@link ContentBody} is still protocol-neutral at this stage. RFC-facing decisions such as
 * whether the outbound request can carry {@code Content-Length} or must fall back to
 * {@code Transfer-Encoding: chunked} for HTTP/1.1 are deferred until {@link #prepare()} and
 * {@link HttpWriter#write(Request, net.hasor.neta.codec.http.HttpVersion)} run.
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class ContentBody {
    public static final int DEFAULT_STREAM_CHUNK_SIZE = 8192;

    /** Returns an explicit zero-length body. */
    public static ContentBody empty() {
        return ByteArrayBody.builder().bodyBytes(new byte[0]).build();
    }

    /** Creates a fixed-length body backed by a defensive copy of the byte array. */
    public static ContentBody bytes(byte[] data) {
        return byteArrayBuilder().bodyBytes(data).build();
    }

    /** Creates a fixed-length body backed directly by an existing {@link ByteBuf}. */
    public static ContentBody byteBuf(ByteBuf byteBuf) {
        return byteBufBuilder().byteBuf(byteBuf).build();
    }

    /** Creates a UTF-8 {@code text/plain} body. */
    public static ContentBody text(String text) {
        return text(text, StandardCharsets.UTF_8);
    }

    /**
     * Creates a textual body whose emitted {@code Content-Type} carries the chosen charset as a
     * media-type parameter.
     */
    public static ContentBody text(String text, Charset charset) {
        return textBuilder().text(text).charset(charset).build();
    }

    /** Creates a UTF-8 JSON body using the {@code application/json} media type. */
    public static ContentBody json(String jsonText) {
        return json(jsonText, StandardCharsets.UTF_8);
    }

    /**
     * Creates a JSON body whose emitted media type remains {@code application/json}; JSON text is
     * expected to follow RFC 8259 semantics while transport framing is handled later by the writer.
     */
    public static ContentBody json(String jsonText, Charset charset) {
        return jsonBuilder().jsonText(jsonText).charset(charset).build();
    }

    /** Creates a fixed-length body by reading the content of a file. */
    public static ContentBody file(File file) {
        return fileBuilder().file(file).build();
    }

    /**
     * Creates a stream-backed body with unknown length.
     * <p>
     * When later written as HTTP/1.1 and no explicit framing headers were supplied, this shape can
     * trigger {@code Transfer-Encoding: chunked} per RFC 9112 Section 7.1.
     */
    public static ContentBody stream(InputStream inputStream) {
        return streamBuilder().inputStream(inputStream).build();
    }

    /**
     * Creates a stream-backed body with an explicit byte count so the writer can emit
     * {@code Content-Length} per RFC 9112 Section 6.2 instead of chunked transfer coding.
     */
    public static ContentBody stream(InputStream inputStream, long contentLength) {
        return streamBuilder().inputStream(inputStream).contentLength(contentLength).build();
    }

    /**
     * Treats a {@link ByteBuf} as a streaming source with unknown total length so later HTTP/1.1
     * serialization may choose chunked framing.
     */
    public static ContentBody stream(ByteBuf byteBuf) {
        return streamBuilder().byteBuf(byteBuf).build();
    }

    /** Creates a {@link ByteBuf}-backed streaming body with a caller-supplied fixed length. */
    public static ContentBody stream(ByteBuf byteBuf, long contentLength) {
        return streamBuilder().byteBuf(byteBuf).contentLength(contentLength).build();
    }

    /** Creates a stream-backed body from a lazily opened {@link StreamSource}. */
    public static ContentBody stream(StreamSource streamSource) {
        return streamBuilder().source(streamSource).build();
    }

    /** Creates a stream-backed body with an explicit content length and lazy stream opening. */
    public static ContentBody stream(StreamSource streamSource, long contentLength) {
        return streamBuilder().source(streamSource).contentLength(contentLength).build();
    }

    /**
     * Creates a stream-backed body with caller-controlled chunk splitting.
     * <p>
     * The {@code chunkSize} only affects local buffering and the number of generated content frames;
     * the actual HTTP/1.1 chunked transfer-coding decision is still taken later by the writer.
     */
    public static ContentBody stream(StreamSource streamSource, Long contentLength, int chunkSize) {
        return streamBuilder().source(streamSource).contentLength(contentLength).chunkSize(chunkSize).build();
    }

    /**
     * Creates an {@code application/x-www-form-urlencoded} body suitable for classic HTML form
     * submission.
     */
    public static ContentBody form(Consumer<FormBody.Builder> consumer) {
        FormBody.Builder builder = formBuilder();
        if (consumer != null) {
            consumer.accept(builder);
        }
        return builder.build();
    }

    /**
     * Creates a {@code multipart/form-data} body.
     * <p>
     * The final boundary parameter and part framing follow the multipart rules implemented by
     * {@link MultipartEncoder}, matching RFC 7578-style form uploads.
     */
    public static ContentBody multipart(Consumer<MultipartBody.Builder> consumer) {
        MultipartBody.Builder builder = multipartBuilder();
        if (consumer != null) {
            consumer.accept(builder);
        }
        return builder.build();
    }

    public static ByteArrayBody.Builder byteArrayBuilder() {
        return ByteArrayBody.builder();
    }

    public static ByteBufBody.Builder byteBufBuilder() {
        return ByteBufBody.builder();
    }

    public static TextBody.Builder textBuilder() {
        return TextBody.builder();
    }

    public static JsonBody.Builder jsonBuilder() {
        return JsonBody.builder();
    }

    public static FileBody.Builder fileBuilder() {
        return FileBody.builder();
    }

    public static StreamBody.Builder streamBuilder() {
        return StreamBody.builder();
    }

    public static FormBody.Builder formBuilder() {
        return FormBody.builder();
    }

    public static MultipartBody.Builder multipartBuilder() {
        return MultipartBody.builder();
    }

    /**
     * Materializes this logical body into immutable prepared parts so the writer can determine wire
     * framing such as {@code Content-Length} versus chunked transfer coding.
     */
    public final PreparedBody prepare() throws IOException {
        return this.doPrepare();
    }

    protected abstract PreparedBody doPrepare() throws IOException;
}