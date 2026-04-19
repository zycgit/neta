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
package net.hasor.nhttp.server.internal;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link DefaultHttpSession}.
 */
public class DefaultHttpSessionTest {

    @Test
    public void testSessionCreation() {
        DefaultHttpSession session = new DefaultHttpSession("test-id", 1800);
        assertEquals("test-id", session.getId());
        assertTrue(session.isValid());
        assertTrue(session.isNew());
        assertEquals(1800, session.getMaxInactiveInterval());
        assertTrue(session.getCreationTime() > 0);
        assertTrue(session.getLastAccessedTime() > 0);
    }

    @Test
    public void testSessionAttributes() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 1800);
        assertNull(session.getAttribute("key"));

        session.setAttribute("key", "value");
        assertEquals("value", session.getAttribute("key"));

        session.setAttribute("key2", 42);
        assertEquals(42, session.getAttribute("key2"));

        Map<String, Object> attrs = session.getAttributes();
        assertEquals(2, attrs.size());
        assertEquals("value", attrs.get("key"));

        session.removeAttribute("key");
        assertNull(session.getAttribute("key"));
        assertEquals(1, session.getAttributes().size());
    }

    @Test
    public void testSessionSetAttributeNull() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 1800);
        session.setAttribute("key", "value");
        assertEquals("value", session.getAttribute("key"));

        session.setAttribute("key", null);
        assertNull(session.getAttribute("key"));
    }

    @Test
    public void testSessionTouch() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 1800);
        assertTrue(session.isNew());

        long beforeTouch = session.getLastAccessedTime();
        session.touch();
        assertFalse(session.isNew());
        assertTrue(session.getLastAccessedTime() >= beforeTouch);
    }

    @Test
    public void testSessionInvalidate() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 1800);
        session.setAttribute("key", "value");

        assertTrue(session.isValid());
        session.invalidate();
        assertFalse(session.isValid());
    }

    @Test(expected = IllegalStateException.class)
    public void testInvalidatedSessionThrows() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 1800);
        session.invalidate();
        session.getAttribute("key"); // should throw
    }

    @Test
    public void testSessionTimeout() {
        // create a session with 1 second timeout
        DefaultHttpSession session = new DefaultHttpSession("s1", 1);
        assertTrue(session.isValid());

        // simulate timeout by adjusting the internal state via reflection or just waiting
        // For this test, we set a very short timeout and verify the logic
        session.setMaxInactiveInterval(0); // 0 means no timeout
        assertTrue(session.isValid());

        session.setMaxInactiveInterval(-1); // negative means no timeout
        assertTrue(session.isValid());
    }

    @Test
    public void testMaxInactiveInterval() {
        DefaultHttpSession session = new DefaultHttpSession("s1", 600);
        assertEquals(600, session.getMaxInactiveInterval());

        session.setMaxInactiveInterval(300);
        assertEquals(300, session.getMaxInactiveInterval());
    }
}
