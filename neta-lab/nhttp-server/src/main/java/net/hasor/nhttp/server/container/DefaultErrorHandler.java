/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.container;

import java.io.IOException;
import net.hasor.cobble.logging.Logger;
import net.hasor.nhttp.server.ErrorHandler;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * Default {@link ErrorHandler} implementation.
 *
 * <p>Writes a minimal HTML error page when the response has not yet been committed.
 * If the response is already committed (headers sent), the error is only logged because
 * it is too late to change the status code or write a new body.</p>
 *
 * <h3>Output format</h3>
 * <pre>{@code
 * HTTP/1.1 500 Internal Server Error
 * Content-Type: text/html; charset=UTF-8
 *
 * <html><body><h1>500 Internal Server Error</h1><p>message</p></body></html>
 * }</pre>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultErrorHandler implements ErrorHandler {
    private static final Logger logger = Logger.getLogger(DefaultErrorHandler.class);

    @Override
    public void handleError(int statusCode, String message, Throwable cause, //
            ServletRequest request, ServletResponse response) throws IOException {
        // Log the error regardless of response state
        String logMsg = "HTTP " + statusCode + (message != null ? ": " + message : "") + " [" + request.getRequestURI() + "]";
        if (cause != null) {
            logger.warn(logMsg, cause);
        } else {
            logger.warn(logMsg);
        }

        if (response.isCommitted()) {
            // Headers already sent — cannot change the response
            logger.warn("Response already committed; cannot send error page for status " + statusCode);
            return;
        }

        // Build a simple HTML error page
        String reasonPhrase = reasonPhrase(statusCode);
        String displayMessage = (message != null && !message.isEmpty()) ? escapeHtml(message) : reasonPhrase;

        String body = "<html><head><title>" + statusCode + " " + escapeHtml(reasonPhrase) + "</title></head>" + "<body><h1>" + statusCode + " " + escapeHtml(reasonPhrase) + "</h1>" + "<p>" + displayMessage + "</p>" + "</body></html>";

        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        response.setStatus(statusCode);
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(bodyBytes.length);
        response.write(bodyBytes);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String reasonPhrase(int statusCode) {
        switch (statusCode) {
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 408:
                return "Request Timeout";
            case 409:
                return "Conflict";
            case 410:
                return "Gone";
            case 413:
                return "Content Too Large";
            case 414:
                return "URI Too Long";
            case 415:
                return "Unsupported Media Type";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 501:
                return "Not Implemented";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            case 504:
                return "Gateway Timeout";
            default:
                return "Error";
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
