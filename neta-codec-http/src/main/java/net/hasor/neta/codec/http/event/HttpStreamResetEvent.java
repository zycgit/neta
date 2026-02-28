/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.event;
/**
 * User event that requests an active reset (abort) of a specific HTTP stream.
 * <p>
 * This single class works for both HTTP/2 and HTTP/3:
 * <ul>
 *   <li>HTTP/2: the Duplexe handler sends a {@code RST_STREAM} frame using the mapped H2 error code.</li>
 *   <li>HTTP/3: the Duplexe handler calls {@code QuicStreamChannel.sendReset()} using the mapped H3 error code.</li>
 * </ul>
 * <p>
 * Fire via {@code context.fireUserEvent(HttpStreamResetEvent.class, event)}.
 * <p>
 * <b>Semantic constants</b> (recommended for protocol-agnostic application code):
 * <pre>{@code
 * // Cancel a stream - protocol-specific handler maps to CANCEL (H2) or H3_REQUEST_CANCELLED (H3)
 * context.fireUserEvent(HttpStreamResetEvent.class, HttpStreamResetEvent.cancel(request.streamId()));
 * }</pre>
 * <p>
 * <b>Raw protocol error codes</b> (when you know which protocol is in use):
 * <pre>{@code
 * import net.hasor.neta.codec.http.h2.Http2ErrorCode;
 * context.fireUserEvent(HttpStreamResetEvent.class,
 *         new HttpStreamResetEvent(streamId, Http2ErrorCode.REFUSED_STREAM));
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-28
 * @see net.hasor.neta.codec.http.h2.Http2ErrorCode
 * @see net.hasor.neta.codec.http.h3.Http3ErrorCode
 */
public final class HttpStreamResetEvent {
    /**
     * Semantic sentinel: "stream is no longer needed / cancelled by the requester".
     * <br>Mapped to {@code Http2ErrorCode.CANCEL} (0x08) for HTTP/2,
     * or {@code Http3ErrorCode.H3_REQUEST_CANCELLED} (0x010c) for HTTP/3.
     */
    public static final long CANCEL         = -1L;
    /**
     * Semantic sentinel: "internal processing error".
     * <br>Mapped to {@code Http2ErrorCode.INTERNAL_ERROR} (0x02) for HTTP/2,
     * or {@code Http3ErrorCode.H3_INTERNAL_ERROR} (0x0102) for HTTP/3.
     */
    public static final long INTERNAL_ERROR = -2L;
    /**
     * Semantic sentinel: "stream refused before any processing".
     * <br>Mapped to {@code Http2ErrorCode.REFUSED_STREAM} (0x07) for HTTP/2,
     * or {@code Http3ErrorCode.H3_REQUEST_REJECTED} (0x010b) for HTTP/3.
     */
    public static final long REFUSED        = -3L;

    private final long streamId;
    private final long errorCode;

    /**
     * Creates a reset event with an explicit raw error code.
     * Use {@link #CANCEL}, {@link #INTERNAL_ERROR}, {@link #REFUSED} for protocol-agnostic
     * semantic codes, or pass a protocol-specific value from {@code Http2ErrorCode} /
     * {@code Http3ErrorCode} directly.
     * @param streamId the stream ID to reset (H2: positive int; H3: QUIC stream ID as long)
     * @param errorCode raw protocol error code, or one of the semantic sentinel constants
     */
    public HttpStreamResetEvent(long streamId, long errorCode) {
        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    /**
     * Creates a cancel event (stream no longer needed).
     * <p>H2 → {@code RST_STREAM(CANCEL)}; H3 → {@code RESET_STREAM(H3_REQUEST_CANCELLED)}.
     */
    public static HttpStreamResetEvent cancel(long streamId) {
        return new HttpStreamResetEvent(streamId, CANCEL);
    }

    /**
     * Creates an internal-error reset event.
     * <p>H2 → {@code RST_STREAM(INTERNAL_ERROR)}; H3 → {@code RESET_STREAM(H3_INTERNAL_ERROR)}.
     */
    public static HttpStreamResetEvent internalError(long streamId) {
        return new HttpStreamResetEvent(streamId, INTERNAL_ERROR);
    }

    // ─── semantic factory methods ──────────────────────────────────────────────

    /**
     * Creates a refused event (stream rejected before any processing).
     * <p>H2 → {@code RST_STREAM(REFUSED_STREAM)}; H3 → {@code RESET_STREAM(H3_REQUEST_REJECTED)}.
     */
    public static HttpStreamResetEvent refused(long streamId) {
        return new HttpStreamResetEvent(streamId, REFUSED);
    }

    /** Returns the stream ID to reset. */
    public long streamId() {
        return streamId;
    }

    /**
     * Returns the error code for this reset event.
     * May be a raw protocol code (≥ 0) or a semantic sentinel ({@link #CANCEL},
     * {@link #INTERNAL_ERROR}, {@link #REFUSED}).
     */
    public long errorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        String codeStr;
        if (errorCode == CANCEL) {
            codeStr = "CANCEL";
        } else if (errorCode == INTERNAL_ERROR) {
            codeStr = "INTERNAL_ERROR";
        } else if (errorCode == REFUSED) {
            codeStr = "REFUSED";
        } else {
            codeStr = "0x" + Long.toHexString(errorCode);
        }
        return "HttpStreamResetEvent{streamId=" + streamId + ", errorCode=" + codeStr + "}";
    }
}
