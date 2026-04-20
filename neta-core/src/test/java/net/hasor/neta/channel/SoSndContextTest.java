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
package net.hasor.neta.channel;

import org.junit.Test;

import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Tests for {@link SoSndContext} queue operations.
 * @author test
 */
public class SoSndContextTest {

    private SoSndData createData(byte... bytes) {
        ByteBuf[] bufs = new ByteBuf[] { ByteBuf.wrap(bytes) };
        return new SoSndData(bytes.length, bufs, new BasicFuture<>(), null);
    }

    @Test
    public void initiallyEmpty() {
        SoSndContext ctx = new SoSndContext();
        assert ctx.isEmpty();
        assert ctx.popData() == null;
        assert ctx.peekData() == null;
    }

    @Test
    public void offerAndPop() {
        SoSndContext ctx = new SoSndContext();
        SoSndData d1 = createData((byte) 1, (byte) 2);
        SoSndData d2 = createData((byte) 3);

        ctx.offer(d1);
        ctx.offer(d2);
        assert !ctx.isEmpty();

        SoSndData popped1 = ctx.popData();
        assert popped1 == d1;

        SoSndData popped2 = ctx.popData();
        assert popped2 == d2;

        assert ctx.isEmpty();
        assert ctx.popData() == null;
    }

    @Test
    public void peekDoesNotRemove() {
        SoSndContext ctx = new SoSndContext();
        SoSndData d = createData((byte) 1);
        ctx.offer(d);

        SoSndData peeked = ctx.peekData();
        assert peeked == d;
        assert !ctx.isEmpty();

        peeked = ctx.peekData();
        assert peeked == d;
    }

    @Test
    public void purge_failsAllPending() {
        SoSndContext ctx = new SoSndContext();
        BasicFuture<NetChannel> f1 = new BasicFuture<>();
        BasicFuture<NetChannel> f2 = new BasicFuture<>();

        ctx.offer(new SoSndData(1, new ByteBuf[] { ByteBuf.wrap(new byte[] { 1 }) }, f1, null));
        ctx.offer(new SoSndData(1, new ByteBuf[] { ByteBuf.wrap(new byte[] { 2 }) }, f2, null));

        RuntimeException err = new RuntimeException("test purge");
        ctx.purge(err);

        assert ctx.isEmpty();
        assert ctx.popData() == null;
    }

    @Test
    public void purge_alreadyEmpty() {
        SoSndContext ctx = new SoSndContext();
        ctx.purge(new RuntimeException("nothing to purge"));
        assert ctx.isEmpty();
    }

    @Test
    public void fifoOrder() {
        SoSndContext ctx = new SoSndContext();
        SoSndData d1 = createData((byte) 1);
        SoSndData d2 = createData((byte) 2);
        SoSndData d3 = createData((byte) 3);

        ctx.offer(d1);
        ctx.offer(d2);
        ctx.offer(d3);

        assert ctx.popData() == d1;
        assert ctx.popData() == d2;
        assert ctx.popData() == d3;
        assert ctx.isEmpty();
    }
}
