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

import org.junit.Test;

import net.hasor.nhttp.server.HttpSession;

/**
 * Tests for {@link DefaultSessionManager}.
 */
public class DefaultSessionManagerTest {

    @Test
    public void testCreateSession() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession session = manager.createSession();

        assertNotNull(session);
        assertNotNull(session.getId());
        assertFalse(session.getId().isEmpty());
        assertTrue(session.isValid());
        assertTrue(session.isNew());
    }

    @Test
    public void testGetSessionById() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession session = manager.createSession();
        String id = session.getId();

        HttpSession retrieved = manager.getSession(id);
        assertNotNull(retrieved);
        assertEquals(id, retrieved.getId());
        assertFalse(retrieved.isNew()); // getSession calls touch()
    }

    @Test
    public void testGetNonExistentSession() {
        DefaultSessionManager manager = new DefaultSessionManager();
        assertNull(manager.getSession("nonexistent"));
        assertNull(manager.getSession(null));
    }

    @Test
    public void testInvalidateSession() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession session = manager.createSession();
        String id = session.getId();

        manager.invalidateSession(id);
        assertFalse(session.isValid());
        assertNull(manager.getSession(id));
    }

    @Test
    public void testInvalidateNonExistent() {
        DefaultSessionManager manager = new DefaultSessionManager();
        // should not throw
        manager.invalidateSession("nonexistent");
    }

    @Test
    public void testDefaultMaxInactiveInterval() {
        DefaultSessionManager manager = new DefaultSessionManager();
        assertEquals(1800, manager.getDefaultMaxInactiveInterval());

        manager.setDefaultMaxInactiveInterval(600);
        assertEquals(600, manager.getDefaultMaxInactiveInterval());

        // newly created sessions should use the new default
        HttpSession session = manager.createSession();
        assertEquals(600, session.getMaxInactiveInterval());
    }

    @Test
    public void testMultipleSessions() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession s1 = manager.createSession();
        HttpSession s2 = manager.createSession();

        assertNotEquals(s1.getId(), s2.getId());
        assertNotNull(manager.getSession(s1.getId()));
        assertNotNull(manager.getSession(s2.getId()));
    }

    @Test
    public void testCleanExpiredSessions() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession s1 = manager.createSession();
        HttpSession s2 = manager.createSession();

        // invalidate one
        s1.invalidate();

        manager.cleanExpiredSessions();

        // s1 should be cleaned
        assertNull(manager.getSession(s1.getId()));
        // s2 should still exist
        assertNotNull(manager.getSession(s2.getId()));
    }

    @Test
    public void testSessionAttributesPersistAcrossRetrieval() {
        DefaultSessionManager manager = new DefaultSessionManager();
        HttpSession session = manager.createSession();
        session.setAttribute("user", "admin");

        HttpSession retrieved = manager.getSession(session.getId());
        assertEquals("admin", retrieved.getAttribute("user"));
    }
}
