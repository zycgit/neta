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
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.StringView;

/**
 * Default implementation of {@link HttpResponse}.
 * <p>
 * This object holds only the response status line. Header blocks and body chunks are represented
 * by separate {@link HttpHeaders} and {@link HttpContent} objects later in the message flow.
 */
public class DefaultHttpResponse implements HttpResponse {
    private int          streamId;
    private HttpVersion  version;
    private HttpStatus   status;
    private CharSequence reasonText;
    //
    private CharSequence versionText;
    private CharSequence statusText;
    //
    private ByteBuf      originalData;

    /**
     * Creates a response status-line object.
     * @param version the HTTP version
     * @param status the HTTP response status
     */
    public DefaultHttpResponse(HttpVersion version, HttpStatus status) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        this.version = version;
        this.status = status;
        this.versionText = version.text();
        this.statusText = status.codeAsString();
        this.reasonText = status.reasonPhrase();
    }

    /**
     * Creates a response status-line object backed by raw text views.
     * @param version the raw protocol version text
     * @param status the raw status code text
     * @param reason the raw reason phrase text
     */
    public DefaultHttpResponse(CharSequence version, CharSequence status, CharSequence reason) {
        if (version == null || version.length() == 0) {
            throw new IllegalArgumentException("version must not be empty");
        }
        if (status == null || status.length() == 0) {
            throw new IllegalArgumentException("status must not be empty");
        }

        this.versionText = version;
        this.statusText = status;
        this.reasonText = reason == null ? "" : reason;
    }

    /**
     * Creates a response status-line object from one complete status line without the trailing CRLF.
     * @param statusLine the status line bytes
     */
    public DefaultHttpResponse(ByteBuf statusLine) {
        if (statusLine == null) {
            throw new IllegalArgumentException("statusLine must not be null");
        }
        this.originalData = statusLine.retain();
    }

    @Override
    public int streamId() {
        return this.streamId;
    }

    @Override
    public HttpResponse streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public HttpVersion protocolVersion() {
        this.ensureLineParsed();
        if (this.version == null) {
            this.version = HttpVersion.valueOf(this.versionText);
        }
        return version;
    }

    /** Sets the protocol version carried by this status line. */
    public HttpResponse protocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        this.releaseSequence(this.versionText);
        this.releaseStatusLine();
        this.version = version;
        this.versionText = version.text();
        return this;
    }

    public String protocolVersionText() {
        this.ensureLineParsed();
        return this.versionText.toString();
    }

    @Override
    public HttpStatus status() {
        this.ensureLineParsed();
        if (this.status == null) {
            this.status = HttpStatus.valueOf(this.statusText, this.reasonText);
        }
        return status;
    }

    /** Sets the response status carried by this status line. */
    public HttpResponse status(HttpStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        this.releaseSequence(this.statusText);
        this.releaseSequence(this.reasonText);
        this.releaseStatusLine();
        this.status = status;
        this.statusText = status.codeAsString();
        this.reasonText = status.reasonPhrase();
        return this;
    }

    @Override
    public String statusText() {
        this.ensureLineParsed();
        return this.statusText.toString();
    }

    public HttpResponse reasonText(String reason) {
        this.releaseSequence(this.reasonText);
        this.reasonText = reason;
        return this;
    }

    @Override
    public String reasonText() {
        this.ensureLineParsed();
        return this.reasonText.toString();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", status: " + statusText() + ' ' + reasonText() + ')';
    }

    @Override
    public void release() {
        this.releaseStatusLine();
        this.releaseSequence(this.versionText);
        this.releaseSequence(this.statusText);
        this.releaseSequence(this.reasonText);
        this.versionText = null;
        this.statusText = null;
        this.reasonText = null;
    }

    private void ensureLineParsed() {
        if (this.versionText != null && this.statusText != null && this.reasonText != null) {
            return;
        }

        ByteBuf line = this.originalData;
        if (line == null) {
            throw new IllegalStateException("status line is not available");
        }

        int start = line.readerIndex();
        int end = start + line.readableBytes();
        int firstSpace = -1;
        int secondSpace = -1;
        for (int i = start; i < end; i++) {
            if (line.getByte(i) == ' ') {
                if (firstSpace < 0) {
                    firstSpace = i;
                } else {
                    secondSpace = i;
                    break;
                }
            }
        }
        if (firstSpace < 0) {
            throw new IllegalStateException("invalid status line");
        }
        if (secondSpace < 0) {
            secondSpace = end;
        }

        this.versionText = StringView.request(line, start, firstSpace - start);
        this.statusText = StringView.request(line, firstSpace + 1, secondSpace - firstSpace - 1);
        this.reasonText = secondSpace >= end ? "" : StringView.request(line, secondSpace + 1, end - secondSpace - 1);
        releaseStatusLine();
    }

    private void releaseStatusLine() {
        ByteBuf line = this.originalData;
        if (line != null) {
            this.originalData = null;
            line.release();
        }
    }

    private void releaseSequence(CharSequence value) {
        if (value instanceof StringView) {
            ((StringView) value).release();
        }
    }
}