package net.hasor.neta.codec.http;

/**
 * HTTP 请求行/响应行超长时抛出。
 */
public class HttpInitialLineTooLongException extends HttpProtocolException {
    public HttpInitialLineTooLongException(String message) {
        super(message);
    }
}
