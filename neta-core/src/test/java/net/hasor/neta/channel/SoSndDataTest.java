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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;

import java.nio.ByteBuffer;

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

        SoSndData soData = new SoSndData(sndData, new BasicFuture<>(), null);
        assert soData.getDataSize() == 8;
        assert soData.readableBytes() == 8;
        assert soData.hasReadable();

        soData.transferTo(wrap);
        assert soData.getDataSize() == 8;
        assert soData.readableBytes() == 7;
        assert soData.hasReadable();

        soData.transferTo(wrap);
        assert soData.getDataSize() == 8;
        assert soData.readableBytes() == 7;
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

        SoSndData soData = new SoSndData(sndData, new BasicFuture<>(), null);
        assert soData.getDataSize() == 8;
        assert soData.readableBytes() == 8;
        assert soData.hasReadable();

        long testReadableBytes = soData.getDataSize();
        while (testReadableBytes > 0) {
            assert soData.getDataSize() == 8;
            assert soData.readableBytes() == testReadableBytes;
            assert soData.hasReadable();
            soData.transferTo(wrap);
            wrap.clear();
            testReadableBytes--;
        }

        assert soData.getDataSize() == 8;
        assert soData.readableBytes() == 0;
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

        SoSndData soData = new SoSndData(sndData, new BasicFuture<>(), null);

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
        assert soData.readableBytes() == 0;
        assert !soData.hasReadable();
    }

}