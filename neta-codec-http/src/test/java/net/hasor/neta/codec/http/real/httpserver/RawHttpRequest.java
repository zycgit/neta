package net.hasor.neta.codec.http.real.httpserver;

import java.nio.charset.StandardCharsets;

public final class RawHttpRequest {
    public final String method;
    public final String path;
    public final String contentType;
    public final String body;

    public RawHttpRequest(String method, String path, String contentType, byte[] bodyBytes) {
        this.method = method;
        this.path = path;
        this.contentType = contentType;
        this.body = new String(bodyBytes, StandardCharsets.UTF_8);
    }
}