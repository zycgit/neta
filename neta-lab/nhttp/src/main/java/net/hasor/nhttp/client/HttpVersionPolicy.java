/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client;

/**
 * HTTP version policy used by {@link HttpClient} when selecting the outbound protocol pipeline.
 * @author 赵永春 (zyc@hasor.net)
 */
public enum HttpVersionPolicy {
    AUTO,
    HTTP_1_1,
    HTTP_2
}
