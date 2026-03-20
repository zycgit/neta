package net.hasor.neta.codec.http.simple;

public interface RawHttpHandler {
    RawHttpResponse handle(RawHttpRequest request) throws Exception;
}