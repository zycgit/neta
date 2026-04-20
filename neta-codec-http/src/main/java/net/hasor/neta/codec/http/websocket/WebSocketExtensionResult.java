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
package net.hasor.neta.codec.http.websocket;
import java.util.*;
import net.hasor.cobble.StringUtils;
/**
 * Structured representation of one websocket extension negotiation entry.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public final class WebSocketExtensionResult {
    private final String              name;
    private final Map<String, String> parameters;

    /**
     * Create a negotiation result with only an extension name.
     * @param name extension name
     */
    public WebSocketExtensionResult(String name) {
        this(name, Collections.emptyMap());
    }

    /**
     * Create a negotiation result with extension parameters.
     * @param name extension name
     * @param parameters extension parameters
     */
    public WebSocketExtensionResult(String name, Map<String, String> parameters) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("extension name is blank");
        }

        this.name = name.trim();
        if (parameters == null || parameters.isEmpty()) {
            this.parameters = Collections.emptyMap();
        } else {
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }

    /**
     * Return the negotiated extension name.
     * @return extension name
     */
    public String name() {
        return this.name;
    }

    /**
     * Return the negotiated extension parameters.
     * @return extension parameters
     */
    public Map<String, String> parameters() {
        return this.parameters;
    }

    /**
     * Determine whether a parameter is present.
     * @param name parameter name
     * @return {@code true} when the parameter exists
     */
    public boolean hasParameter(String name) {
        return this.parameter(name) != null || this.parameters.containsKey(name);
    }

    /**
     * Resolve one parameter value case-insensitively.
     * @param name parameter name
     * @return parameter value, or {@code null} when absent
     */
    public String parameter(String name) {
        if (StringUtils.isBlank(name) || this.parameters.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, String> entry : this.parameters.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getKey(), name)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Render this extension result back into an HTTP header value.
     * @return extension header string
     */
    public String asHeaderValue() {
        if (this.parameters.isEmpty()) {
            return this.name;
        }

        StringBuilder builder = new StringBuilder(this.name);
        for (Map.Entry<String, String> entry : this.parameters.entrySet()) {
            builder.append("; ").append(entry.getKey());
            if (StringUtils.isNotBlank(entry.getValue())) {
                builder.append('=').append(entry.getValue());
            }
        }

        return builder.toString();
    }

    /**
     * Parse an extension header value into structured results.
     * @param headerValue extension header value
     * @return parsed extension results
     */
    public static List<WebSocketExtensionResult> parse(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return Collections.emptyList();
        }

        String[] entries = headerValue.split(",");
        List<WebSocketExtensionResult> results = new ArrayList<>(entries.length);
        for (String entry : entries) {
            WebSocketExtensionResult result = parseEntry(entry);
            if (result != null) {
                results.add(result);
            }
        }

        if (results.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(results);
    }

    private static WebSocketExtensionResult parseEntry(String entry) {
        if (StringUtils.isBlank(entry)) {
            return null;
        }

        String[] parts = entry.split(";");
        String name = parts[0] != null ? parts[0].trim() : null;
        if (StringUtils.isBlank(name)) {
            return null;
        }

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String token = parts[i] != null ? parts[i].trim() : null;
            if (StringUtils.isBlank(token)) {
                continue;
            }

            int eqIndex = token.indexOf('=');
            if (eqIndex < 0) {
                parameters.put(token, null);
                continue;
            }

            String key = token.substring(0, eqIndex).trim();
            if (StringUtils.isBlank(key)) {
                continue;
            }

            String value = token.substring(eqIndex + 1).trim();
            parameters.put(key, StringUtils.isBlank(value) ? null : value);
        }

        return new WebSocketExtensionResult(name, parameters);
    }

    /**
     * Compare this extension result with another object.
     * @param obj candidate object
     * @return {@code true} when both results carry the same name and parameters
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof WebSocketExtensionResult)) {
            return false;
        }

        WebSocketExtensionResult that = (WebSocketExtensionResult) obj;
        return Objects.equals(this.name, that.name) && Objects.equals(this.parameters, that.parameters);
    }

    /**
     * Return the hash code for this extension result.
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.name, this.parameters);
    }

    /**
     * Return the HTTP header representation of this extension result.
     * @return extension header string
     */
    @Override
    public String toString() {
        return this.asHeaderValue();
    }
}