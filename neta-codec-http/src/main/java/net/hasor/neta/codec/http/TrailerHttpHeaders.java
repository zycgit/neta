package net.hasor.neta.codec.http;
/**
 * Represents a trailer header block emitted after a chunked message body.
 * <p>
 * Trailer headers are independent from the initial header section. They appear
 * after the last chunk marker and before {@link LastHttpContent}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public interface TrailerHttpHeaders extends HttpHeaders {
}