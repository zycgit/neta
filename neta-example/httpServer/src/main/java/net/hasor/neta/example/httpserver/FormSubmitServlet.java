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
import java.util.List;
import java.util.Map;

import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * Example servlet demonstrating HTML form submission handling.
 * Accepts application/x-www-form-urlencoded POST requests and returns the
 * submitted form data as a JSON response.
 * <p>
 * Test with:
 * <pre>
 *   curl -X POST http://localhost:8080/api/form \
 *        -d "username=alice&amp;email=alice@example.com&amp;message=Hello+World"
 * </pre>
 * Or open {@code /pages/form.html} in a browser.
 * @author 赵永春 (zyc@hasor.net)
 */
public class FormSubmitServlet extends HttpServlet {

    @Override
    protected void doPost(ServletRequest request, ServletResponse response) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(200);

        Map<String, List<String>> params = request.getParameterMap();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": \"success\",\n");
        json.append("  \"message\": \"Form data received\",\n");
        json.append("  \"method\": \"").append(escapeJson(request.getMethod())).append("\",\n");
        json.append("  \"contentType\": \"").append(escapeJson(request.getContentType())).append("\",\n");
        json.append("  \"fields\": {\n");

        boolean first = true;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (!first) {
                json.append(",\n");
            }
            first = false;
            String key = entry.getKey();
            List<String> values = entry.getValue();
            json.append("    \"").append(escapeJson(key)).append("\": ");
            if (values.size() == 1) {
                json.append("\"").append(escapeJson(values.get(0))).append("\"");
            } else {
                json.append("[");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        json.append(", ");
                    }
                    json.append("\"").append(escapeJson(values.get(i))).append("\"");
                }
                json.append("]");
            }
        }

        json.append("\n  }\n");
        json.append("}");

        response.write(json.toString());
    }

    @Override
    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        // Return a simple HTML form for testing
        response.setContentType("text/html; charset=UTF-8");
        response.setStatus(200);

        String html = "<!DOCTYPE html>\n" + "<html><head><title>Form Submit Example</title></head>\n" + "<body>\n" + "<h1>Form Submit Example</h1>\n" + "<form method=\"POST\" action=\"/api/form\">\n" + "  <p><label>Username: <input type=\"text\" name=\"username\" /></label></p>\n" + "  <p><label>Email: <input type=\"email\" name=\"email\" /></label></p>\n" + "  <p><label>Message: <textarea name=\"message\" rows=\"4\" cols=\"40\"></textarea></label></p>\n" + "  <p><button type=\"submit\">Submit</button></p>\n" + "</form>\n" + "</body></html>";

        response.write(html);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
