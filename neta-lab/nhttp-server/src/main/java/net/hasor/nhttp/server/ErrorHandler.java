/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

import java.io.IOException;

/**
 * Handles errors that occur during request processing.
 * <p>Called when an unhandled exception escapes a servlet or filter, or when
 * {@link ServletResponse#sendError} is invoked.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ErrorHandler {

    /**
     * Handles the given error by writing an appropriate response.
     * <p>Implementations must check {@link ServletResponse#isCommitted()} before writing;
     * if the response is already committed, the error can only be logged.</p>
     *
     * @param statusCode HTTP status code (e.g. 500, 404)
     * @param message    human-readable error message, may be null
     * @param cause      the underlying exception, may be null
     * @param request    the current request
     * @param response   the current response
     * @throws IOException if writing the error response fails
     */
    void handleError(int statusCode, String message, Throwable cause, //
            ServletRequest request, ServletResponse response) throws IOException;
}
