package net.hasor.neta.codec.http.exception;

/**
 * HTTP Header 超出最大限制时抛出。
 */
public class HttpHeaderTooLargeException extends HttpProtocolException {
    public HttpHeaderTooLargeException(String message) {
        super(message);
    }
}
