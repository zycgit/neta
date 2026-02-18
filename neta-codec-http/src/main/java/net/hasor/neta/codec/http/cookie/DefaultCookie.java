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
package net.hasor.neta.codec.http.cookie;

/**
 * Default mutable implementation of {@link Cookie}.
 * <p>Use the builder-style setters to configure cookie attributes:
 * <pre>
 *   Cookie c = new DefaultCookie("session", "abc123")
 *       .setPath("/")
 *       .setDomain("example.com")
 *       .setMaxAge(3600)
 *       .setHttpOnly(true)
 *       .setSecure(true);
 * </pre>
 */
public class DefaultCookie implements Cookie {

    /** Sentinel value indicating {@code Max-Age} is not set. */
    public static final long UNDEFINED_MAX_AGE = Long.MIN_VALUE;

    private final String  name;
    private       String  value;
    private       String  domain;
    private       String  path;
    private       long    maxAge = UNDEFINED_MAX_AGE;
    private       String  expires;
    private       boolean secure;
    private       boolean httpOnly;
    private       String  sameSite;

    /**
     * Creates a new cookie with the given name and value.
     * @param name cookie name (must not be {@code null} or empty)
     * @param value cookie value (must not be {@code null})
     * @throws IllegalArgumentException if name is null or empty, or value is null
     */
    public DefaultCookie(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("cookie name must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("cookie value must not be null");
        }
        this.name = name;
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Cookie interface
    // -------------------------------------------------------------------------

    @Override
    public String name() {
        return name;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String domain() {
        return domain;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long maxAge() {
        return maxAge;
    }

    @Override
    public String expires() {
        return expires;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets the {@code Secure} flag and returns {@code this} for chaining.
     */
    public DefaultCookie setSecure(boolean secure) {
        this.secure = secure;
        return this;
    }

    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    // -------------------------------------------------------------------------
    // Setters (builder-style)
    // -------------------------------------------------------------------------

    /**
     * Sets the {@code HttpOnly} flag and returns {@code this} for chaining.
     */
    public DefaultCookie setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    @Override
    public String sameSite() {
        return sameSite;
    }

    /**
     * Sets the value of this cookie and returns {@code this} for chaining.
     */
    public DefaultCookie setValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("cookie value must not be null");
        }
        this.value = value;
        return this;
    }

    /**
     * Sets the {@code Domain} attribute and returns {@code this} for chaining.
     */
    public DefaultCookie setDomain(String domain) {
        this.domain = domain;
        return this;
    }

    /**
     * Sets the {@code Path} attribute and returns {@code this} for chaining.
     */
    public DefaultCookie setPath(String path) {
        this.path = path;
        return this;
    }

    /**
     * Sets the {@code Max-Age} attribute (in seconds) and returns {@code this} for chaining.
     * Pass {@link #UNDEFINED_MAX_AGE} to clear the attribute.
     */
    public DefaultCookie setMaxAge(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Sets the {@code Expires} attribute as an RFC 1123 date string and returns
     * {@code this} for chaining.
     */
    public DefaultCookie setExpires(String expires) {
        this.expires = expires;
        return this;
    }

    /**
     * Sets the {@code SameSite} attribute and returns {@code this} for chaining.
     * Common values: {@code "Strict"}, {@code "Lax"}, {@code "None"}.
     */
    public DefaultCookie setSameSite(String sameSite) {
        this.sameSite = sameSite;
        return this;
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value);
        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (maxAge != UNDEFINED_MAX_AGE) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (expires != null) {
            sb.append("; Expires=").append(expires);
        }
        if (secure) {
            sb.append("; Secure");
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (sameSite != null) {
            sb.append("; SameSite=").append(sameSite);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cookie)) {
            return false;
        }
        Cookie that = (Cookie) o;
        return name.equals(that.name()) && value.equals(that.value());
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + value.hashCode();
    }
}
