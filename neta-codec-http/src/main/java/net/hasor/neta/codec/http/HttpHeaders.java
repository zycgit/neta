package net.hasor.neta.codec.http;
import java.util.List;
import java.util.Set;

/**
 * Represents an HTTP header block.
 * <p>
 * A header block appears after an {@link HttpRequest} or {@link HttpResponse} and
 * before any {@link HttpContent}. A message may contain zero or more regular header
 * blocks, and the header section is terminated by a final {@link LastHttpHeaders}.
 * For chunked messages, standalone {@link TrailerHttpHeaders} may still be emitted
 * before {@link LastHttpContent}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface HttpHeaders extends HttpObject {
    /**
     * Return all values for the specified header name in insertion order.
     * @param name header name
     * @return all matching header values
     */
    List<String> getValues(String name);

    /**
     * Return the first value for the specified header and parse it as an int;
     * return the default value if the header is missing or parsing fails.
     * @param name header name
     * @param defaultValue default value
     * @return parsed integer value
     */
    int getInt(String name, int defaultValue);

    /**
     * Return the first value for the specified header and parse it as a long;
     * return the default value if the header is missing or parsing fails.
     * @param name header name
     * @param defaultValue default value
     * @return parsed long value
     */
    long getLong(String name, long defaultValue);

    /**
     * Return the first value for the specified header, or {@code null} if absent.
     * @param name header name
     * @return first header value
     */
    String getString(String name);

    /**
     * Return whether the specified header name is present.
     * @param name header name
     * @return {@code true} if present
     */
    boolean containsHeader(String name);

    /**
     * Return the distinct header names in insertion order.
     * @return header name set
     */
    Set<String> headerNames();

    /**
     * Return the number of stored header entries; duplicate names are counted separately.
     * @return number of header entries
     */
    int headerSize();

    /**
     * Append a header field.
     * @param name header name
     * @param value header value
     * @return current header object
     */
    HttpHeaders addHeader(String name, String value);

    /**
     * Set a header field and replace any existing values with the same name.
     * @param name header name
     * @param value header value
     * @return current header object
     */
    HttpHeaders setHeader(String name, String value);

    /**
     * Remove all header fields.
     * @return current header object
     */
    HttpHeaders clearHeader();

    /**
     * Remove all header fields with the specified name.
     * @param name header name
     * @return current header object
     */
    HttpHeaders removeHeader(String name);

    /**
     * Append fields from another header block to the current header block.
     * @param headers source header block
     * @return current header object
     */
    HttpHeaders appendHeaders(HttpHeaders headers);
}