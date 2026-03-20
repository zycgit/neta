package net.hasor.neta.codec.http.simple;

public final class RawHttpResponse {
    public final int    statusCode;
    public final String reason;
    public final String contentType;
    public final String body;

    public RawHttpResponse(int statusCode, String reason, String contentType, String body) {
        this.statusCode = statusCode;
        this.reason = reason;
        this.contentType = contentType;
        this.body = body;
    }
}