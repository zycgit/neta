/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
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
