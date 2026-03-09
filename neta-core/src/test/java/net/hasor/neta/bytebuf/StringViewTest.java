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
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class StringViewTest {
    private static ByteBuf toBuf(String s) {
        return ByteBuf.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testStringViewImplementsCharSequence() {
        ByteBuf buf = toBuf("session");
        try {
            StringView value = new StringView(buf, 0, 7);
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
        StringView view = new StringView(buf, 0, 7);

        buf.free();

        assertFalse(view.isResolved());
        assertEquals("session", view.resolve());
        assertTrue(view.isResolved());
        assertEquals('e', view.charAt(1));
    }
}