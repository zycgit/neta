package net.hasor.neta.codec.http;
import java.util.List;
import java.util.Set;

/**
 * Represents one HTTP header block.
 * <p>
 * Header blocks are emitted after {@link HttpRequest} or {@link HttpResponse} and before any
 * {@link HttpContent}. A message may contain zero or more ordinary header blocks and is closed by
 * a final {@link LastHttpHeaders} marker. Chunked messages may later emit separate
 * {@link TrailerHttpHeaders} blocks before {@link LastHttpContent}.
 */
public interface HttpHeaders extends HttpObject {
    /** Returns all values for the given header in insertion order. */
    List<String> getValues(String name);

    /** Returns the first header value parsed as int, or the provided default. */
    int getInt(String name, int defaultValue);

    /** Returns the first header value parsed as long, or the provided default. */
    long getLong(String name, long defaultValue);

    /** Returns the first header value, or {@code null} when absent. */
    String getString(String name);

    /** Returns whether the header name exists. */
    boolean containsHeader(String name);

    /** Returns distinct header names in insertion order. */
    Set<String> headerNames();

    /** Returns the number of stored header entries, including repeated names. */
    int headerSize();

    HttpHeaders addHeader(String name, String value);

    HttpHeaders setHeader(String name, String value);

    HttpHeaders clearHeader();

    HttpHeaders removeHeader(String name);

    HttpHeaders appendHeaders(HttpHeaders headers);
}