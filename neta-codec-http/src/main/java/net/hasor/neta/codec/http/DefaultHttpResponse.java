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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link HttpResponse}.
 * <p>
 * This object holds only the response status line. Header blocks and body chunks are represented
 * by separate {@link HttpHeaders} and {@link HttpContent} objects later in the message flow.
 */
public class DefaultHttpResponse implements HttpResponse {
    private int         streamId;
    private HttpVersion version;
    private HttpStatus  status;
    private String      reasonText;
    private String      versionText;
    private String      statusText;

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
     * Creates a response status-line object from parsed text fields.
     * @param version the raw protocol version text
     * @param status the raw status code text
     * @param reason the raw reason phrase text
     */
    public DefaultHttpResponse(String version, String status, String reason) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
        if (status == null || status.isEmpty()) {
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
        parseStatusLine(statusLine);
    }

    private void parseStatusLine(ByteBuf line) {
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

        this.versionText = line.getString(start, firstSpace - start, StandardCharsets.US_ASCII);
        this.statusText = line.getString(firstSpace + 1, secondSpace - firstSpace - 1, StandardCharsets.US_ASCII);
        this.reasonText = secondSpace >= end ? "" : line.getString(secondSpace + 1, end - secondSpace - 1, StandardCharsets.US_ASCII);
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
        if (this.version == null) {
            this.version = HttpVersion.valueOf(this.versionText);
        }
        return version;
    }

    /** Sets the protocol version carried by this status line. */
    @Override
    public HttpResponse protocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.versionText = version.text();
        return this;
    }

    public String protocolVersionText() {
        return this.versionText;
    }

    @Override
    public HttpStatus status() {
        if (this.status == null) {
            this.status = HttpStatus.valueOf(this.statusText, this.reasonText);
        }
        return status;
    }

    /** Sets the response status carried by this status line. */
    @Override
    public HttpResponse status(HttpStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.status = status;
        this.statusText = status.codeAsString();
        this.reasonText = status.reasonPhrase();
        return this;
    }

    @Override
    public String statusText() {
        return this.statusText;
    }

    @Override
    public HttpResponse reasonText(String reason) {
        this.reasonText = reason;
        return this;
    }

    @Override
    public String reasonText() {
        return this.reasonText;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", status: " + statusText() + ' ' + reasonText() + ')';
    }

    @Override
    public void release() {
        this.streamId = 0;
        this.version = null;
        this.status = null;
        this.versionText = null;
        this.statusText = null;
        this.reasonText = null;
    }
}