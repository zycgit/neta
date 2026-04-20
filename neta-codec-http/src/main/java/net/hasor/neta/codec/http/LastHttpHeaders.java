package net.hasor.neta.codec.http;
/**
 * Marks the end of the request or response header section.
 * <p>
 * This object is still a header block and may therefore carry header fields.
 * Its main role is to declare that the initial header section has ended, so
 * subsequent objects move into the content phase or the trailer phase.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-10
 */
public interface LastHttpHeaders extends HttpHeaders {
}