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
package net.hasor.neta.http;
import java.io.IOException;

/**
 * HTTP Servlet interface, similar to javax.servlet.http.HttpServlet.
 * Implementations handle HTTP requests and produce responses.
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class HttpServlet {

    /** Called when the servlet is initialized. Override to perform one-time setup. */
    public void init(ServletContext context) throws Exception {
    }

    /** Called when the servlet is being removed. Override to perform cleanup. */
    public void destroy() {
    }

    /**
     * Dispatches the request to the appropriate doXxx method based on HTTP method.
     */
    public void service(ServletRequest request, ServletResponse response) throws IOException {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)) {
            doGet(request, response);
        } else if ("POST".equalsIgnoreCase(method)) {
            doPost(request, response);
        } else if ("PUT".equalsIgnoreCase(method)) {
            doPut(request, response);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            doDelete(request, response);
        } else if ("HEAD".equalsIgnoreCase(method)) {
            doHead(request, response);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            doOptions(request, response);
        } else if ("PATCH".equalsIgnoreCase(method)) {
            doPatch(request, response);
        } else {
            response.sendError(405, "Method Not Allowed: " + method);
        }
    }

    protected void doGet(ServletRequest request, ServletResponse response) throws IOException {
        response.sendError(405, "GET not supported");
    }

    protected void doPost(ServletRequest request, ServletResponse response) throws IOException {
        response.sendError(405, "POST not supported");
    }

    protected void doPut(ServletRequest request, ServletResponse response) throws IOException {
        response.sendError(405, "PUT not supported");
    }

    protected void doDelete(ServletRequest request, ServletResponse response) throws IOException {
        response.sendError(405, "DELETE not supported");
    }

    protected void doHead(ServletRequest request, ServletResponse response) throws IOException {
        doGet(request, response);
    }

    protected void doOptions(ServletRequest request, ServletResponse response) throws IOException {
        response.setHeader("Allow", "GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH");
        response.setStatus(200);
    }

    protected void doPatch(ServletRequest request, ServletResponse response) throws IOException {
        response.sendError(405, "PATCH not supported");
    }
}
