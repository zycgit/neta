package net.hasor.neta.codec.http;

/**
 * Marks the end of the header section for a request or response.
 * <p>
 * This object is still a header block and may therefore carry header fields. Its primary role is
 * to declare that the next HTTP object, if any, belongs to the body section.
 */
public interface LastHttpHeaders extends HttpHeaders {
}