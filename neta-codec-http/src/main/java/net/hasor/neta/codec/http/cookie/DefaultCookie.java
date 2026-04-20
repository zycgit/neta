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
 * Default implementation of {@link Cookie}.
 * <p>This implementation stores {@link String} values directly and does not keep a lazily resolved
 * view inside the cookie model.
 * <p>Cookie attributes can be configured through chainable setters:
 * <pre>
 *   Cookie c = new DefaultCookie("session", "abc123")
 *       .setPath("/")
 *       .setDomain("example.com")
 *       .setMaxAge(3600)
 *       .setHttpOnly(true)
 *       .setSecure(true);
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultCookie implements Cookie {
    /** Sentinel value indicating that {@code Max-Age} is not set. */
    public static final long UNDEFINED_MAX_AGE = Long.MIN_VALUE;
    private final String     name;
    private String           value;
    private String           domain;
    private String           path;
    private long             maxAge            = UNDEFINED_MAX_AGE;
    private String           expires;
    private boolean          secure;
    private boolean          httpOnly;
    private String           sameSite;

    /**
     * Creates a new cookie with the given name and value.
     * @param name the cookie name, which must not be {@code null} or empty
     * @param value the cookie value, which must not be {@code null}
     * @throws IllegalArgumentException if the name is {@code null}, empty, or the value is {@code null}
     */
    public DefaultCookie(CharSequence name, CharSequence value) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("cookie name must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("cookie value must not be null");
        }
        this.name = name.toString();
        this.value = value.toString();
    }

    // -------------------------------------------------------------------------
    // Cookie interface implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the cookie name.
     */
    @Override
    public String name() {
        return this.name;
    }

    /**
     * Returns the cookie value.
     */
    @Override
    public String value() {
        return this.value;
    }

    /**
     * Returns the cookie Domain attribute.
     */
    @Override
    public String domain() {
        return this.domain;
    }

    /**
     * Returns the cookie Path attribute.
     */
    @Override
    public String path() {
        return this.path;
    }

    /**
     * Returns the currently stored {@code Max-Age} value in seconds.
     */
    @Override
    public long maxAge() {
        return maxAge;
    }

    /**
     * Returns the cookie Expires attribute.
     */
    @Override
    public String expires() {
        return this.expires;
    }

    /**
     * This implementation always stores strings directly, so it is always in a resolved state.
     */
    @Override
    public boolean isResolved() {
        return true;
    }

    /**
     * This implementation always stores strings directly, so this method returns itself.
     */
    @Override
    public Cookie resolve() {
        return this;
    }

    /**
     * This implementation does not hold additional lazy-view resources, so this method is a no-op.
     */
    @Override
    public void release() {
    }

    // -------------------------------------------------------------------------
    // Setters (chainable style)
    // -------------------------------------------------------------------------

    /**
     * Returns whether the Secure flag is set.
     */
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

    /**
     * Returns whether the HttpOnly flag is set.
     */
    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    /**
     * Sets the {@code HttpOnly} flag and returns {@code this} for chaining.
     */
    public DefaultCookie setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    /**
     * Returns the cookie SameSite attribute.
     */
    @Override
    public String sameSite() {
        return this.sameSite;
    }

    /**
     * Sets the current cookie value and returns {@code this} for chaining.
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
     * Sets the {@code Max-Age} attribute in seconds and returns {@code this} for chaining.
     * Passing {@link #UNDEFINED_MAX_AGE} clears the attribute.
     */
    public DefaultCookie setMaxAge(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Sets the {@code Expires} attribute as an RFC 1123 date string and returns {@code this} for chaining.
     */
    public DefaultCookie setExpires(String expires) {
        this.expires = expires;
        return this;
    }

    /**
     * Sets the {@code SameSite} attribute and returns {@code this} for chaining.
     * Common values include {@code "Strict"}, {@code "Lax"}, and {@code "None"}.
     */
    public DefaultCookie setSameSite(String sameSite) {
        this.sameSite = sameSite;
        return this;
    }

    /**
     * Returns the string representation of this cookie in {@code Set-Cookie} header form.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.name()).append('=').append(this.value());
        if (this.domain() != null) {
            sb.append("; Domain=").append(this.domain());
        }
        if (this.path() != null) {
            sb.append("; Path=").append(this.path());
        }
        if (maxAge != UNDEFINED_MAX_AGE) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (this.expires() != null) {
            sb.append("; Expires=").append(this.expires());
        }
        if (secure) {
            sb.append("; Secure");
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (this.sameSite() != null) {
            sb.append("; SameSite=").append(this.sameSite());
        }
        return sb.toString();
    }

    /**
     * Returns whether this object is equivalent to another cookie.
     * The current implementation compares only {@link #name()} and {@link #value()}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cookie)) {
            return false;
        }
        Cookie that = (Cookie) o;
        return this.name().equals(that.name()) && this.value().equals(that.value());
    }

    /**
     * Returns the hash code computed from {@link #name()} and {@link #value()}.
     */
    @Override
    public int hashCode() {
        return 31 * this.name().hashCode() + this.value().hashCode();
    }
}
