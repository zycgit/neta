/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.quic.QuicMessage;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoSndDataTest {

    @Test
    public void test_1() {
        ByteBuf[] sndData = new ByteBuf[] {             //
                ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }),//
                ByteBuf.wrap(new byte[] { 1 }),         //
                ByteBuf.EMPTY,                          //
                ByteBuf.wrap(new byte[] { 1, 2, 3 })    //
        };
        ByteBuffer wrap = ByteBuffer.wrap(new byte[1]);

        SoSndData soData = new SoSndData(8, sndData, new BasicFuture<>(), null);
        assert soData.getDataSize() == 8;
        assert soData.hasReadable();

        soData.transferTo(wrap);
        assert soData.getDataSize() == 8;
        assert soData.hasReadable();

        soData.transferTo(wrap);
        assert soData.getDataSize() == 8;
        assert soData.hasReadable();
    }

    @Test
    public void test_2() {
        ByteBuf[] sndData = new ByteBuf[] {             //
                ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }),//
                ByteBuf.wrap(new byte[] { 1 }),         //
                ByteBuf.EMPTY,                          //
                ByteBuf.wrap(new byte[] { 1, 2, 3 })    //
        };
        ByteBuffer wrap = ByteBuffer.wrap(new byte[1]);

        SoSndData soData = new SoSndData(8, sndData, new BasicFuture<>(), null);
        assert soData.getDataSize() == 8;
        assert soData.hasReadable();

        long testReadableBytes = soData.getDataSize();
        while (testReadableBytes > 0) {
            assert soData.getDataSize() == 8;
            assert soData.hasReadable();
            soData.transferTo(wrap);
            wrap.clear();
            testReadableBytes--;
        }

        assert soData.getDataSize() == 8;
        assert !soData.hasReadable();
    }

    @Test
    public void test_3() {
        ByteBuf[] sndData = new ByteBuf[] {             //
                ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }),//
                ByteBuf.wrap(new byte[] { 1 }),         //
                ByteBuf.EMPTY,                          //
                ByteBuf.wrap(new byte[] { 1, 2, 3 })    //
        };
        ByteBuffer wrap = ByteBuffer.wrap(new byte[3]);

        SoSndData soData = new SoSndData(8, sndData, new BasicFuture<>(), null);

        assert soData.transferTo(wrap) == 3;
        assert wrap.get(0) == 1;
        assert wrap.get(1) == 2;
        assert wrap.get(2) == 3;
        wrap.clear();

        assert soData.transferTo(wrap) == 3;
        assert wrap.get(0) == 4;
        assert wrap.get(1) == 1;
        assert wrap.get(2) == 1;
        wrap.clear();

        assert soData.transferTo(wrap) == 2;
        assert wrap.get(0) == 2;
        assert wrap.get(1) == 3;
        wrap.clear();

        assert soData.getDataSize() == 8;
        assert !soData.hasReadable();
    }

    @Test
    public void test_quic_message_failed_should_recycle() {
        ByteBuf failedBody = ByteBuf.wrap("failed".getBytes(StandardCharsets.UTF_8));
        QuicMessage failedMessage = QuicMessage.of(0, failedBody, false);

        SoSndData soData = new SoSndData(failedBody.readableBytes(), new Object[] { failedMessage }, new BasicFuture<NetChannel>(), null);
        soData.failed(new SoSndException("send failed"));

        assertTrue(failedBody.isFree());

        ByteBuf reusedBody = ByteBuf.wrap("reused".getBytes(StandardCharsets.UTF_8));
        QuicMessage reusedMessage = QuicMessage.of(2, reusedBody, true);
        assertSame(failedMessage, reusedMessage);

        reusedMessage.release();
        assertTrue(reusedBody.isFree());
    }

}
