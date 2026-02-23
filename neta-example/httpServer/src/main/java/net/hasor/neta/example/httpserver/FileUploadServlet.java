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
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hasor.neta.codec.http.multipart.FileUpload;
import net.hasor.neta.http.HttpServlet;
import net.hasor.neta.http.ServletRequest;
import net.hasor.neta.http.ServletResponse;

/**
 * Example servlet demonstrating multipart/form-data file upload handling.
 * Parses the uploaded files and returns metadata as a JSON response.
 * <p>
 * Test with:
 * <pre>
 *   curl -X POST http://localhost:8080/api/upload \
 *        -F "description=My files" \
 *        -F "file1=@/path/to/readme.txt" \
 *        -F "file2=@/path/to/photo.png"
 * </pre>
 * Or open {@code /pages/upload.html} in a browser.
 * @author 赵永春 (zyc@hasor.net)
 */
public class FileUploadServlet extends HttpServlet {

    @Override
    protected void doPost(ServletRequest request, ServletResponse response) throws IOException {
        response.setContentType("application/json; charset=UTF-8");

        if (!request.isMultipart()) {
            response.setStatus(400);
            response.write("{\"status\": \"error\", \"message\": \"Expected multipart/form-data request\"}");
            return;
        }

        List<FileUpload> parts = request.getFileUploads();
        response.setStatus(200);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": \"success\",\n");
        json.append("  \"message\": \"Upload received\",\n");
        json.append("  \"totalParts\": ").append(parts.size()).append(",\n");

        // Collect form fields
        json.append("  \"fields\": {\n");
        boolean firstField = true;
        for (FileUpload part : parts) {
            if (part.filename() != null) {
                continue; // skip file parts
            }
            if (!firstField) {
                json.append(",\n");
            }
            firstField = false;
            String value = part.content().getString(part.content().readerIndex(), part.content().readableBytes(), StandardCharsets.UTF_8);
            json.append("    \"").append(escapeJson(part.name())).append("\": \"").append(escapeJson(value)).append("\"");
        }
        json.append("\n  },\n");

        // Collect file metadata
        json.append("  \"files\": [\n");
        boolean firstFile = true;
        for (FileUpload part : parts) {
            if (part.filename() == null) {
                continue; // skip non-file parts
            }
            if (!firstFile) {
                json.append(",\n");
            }
            firstFile = false;
            json.append("    {\n");
            json.append("      \"fieldName\": \"").append(escapeJson(part.name())).append("\",\n");
            json.append("      \"fileName\": \"").append(escapeJson(part.filename())).append("\",\n");
            json.append("      \"contentType\": \"").append(escapeJson(part.contentType())).append("\",\n");
            json.append("      \"size\": ").append(part.content().readableBytes()).append("\n");
            json.append("    }");
        }
        json.append("\n  ]\n");
        json.append("}");

        response.write(json.toString());
    }

    @Override
    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        // Return a simple HTML form for testing
        response.setContentType("text/html; charset=UTF-8");
        response.setStatus(200);

        String html = "<!DOCTYPE html>\n" + "<html><head><title>File Upload Example</title></head>\n" + "<body>\n" + "<h1>File Upload Example</h1>\n" + "<form method=\"POST\" action=\"/api/upload\" enctype=\"multipart/form-data\">\n" + "  <p><label>Description: <input type=\"text\" name=\"description\" /></label></p>\n" + "  <p><label>File 1: <input type=\"file\" name=\"file1\" /></label></p>\n" + "  <p><label>File 2: <input type=\"file\" name=\"file2\" /></label></p>\n" + "  <p><button type=\"submit\">Upload</button></p>\n" + "</form>\n" + "</body></html>";

        response.write(html);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
