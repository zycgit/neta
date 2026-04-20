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
package net.hasor.neta.bytebuf;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.hasor.cobble.function.Release;

public class StringViewTest {
    private static ByteBuf toBuf(String s) {
        return ByteBuf.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testStringViewImplementsCharSequence() {
        ByteBuf buf = toBuf("session");
        try {
            StringView value = StringView.request(buf, 0, 7);
            assertFalse(value.isResolved());
            assertEquals(7, value.length());
            assertEquals('s', value.charAt(0));
            assertEquals('n', value.charAt(6));
            assertEquals("ess", value.subSequence(1, 4).toString());
            assertEquals("session", value.toString());
            assertTrue(value.isResolved());
        } finally {
            buf.free();
        }
    }

    @Test
    public void testStringViewKeepsReadableAfterSourceRelease() {
        ByteBuf buf = toBuf("session");
        StringView view = StringView.request(buf, 0, 7);

        buf.free();

        assertFalse(view.isResolved());
        assertEquals("session", view.resolve());
        assertTrue(view.isResolved());
        assertEquals('e', view.charAt(1));
    }

    @Test
    public void testResolveReturnsEmptyWhenUnderlyingBufferWasOverReleased() {
        ByteBuf buf = toBuf("session");
        StringView view = StringView.request(buf, 0, 7);

        buf.free();
        buf.free();

        assertEquals("", view.resolve());
        assertTrue(view.isResolved());
    }

    @Test
    public void testStringViewCanBeReusedFromPool() {
        ByteBuf first = toBuf("first");
        ByteBuf second = toBuf("second");
        try {
            StringView firstView = StringView.request(first, 0, 5);
            assertEquals("first", firstView.resolve());
            firstView.release();

            StringView secondView = StringView.request(second, 0, 6);
            assertSame(firstView, secondView);
            assertEquals("second", secondView.resolve());
            secondView.release();
        } finally {
            first.free();
            second.free();
        }
    }

    @Test
    public void testStringViewCanBeReleasedViaReleaseInterface() {
        ByteBuf first = toBuf("first");
        ByteBuf second = toBuf("second");
        try {
            StringView firstView = StringView.request(first, 0, 5);
            Release release = firstView;
            release.release();

            StringView secondView = StringView.request(second, 0, 6);
            assertSame(firstView, secondView);
            assertEquals("second", secondView.resolve());
            secondView.release();
        } finally {
            first.free();
            second.free();
        }
    }

    @Test
    public void testReleaseReturnsRetainedReferenceToSource() {
        ByteBuf buf = toBuf("session");
        try {
            assertEquals(1, buf.refCnt());

            StringView view = StringView.request(buf, 0, 7);
            assertEquals(2, buf.refCnt());

            view.release();
            assertEquals(1, buf.refCnt());
        } finally {
            buf.free();
        }
    }

    @Test
    public void testResolveReturnsRetainedReferenceToSource() {
        ByteBuf buf = toBuf("session");
        try {
            assertEquals(1, buf.refCnt());

            StringView view = StringView.request(buf, 0, 7);
            assertEquals(2, buf.refCnt());

            assertEquals("session", view.resolve());
            assertEquals(1, buf.refCnt());
            assertTrue(view.isResolved());
        } finally {
            buf.free();
        }
    }
}