package net.hasor.neta.codec.http.real.httpserver;

public interface RawHttpHandler {
    RawHttpResponse handle(RawHttpRequest request) throws Exception;
}