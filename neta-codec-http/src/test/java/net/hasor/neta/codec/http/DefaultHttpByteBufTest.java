/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;

import static org.junit.Assert.*;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;

public class DefaultHttpByteBufTest {
    @Test
    public void pooledWrapperResetsStateAndDoesNotDoubleReleaseTransferredContent() {
        ByteBuf firstContent = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        DefaultHttpByteBuf first = DefaultHttpByteBuf.newInstance(firstContent, 7);
        first.markBad("old");

        ByteBuf transferred = first.transferContent();
        first.release();
        first.release();
        assertFalse(transferred.isFree());
        transferred.release();

        ByteBuf secondContent = ByteBuf.wrap(new byte[] { 4, 5 });
        DefaultHttpByteBuf second = DefaultHttpByteBuf.newInstance(secondContent, 11);
        assertSame(first, second);
        assertEquals(11, second.streamId());
        assertFalse(second.isBad());
        assertSame(secondContent, second.content());

        second.release();
        assertTrue(secondContent.isFree());
    }
}
