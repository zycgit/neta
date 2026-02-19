package net.hasor.neta.codec.http.exception;

/**
 * HTTP Body/Content 超出最大限制时抛出。
 */
public class HttpContentTooLargeException extends HttpProtocolException {
    public HttpContentTooLargeException(String message) {
        super(message);
    }
}
