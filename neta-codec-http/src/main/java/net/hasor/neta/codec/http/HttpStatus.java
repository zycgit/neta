/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
/**
 * Represents an HTTP response status code and its reason phrase, as defined in
 * <a href="https://tools.ietf.org/html/rfc7231#section-6">RFC 7231, Section 6</a>.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public final class HttpStatus {
    // --- 1xx Informational (RFC 7231 §6.2) ---
    public static final HttpStatus CONTINUE            = new HttpStatus(100, "Continue");
    public static final HttpStatus SWITCHING_PROTOCOLS = new HttpStatus(101, "Switching Protocols");
    public static final HttpStatus PROCESSING          = new HttpStatus(102, "Processing");

    // --- 2xx Success (RFC 7231 §6.3) ---
    public static final HttpStatus OK                            = new HttpStatus(200, "OK");
    public static final HttpStatus CREATED                       = new HttpStatus(201, "Created");
    public static final HttpStatus ACCEPTED                      = new HttpStatus(202, "Accepted");
    public static final HttpStatus NON_AUTHORITATIVE_INFORMATION = new HttpStatus(203, "Non-Authoritative Information");
    public static final HttpStatus NO_CONTENT                    = new HttpStatus(204, "No Content");
    public static final HttpStatus RESET_CONTENT                 = new HttpStatus(205, "Reset Content");
    public static final HttpStatus PARTIAL_CONTENT               = new HttpStatus(206, "Partial Content");
    public static final HttpStatus MULTI_STATUS                  = new HttpStatus(207, "Multi-Status");

    // --- 3xx Redirection (RFC 7231 §6.4) ---
    public static final HttpStatus MULTIPLE_CHOICES   = new HttpStatus(300, "Multiple Choices");
    public static final HttpStatus MOVED_PERMANENTLY  = new HttpStatus(301, "Moved Permanently");
    public static final HttpStatus FOUND              = new HttpStatus(302, "Found");
    public static final HttpStatus SEE_OTHER          = new HttpStatus(303, "See Other");
    public static final HttpStatus NOT_MODIFIED       = new HttpStatus(304, "Not Modified");
    public static final HttpStatus USE_PROXY          = new HttpStatus(305, "Use Proxy");
    public static final HttpStatus TEMPORARY_REDIRECT = new HttpStatus(307, "Temporary Redirect");
    public static final HttpStatus PERMANENT_REDIRECT = new HttpStatus(308, "Permanent Redirect");

    // --- 4xx Client Error (RFC 7231 §6.5) ---
    public static final HttpStatus BAD_REQUEST                     = new HttpStatus(400, "Bad Request");
    public static final HttpStatus UNAUTHORIZED                    = new HttpStatus(401, "Unauthorized");
    public static final HttpStatus PAYMENT_REQUIRED                = new HttpStatus(402, "Payment Required");
    public static final HttpStatus FORBIDDEN                       = new HttpStatus(403, "Forbidden");
    public static final HttpStatus NOT_FOUND                       = new HttpStatus(404, "Not Found");
    public static final HttpStatus METHOD_NOT_ALLOWED              = new HttpStatus(405, "Method Not Allowed");
    public static final HttpStatus NOT_ACCEPTABLE                  = new HttpStatus(406, "Not Acceptable");
    public static final HttpStatus PROXY_AUTHENTICATION_REQUIRED   = new HttpStatus(407, "Proxy Authentication Required");
    public static final HttpStatus REQUEST_TIMEOUT                 = new HttpStatus(408, "Request Timeout");
    public static final HttpStatus CONFLICT                        = new HttpStatus(409, "Conflict");
    public static final HttpStatus GONE                            = new HttpStatus(410, "Gone");
    public static final HttpStatus LENGTH_REQUIRED                 = new HttpStatus(411, "Length Required");
    public static final HttpStatus PRECONDITION_FAILED             = new HttpStatus(412, "Precondition Failed");
    public static final HttpStatus REQUEST_ENTITY_TOO_LARGE        = new HttpStatus(413, "Request Entity Too Large");
    public static final HttpStatus REQUEST_URI_TOO_LONG            = new HttpStatus(414, "Request-URI Too Long");
    public static final HttpStatus UNSUPPORTED_MEDIA_TYPE          = new HttpStatus(415, "Unsupported Media Type");
    public static final HttpStatus REQUESTED_RANGE_NOT_SATISFIABLE = new HttpStatus(416, "Requested Range Not Satisfiable");
    public static final HttpStatus EXPECTATION_FAILED              = new HttpStatus(417, "Expectation Failed");
    public static final HttpStatus MISDIRECTED_REQUEST             = new HttpStatus(421, "Misdirected Request");
    public static final HttpStatus UNPROCESSABLE_ENTITY            = new HttpStatus(422, "Unprocessable Entity");
    public static final HttpStatus LOCKED                          = new HttpStatus(423, "Locked");
    public static final HttpStatus FAILED_DEPENDENCY               = new HttpStatus(424, "Failed Dependency");
    public static final HttpStatus TOO_EARLY                       = new HttpStatus(425, "Too Early");
    public static final HttpStatus UPGRADE_REQUIRED                = new HttpStatus(426, "Upgrade Required");
    public static final HttpStatus PRECONDITION_REQUIRED           = new HttpStatus(428, "Precondition Required");
    public static final HttpStatus TOO_MANY_REQUESTS               = new HttpStatus(429, "Too Many Requests");
    public static final HttpStatus REQUEST_HEADER_FIELDS_TOO_LARGE = new HttpStatus(431, "Request Header Fields Too Large");
    public static final HttpStatus UNAVAILABLE_FOR_LEGAL_REASONS   = new HttpStatus(451, "Unavailable For Legal Reasons");

    // --- 5xx Server Error (RFC 7231 §6.6) ---
    public static final HttpStatus INTERNAL_SERVER_ERROR           = new HttpStatus(500, "Internal Server Error");
    public static final HttpStatus NOT_IMPLEMENTED                 = new HttpStatus(501, "Not Implemented");
    public static final HttpStatus BAD_GATEWAY                     = new HttpStatus(502, "Bad Gateway");
    public static final HttpStatus SERVICE_UNAVAILABLE             = new HttpStatus(503, "Service Unavailable");
    public static final HttpStatus GATEWAY_TIMEOUT                 = new HttpStatus(504, "Gateway Timeout");
    public static final HttpStatus HTTP_VERSION_NOT_SUPPORTED      = new HttpStatus(505, "HTTP Version Not Supported");
    public static final HttpStatus VARIANT_ALSO_NEGOTIATES         = new HttpStatus(506, "Variant Also Negotiates");
    public static final HttpStatus INSUFFICIENT_STORAGE            = new HttpStatus(507, "Insufficient Storage");
    public static final HttpStatus NOT_EXTENDED                    = new HttpStatus(510, "Not Extended");
    public static final HttpStatus NETWORK_AUTHENTICATION_REQUIRED = new HttpStatus(511, "Network Authentication Required");

    private static final Map<Integer, HttpStatus> KNOWN_STATUSES = new HashMap<>();
    /** Fast array lookup table for status codes 100 through 599, avoiding Integer boxing and HashMap overhead. */
    private static final HttpStatus[]             STATUS_LOOKUP  = new HttpStatus[600];

    static {
        register(CONTINUE, SWITCHING_PROTOCOLS, PROCESSING);
        register(OK, CREATED, ACCEPTED, NON_AUTHORITATIVE_INFORMATION, NO_CONTENT, RESET_CONTENT, PARTIAL_CONTENT, MULTI_STATUS);
        register(MULTIPLE_CHOICES, MOVED_PERMANENTLY, FOUND, SEE_OTHER, NOT_MODIFIED, USE_PROXY, TEMPORARY_REDIRECT, PERMANENT_REDIRECT);
        register(BAD_REQUEST, UNAUTHORIZED, PAYMENT_REQUIRED, FORBIDDEN, NOT_FOUND, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE);
        register(PROXY_AUTHENTICATION_REQUIRED, REQUEST_TIMEOUT, CONFLICT, GONE, LENGTH_REQUIRED, PRECONDITION_FAILED);
        register(REQUEST_ENTITY_TOO_LARGE, REQUEST_URI_TOO_LONG, UNSUPPORTED_MEDIA_TYPE, REQUESTED_RANGE_NOT_SATISFIABLE);
        register(EXPECTATION_FAILED, MISDIRECTED_REQUEST, UNPROCESSABLE_ENTITY, LOCKED, FAILED_DEPENDENCY);
        register(TOO_EARLY, UPGRADE_REQUIRED, PRECONDITION_REQUIRED, TOO_MANY_REQUESTS, REQUEST_HEADER_FIELDS_TOO_LARGE, UNAVAILABLE_FOR_LEGAL_REASONS);
        register(INTERNAL_SERVER_ERROR, NOT_IMPLEMENTED, BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT);
        register(HTTP_VERSION_NOT_SUPPORTED, VARIANT_ALSO_NEGOTIATES, INSUFFICIENT_STORAGE, NOT_EXTENDED, NETWORK_AUTHENTICATION_REQUIRED);
    }

    private final int    code;
    private final String reasonPhrase;
    private final String codeStr;
    private final byte[] codeBytes;
    private final byte[] reasonPhraseBytes;

    /**
     * Creates an HttpStatus with the specified status code and reason phrase.
     * @param code HTTP status code, typically in the range 100 to 599
     * @param reasonPhrase reason phrase
     */
    public HttpStatus(int code, String reasonPhrase) {
        if (code < 100 || code > 999) {
            throw new IllegalArgumentException("code must be between 100 and 999: " + code);
        }
        if (reasonPhrase == null) {
            throw new IllegalArgumentException("reasonPhrase must not be null");
        }
        this.code = code;
        this.reasonPhrase = reasonPhrase;
        this.codeStr = String.valueOf(code);
        this.codeBytes = this.codeStr.getBytes(StandardCharsets.US_ASCII);
        this.reasonPhraseBytes = reasonPhrase.getBytes(StandardCharsets.US_ASCII);
    }

    private static void register(HttpStatus... statuses) {
        for (HttpStatus status : statuses) {
            KNOWN_STATUSES.put(status.code, status);
            if (status.code < STATUS_LOOKUP.length) {
                STATUS_LOOKUP[status.code] = status;
            }
        }
    }

    /**
     * Returns the {@link HttpStatus} for the given status code.
     * Returns a cached constant for standard status codes; otherwise creates a new instance with the default reason phrase "Unknown Status {code}".
     * @param code status code
     * @return matching HttpStatus
     */
    public static HttpStatus valueOf(int code) {
        // Fast path: use array lookup for common status codes to avoid boxing and HashMap overhead.
        if (code >= 100 && code < STATUS_LOOKUP.length) {
            HttpStatus known = STATUS_LOOKUP[code];
            if (known != null) {
                return known;
            }
        }
        return new HttpStatus(code, "Unknown Status " + code);
    }

    /**
     * Returns the {@link HttpStatus} for the given status code and reason phrase.
     * Returns a cached constant when both the standard status code and reason phrase match.
     * @param code status code
     * @param reasonPhrase reason phrase
     * @return matching HttpStatus
     */
    public static HttpStatus valueOf(int code, String reasonPhrase) {
        // Fast path: use array lookup for common status codes.
        if (code >= 100 && code < STATUS_LOOKUP.length) {
            HttpStatus known = STATUS_LOOKUP[code];
            if (known != null && known.reasonPhrase.equals(reasonPhrase)) {
                return known;
            }
        }
        return new HttpStatus(code, reasonPhrase);
    }

    /**
     * Returns the {@link HttpStatus} for the given status code text and reason phrase text.
     * @param codeText status code text
     * @param reasonPhrase reason phrase text
     * @return matching HttpStatus
     */
    public static HttpStatus valueOf(CharSequence codeText, CharSequence reasonPhrase) {
        if (codeText == null || codeText.length() == 0) {
            throw new IllegalArgumentException("codeText must not be empty");
        }
        int code = 0;
        for (int i = 0; i < codeText.length(); i++) {
            char ch = codeText.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("invalid status code: " + codeText);
            }
            code = code * 10 + (ch - '0');
        }

        if (code >= 100 && code < STATUS_LOOKUP.length) {
            HttpStatus known = STATUS_LOOKUP[code];
            if (known != null && reasonEquals(known.reasonPhrase, reasonPhrase)) {
                return known;
            }
        }

        String reason = reasonPhrase == null ? "" : reasonPhrase.toString();
        return new HttpStatus(code, reason);
    }

    private static boolean reasonEquals(String knownReason, CharSequence candidate) {
        if (candidate == null) {
            return knownReason.isEmpty();
        }
        if (knownReason.length() != candidate.length()) {
            return false;
        }
        for (int i = 0; i < knownReason.length(); i++) {
            if (knownReason.charAt(i) != candidate.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the HTTP status code, such as 200 or 404.
     * @return HTTP status code
     */
    public int code() {
        return code;
    }

    /**
     * Returns the reason phrase, such as "OK" or "Not Found".
     * @return reason phrase
     */
    public String reasonPhrase() {
        return reasonPhrase;
    }

    /**
     * Returns the cached string form of the status code, such as "200" or "404".
     * @return string form of the status code
     */
    public String codeAsString() {
        return codeStr;
    }

    /**
     * Returns the cached ASCII bytes for the status code.
     * @return status code bytes
     */
    public byte[] codeBytes() {
        return codeBytes;
    }

    /**
     * Returns the cached ASCII bytes for the reason phrase.
     * @return reason phrase bytes
     */
    public byte[] reasonPhraseBytes() {
        return reasonPhraseBytes;
    }

    /**
     * Returns the class of this status code.
     * <ul>
     *   <li>1xx: informational response</li>
     *   <li>2xx: success</li>
     *   <li>3xx: redirection</li>
     *   <li>4xx: client error</li>
     *   <li>5xx: server error</li>
     * </ul>
     * @return status code class
     */
    public int codeClass() {
        return code / 100;
    }

    /**
     * Returns whether this status is informational (1xx).
     * @return whether this is 1xx
     */
    public boolean isInformational() {
        return codeClass() == 1;
    }

    /**
     * Returns whether this status indicates success (2xx).
     * @return whether this is 2xx
     */
    public boolean isSuccess() {
        return codeClass() == 2;
    }

    /**
     * Returns whether this status indicates redirection (3xx).
     * @return whether this is 3xx
     */
    public boolean isRedirection() {
        return codeClass() == 3;
    }

    /**
     * Returns whether this status indicates a client error (4xx).
     * @return whether this is 4xx
     */
    public boolean isClientError() {
        return codeClass() == 4;
    }

    /**
     * Returns whether this status indicates a server error (5xx).
     * @return whether this is 5xx
     */
    public boolean isServerError() {
        return codeClass() == 5;
    }

    @Override
    public String toString() {
        return code + " " + reasonPhrase;
    }

    @Override
    public int hashCode() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpStatus)) {
            return false;
        }
        return code == ((HttpStatus) o).code;
    }
}
