/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.example.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;

/**
 * A simple static file servlet that serves resources from the classpath.
 * Supports common MIME types for HTML, CSS, JS, images, fonts, etc.
 * @author 赵永春 (zyc@hasor.net)
 */
public class StaticFileServlet extends HttpServlet {
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        // Text
        MIME_TYPES.put(".html", "text/html; charset=UTF-8");
        MIME_TYPES.put(".htm", "text/html; charset=UTF-8");
        MIME_TYPES.put(".css", "text/css; charset=UTF-8");
        MIME_TYPES.put(".js", "application/javascript; charset=UTF-8");
        MIME_TYPES.put(".json", "application/json; charset=UTF-8");
        MIME_TYPES.put(".xml", "application/xml; charset=UTF-8");
        MIME_TYPES.put(".txt", "text/plain; charset=UTF-8");
        MIME_TYPES.put(".csv", "text/csv; charset=UTF-8");
        MIME_TYPES.put(".md", "text/markdown; charset=UTF-8");
        // Images
        MIME_TYPES.put(".png", "image/png");
        MIME_TYPES.put(".jpg", "image/jpeg");
        MIME_TYPES.put(".jpeg", "image/jpeg");
        MIME_TYPES.put(".gif", "image/gif");
        MIME_TYPES.put(".svg", "image/svg+xml");
        MIME_TYPES.put(".ico", "image/x-icon");
        MIME_TYPES.put(".webp", "image/webp");
        // Fonts
        MIME_TYPES.put(".woff", "font/woff");
        MIME_TYPES.put(".woff2", "font/woff2");
        MIME_TYPES.put(".ttf", "font/ttf");
        MIME_TYPES.put(".eot", "application/vnd.ms-fontobject");
        // Other
        MIME_TYPES.put(".pdf", "application/pdf");
        MIME_TYPES.put(".zip", "application/zip");
        MIME_TYPES.put(".wasm", "application/wasm");
    }

    private final String basePath;

    /**
     * Creates a StaticFileServlet with the given classpath base path.
     * @param basePath the classpath prefix (e.g., "static" to serve from "static/")
     */
    public StaticFileServlet(String basePath) {
        this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
    }

    @Override
    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        String path = request.getRequestPath();

        // Default to index.html for root path
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }

        // Security check: prevent path traversal
        if (path.contains("..") || path.contains("//")) {
            response.sendError(403, "Forbidden");
            return;
        }

        // Remove leading slash for classpath lookup
        String resourcePath = this.basePath + (path.startsWith("/") ? path.substring(1) : path);

        // Try to load the resource from classpath
        InputStream resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            // Try with index.html for directory-like paths
            if (!path.contains(".")) {
                String dirPath = path.endsWith("/") ? path : path + "/";
                resourcePath = this.basePath + (dirPath.startsWith("/") ? dirPath.substring(1) : dirPath) + "index.html";
                resourceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            }
        }

        if (resourceStream == null) {
            response.sendError(404, "Not Found: " + path);
            return;
        }

        try (InputStream stream = resourceStream) {
            // Determine content type
            String contentType = getContentType(path);
            response.setContentType(contentType);
            response.setStatus(200);

            // Add cache control for static assets
            if (!path.endsWith(".html") && !path.endsWith(".htm")) {
                response.setHeader(HttpHeaderNames.CACHE_CONTROL, "public, max-age=3600");
            } else {
                response.setHeader(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
            }

            // Read and write the resource
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                response.write(buffer, 0, bytesRead);
            }
        }
    }

    /** Determines the content type based on file extension */
    private String getContentType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = path.substring(dotIndex).toLowerCase();
            String mime = MIME_TYPES.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        return HttpHeaderValues.APPLICATION_OCTET_STREAM;
    }
}
