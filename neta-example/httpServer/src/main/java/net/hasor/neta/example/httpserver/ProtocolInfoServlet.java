/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.example.httpserver;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.hasor.nhttp.server.HttpServlet;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * API servlet that returns server protocol information as JSON.
 * Useful for verifying which protocol the browser is using.
 * @author 赵永春 (zyc@hasor.net)
 */
public class ProtocolInfoServlet extends HttpServlet {
    private final int httpPort;
    private final int httpsPort;
    private final boolean http2Enabled;
    private final boolean http3Enabled;

    public ProtocolInfoServlet(int httpPort, int httpsPort, boolean http2Enabled, boolean http3Enabled) {
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.http2Enabled = http2Enabled;
        this.http3Enabled = http3Enabled;
    }

    @Override
    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(200);

        // Respect reverse proxy forwarded headers (Caddy automatically sets these)
        String fwdProto = request.getHeader("X-Forwarded-Proto");
        String fwdHost = request.getHeader("X-Forwarded-Host");
        String fwdFor = request.getHeader("X-Forwarded-For");

        String protocol = request.getProtocol();
        String scheme = (fwdProto != null && !fwdProto.isEmpty()) ? fwdProto : request.getScheme();
        String method = request.getMethod();
        String host = (fwdHost != null && !fwdHost.isEmpty()) ? fwdHost : request.getHost();
        String uri = request.getRequestURI();
        String remoteAddr = (fwdFor != null && !fwdFor.isEmpty()) ? fwdFor : String.valueOf(request.getRemoteAddress());
        boolean secure = "https".equalsIgnoreCase(scheme);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String json = "{\n"
            + "  \"protocol\": \"" + escapeJson(protocol) + "\",\n"
            + "  \"scheme\": \"" + escapeJson(scheme) + "\",\n"
            + "  \"method\": \"" + escapeJson(method) + "\",\n"
            + "  \"host\": \"" + escapeJson(host) + "\",\n"
            + "  \"uri\": \"" + escapeJson(uri) + "\",\n"
            + "  \"secure\": " + secure + ",\n"
            + "  \"remoteAddress\": \"" + escapeJson(remoteAddr) + "\",\n"
            + "  \"serverTime\": \"" + timestamp + "\",\n"
            + "  \"serverConfig\": {\n"
            + "    \"http2Enabled\": " + this.http2Enabled + ",\n"
            + "    \"http3Enabled\": " + this.http3Enabled + "\n"
            + "  },\n"
            + "  \"serverProtocols\": {\n"
            + "    \"http1.1\": { \"port\": " + this.httpPort + ", \"transport\": \"TCP\" },\n"
            + "    \"h2c\": { \"port\": " + this.httpPort + ", \"transport\": \"TCP (Prior Knowledge)\" },\n"
            + "    \"h2\": { \"port\": " + this.httpsPort + ", \"transport\": \"TCP+TLS+ALPN\" },\n"
            + "    \"h3\": { \"enabled\": false, \"transport\": \"Temporarily unavailable\" }\n"
            + "  }\n"
            + "}";

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
