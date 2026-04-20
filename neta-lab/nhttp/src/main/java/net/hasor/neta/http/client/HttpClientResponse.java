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
package net.hasor.neta.http.client;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.HttpVersion;

/**
 * Immutable aggregated HTTP response.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpClientResponse {
    private final URI                       uri;
    private final HttpVersion               version;
    private final HttpStatus                status;
    private final Map<String, List<String>> headers;
    private final byte[]                    bodyBytes;

    HttpClientResponse(URI uri, HttpVersion version, HttpStatus status, Map<String, List<String>> headers, byte[] bodyBytes) {
        this.uri = uri;
        this.version = version;
        this.status = status;
        this.headers = headers;
        this.bodyBytes = bodyBytes;
    }

    static HttpClientResponse of(URI uri, FullHttpResponse response) {
        try {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String name : response.headerNames()) {
                headers.put(name, new ArrayList<>(response.getValues(name)));
            }
            return new HttpClientResponse(uri, response.protocolVersion(), response.status(), headers, response.content().asByteArray());
        } finally {
            response.release();
        }
    }

    public URI uri() {
        return this.uri;
    }

    public HttpVersion version() {
        return this.version;
    }

    public HttpStatus status() {
        return this.status;
    }

    public int statusCode() {
        return this.status.code();
    }

    public String reasonPhrase() {
        return this.status.reasonPhrase();
    }

    public String header(String name) {
        List<String> values = this.findHeaderValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> headers(String name) {
        List<String> values = this.findHeaderValues(name);
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
    }

    public Map<String, List<String>> allHeaders() {
        return Collections.unmodifiableMap(this.headers);
    }

    public byte[] bodyBytes() {
        return this.bodyBytes;
    }

    public String bodyText() {
        return this.bodyText(StandardCharsets.UTF_8);
    }

    public String bodyText(Charset charset) {
        return new String(this.bodyBytes, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    private List<String> findHeaderValues(String name) {
        List<String> directValues = this.headers.get(name);
        if (directValues != null) {
            return directValues;
        }

        for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getKey(), name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}