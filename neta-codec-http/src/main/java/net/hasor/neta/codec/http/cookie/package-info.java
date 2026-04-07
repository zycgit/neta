/**
 * HTTP cookie encoding and decoding utilities.
 * <p>This package covers two different HTTP semantic paths at the same time:
 * <h3>Request side: {@code Cookie} header</h3>
 * When a request contains multiple cookies, multiple {@code name=value} fragments should be merged
 * into one {@code Cookie} header value and then encoded or decoded by
 * {@link net.hasor.neta.codec.http.cookie.CookieEncoder} and
 * {@link net.hasor.neta.codec.http.cookie.CookieDecoder}.
 * <pre>
 *   Cookie: sid=old; sid=new; theme=dark
 * </pre>
 * <h3>Response side: {@code Set-Cookie} header</h3>
 * When a response contains multiple cookies, they should not be merged into a single header value.
 * Instead, one {@code Set-Cookie} header should be written for each cookie, and
 * {@link net.hasor.neta.codec.http.cookie.ServerCookieEncoder}
 * and {@link net.hasor.neta.codec.http.cookie.ServerCookieDecoder} should process them one by one.
 * <pre>
 *   Set-Cookie: sid=old; Path=/legacy
 *   Set-Cookie: sid=new; Path=/app
 * </pre>
 */
package net.hasor.neta.codec.http.cookie;