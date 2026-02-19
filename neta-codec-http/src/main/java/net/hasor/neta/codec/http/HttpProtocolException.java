package net.hasor.neta.codec.http;

/**
 * HTTP 协议相关异常的统一基类。
 * 所有协议边界、格式、状态等异常均应继承自本类。
 */
public class HttpProtocolException extends RuntimeException {
    public HttpProtocolException(String message) {
        super(message);
    }

    public HttpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpProtocolException(Throwable cause) {
        super(cause);
    }
}
