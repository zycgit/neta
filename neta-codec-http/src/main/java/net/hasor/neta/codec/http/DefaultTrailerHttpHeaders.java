/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Default implementation of {@link TrailerHttpHeaders}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class DefaultTrailerHttpHeaders extends DefaultHttpHeaders implements TrailerHttpHeaders {
    public static final TrailerHttpHeaders EMPTY = new DefaultTrailerHttpHeaders();

    /**
     * Create an empty trailer header block.
     */
    public DefaultTrailerHttpHeaders() {
        super();
    }
}
