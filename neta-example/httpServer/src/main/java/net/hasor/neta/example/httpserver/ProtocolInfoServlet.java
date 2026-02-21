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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.hasor.neta.http.HttpServlet;
import net.hasor.neta.http.ServletRequest;
import net.hasor.neta.http.ServletResponse;

/**
 * API servlet that returns server protocol information as JSON.
 * Useful for verifying which protocol the browser is using.
 * @author 赵永春 (zyc@hasor.net)
 */
public class ProtocolInfoServlet extends HttpServlet {

    @Override
    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(200);

        String protocol = request.getProtocol();
        String scheme = request.getScheme();
        String method = request.getMethod();
        String host = request.getHost();
        String uri = request.getRequestURI();
        String remoteAddr = String.valueOf(request.getRemoteAddress());
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String json = "{\n"                                                                     //
                + "  \"protocol\": \"" + escapeJson(protocol) + "\",\n"         //
                + "  \"scheme\": \"" + escapeJson(scheme) + "\",\n"             //
                + "  \"method\": \"" + escapeJson(method) + "\",\n"             //
                + "  \"host\": \"" + escapeJson(host) + "\",\n"                 //
                + "  \"uri\": \"" + escapeJson(uri) + "\",\n"                   //
                + "  \"secure\": " + request.isSecure() + ",\n"                 //
                + "  \"remoteAddress\": \"" + escapeJson(remoteAddr) + "\",\n"  //
                + "  \"serverTime\": \"" + timestamp + "\",\n"                  //
                + "  \"serverConfig\": {\n"                                                 //
                + "    \"http2Enabled\": true,\n"                                           //
                + "    \"http3Enabled\": true\n"                                            //
                + "  },\n"                                                                  //
                + "  \"serverProtocols\": {\n"                                              //
                + "    \"http1.1\": { \"port\": 8080, \"transport\": \"TCP\" },\n"          //
                + "    \"h2c\": { \"port\": 8080, \"transport\": \"TCP (Prior Knowledge)\" },\n" //
                + "    \"h2\": { \"port\": 8443, \"transport\": \"TCP+TLS+ALPN\" },\n"      //
                + "    \"h3\": { \"port\": 8443, \"transport\": \"UDP+QUIC\" }\n"           //
                + "  }\n"                                                                   //
                + "}";                                                                       //

        response.write(json);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\\", "\\\\")//
                .replace("\"", "\\\"")    //
                .replace("\n", "\\n")     //
                .replace("\r", "\\r");
    }
}
