package net.hasor.neta.codec.http;

/**
 * Represents one trailing header block emitted after the message body of a chunked message.
 * <p>
 * Trailer headers are distinct from the initial header section. They appear after the final
 * chunk marker and before {@link LastHttpContent}.
 */
public interface TrailerHttpHeaders extends HttpHeaders {
}