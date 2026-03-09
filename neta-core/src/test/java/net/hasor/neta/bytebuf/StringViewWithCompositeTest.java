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

public class StringViewWithCompositeTest {
    private static ByteBuf toBuf(String value) {
        return ByteBuf.wrap(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testCompositeViewReadsAcrossComponentBoundaries() {
        ByteBuf part1 = toBuf("ses");
        ByteBuf part2 = toBuf("sion");
        try {
            StringViewWithComposite view = new StringViewWithComposite(new ByteBuf[] { part1, part2 }, 0, 7);
            assertFalse(view.isResolved());
            assertEquals(7, view.length());
            assertEquals('s', view.charAt(0));
            assertEquals('s', view.charAt(3));
            assertEquals('n', view.charAt(6));
            assertEquals("sion", view.subSequence(3, 7).toString());
            assertEquals("session", view.resolve());
            assertTrue(view.isResolved());
        } finally {
            part1.free();
            part2.free();
        }
    }

    @Test
    public void testCompositeViewRemainsReadableAfterOriginalBuffersReleased() {
        ByteBuf part1 = toBuf("he");
        ByteBuf part2 = toBuf("ader");
        StringViewWithComposite view = new StringViewWithComposite(new ByteBuf[] { part1, part2 }, 1, 4);

        part1.free();
        part2.free();

        assertFalse(view.isResolved());
        assertEquals("eade", view.resolve());
        assertTrue(view.isResolved());
        assertEquals('a', view.charAt(1));
        assertEquals("ad", view.subSequence(1, 3).toString());
    }
}