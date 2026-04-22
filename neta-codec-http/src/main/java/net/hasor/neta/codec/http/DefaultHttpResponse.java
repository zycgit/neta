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
 * This object stores only the response status line. Header blocks and content chunks
 * are represented later in the message stream as separate {@link HttpHeaders} and
 * {@link HttpContent} objects.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultHttpResponse extends AbstractHttpObject<HttpResponse> implements HttpResponse {
    private HttpVersion  version;
    private HttpStatus   status;
    private CharSequence reasonText;
    private CharSequence versionText;
    private CharSequence statusText;

    /**
     * Create a response status-line object.
     * @param version HTTP version
     * @param status HTTP response status
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
     * Create a response status-line object from parsed text fields.
     * @param version raw protocol version text
     * @param status raw status code text
     * @param reason raw reason phrase text
     */
    public DefaultHttpResponse(String version, String status, String reason) {
        this(version, status, (CharSequence) reason);
    }

    public DefaultHttpResponse(CharSequence version, CharSequence status, CharSequence reason) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be empty");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be empty");
        }
        this.versionText = version;
        this.statusText = status;
        this.reasonText = reason == null ? "" : reason;
    }

    /**
     * Create a response status object from a complete status line without the trailing CRLF.
     * @param statusLine status line bytes
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
    protected HttpResponse self() {
        return this;
    }

    /**
     * Return the protocol version.
     */
    @Override
    public HttpVersion protocolVersion() {
        if (this.version == null) {
            CharSequence rawVersion = this.versionText;
            this.version = HttpVersion.valueOf(rawVersion);
            this.versionText = this.version.text();
            HttpCharSequences.release(rawVersion);
        }
        return version;
    }

    /**
     * Set the protocol version on the status line.
     * @param version protocol version
     * @return current response instance
     */
    @Override
    public HttpResponse protocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        HttpCharSequences.release(this.versionText);
        this.version = version;
        this.versionText = version.text();
        return this;
    }

    /**
     * Return the raw protocol version text.
     * @return raw protocol version text
     */
    public String protocolVersionText() {
        if (this.version != null) {
            this.versionText = this.version.text();
            return (String) this.versionText;
        }
        String resolved = HttpCharSequences.materialize(this.versionText);
        this.versionText = resolved;
        return resolved;
    }

    /**
     * Return the response status.
     */
    @Override
    public HttpStatus status() {
        if (this.status == null) {
            CharSequence rawStatus = this.statusText;
            CharSequence rawReason = this.reasonText;
            this.status = HttpStatus.valueOf(rawStatus, rawReason);
            this.statusText = this.status.codeAsString();
            this.reasonText = this.status.reasonPhrase();
            HttpCharSequences.release(rawStatus);
            HttpCharSequences.release(rawReason);
        }
        return status;
    }

    /**
     * Set the response status on the status line.
     * @param status response status
     * @return current response instance
     */
    @Override
    public HttpResponse status(HttpStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        HttpCharSequences.release(this.statusText);
        HttpCharSequences.release(this.reasonText);
        this.status = status;
        this.statusText = status.codeAsString();
        this.reasonText = status.reasonPhrase();
        return this;
    }

    /**
     * Return the status code text.
     */
    @Override
    public String statusText() {
        if (this.status != null) {
            this.statusText = this.status.codeAsString();
            return (String) this.statusText;
        }
        String resolved = HttpCharSequences.materialize(this.statusText);
        this.statusText = resolved;
        return resolved;
    }

    /**
     * Set the reason phrase.
     * @param reason reason phrase
     * @return current response instance
     */
    @Override
    public HttpResponse reasonText(String reason) {
        HttpCharSequences.release(this.reasonText);
        this.reasonText = reason;
        return this;
    }

    /**
     * Return the reason phrase.
     */
    @Override
    public String reasonText() {
        if (this.status != null) {
            this.reasonText = this.status.reasonPhrase();
            return (String) this.reasonText;
        }
        String resolved = HttpCharSequences.materialize(this.reasonText);
        this.reasonText = resolved;
        return resolved;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(streamId=" + this.streamId() + ", version: " + protocolVersionText() + ", status: " + statusText() + ' ' + reasonText() + ')';
    }

    /**
     * Release the state held by this response object.
     */
    @Override
    public void release() {
        this.resetHttpObjectState();
        HttpCharSequences.release(this.versionText);
        HttpCharSequences.release(this.statusText);
        HttpCharSequences.release(this.reasonText);
        this.version = null;
        this.status = null;
        this.versionText = null;
        this.statusText = null;
        this.reasonText = null;
    }
}