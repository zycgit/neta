/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Marks the end of an HTTP message body.
 * <p>
 * This object terminates the content section that starts after
 * {@link LastHttpHeaders}. It may carry the final payload buffer, but in the
 * current object model it does not carry trailing headers.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface LastHttpContent extends HttpContent {
}
