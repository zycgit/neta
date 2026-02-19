package net.hasor.neta.codec.http.exception;

/**
 * HTTP 协议格式错误时抛出。
 */
public class HttpMalformedRequestException extends HttpProtocolException {
    public HttpMalformedRequestException(String message) {
        super(message);
    }

    public HttpMalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
