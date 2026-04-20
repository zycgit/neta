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

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for ReadOnlyByteBuf
 */
public class ReadOnlyByteBufTest {

    private ByteBuf createTestBuf() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        return buf;
    }

    // ========== asReadOnly basic ==========

    @Test
    public void asReadOnly_returnsReadOnlyWrapper() {
        ByteBuf buf = createTestBuf();
        ByteBuf ro = buf.asReadOnly();
        assertNotNull(ro);
        assertTrue(ro instanceof ReadOnlyByteBuf);
        ro.free();
    }

    @Test
    public void asReadOnly_onReadOnly_returnsSelf() {
        ByteBuf buf = createTestBuf();
        ByteBuf ro = buf.asReadOnly();
        ByteBuf ro2 = ro.asReadOnly();
        assertSame(ro, ro2);
        ro.free();
    }

    @Test
    public void asReadOnly_EMPTY_returnsSelf() {
        ByteBuf ro = ByteBuf.EMPTY.asReadOnly();
        assertSame(ByteBuf.EMPTY, ro);
    }

    // ========== Read operations work ==========

    @Test
    public void readOnly_readByte_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(10, ro.readByte());
        assertEquals(20, ro.readByte());
        assertEquals(30, ro.readByte());
        ro.free();
    }

    @Test
    public void readOnly_readBytes_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        ByteBuf ro = buf.asReadOnly();
        byte[] dst = new byte[4];
        ro.readBytes(dst);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, dst);
        ro.free();
    }

    @Test
    public void readOnly_getByte_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(10, ro.getByte(0));
        assertEquals(20, ro.getByte(1));
        assertEquals(30, ro.getByte(2));
        ro.free();
    }

    @Test
    public void readOnly_readableBytes() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(5, ro.readableBytes());
        ro.readByte();
        assertEquals(4, ro.readableBytes());
        ro.free();
    }

    @Test
    public void readOnly_readInt16_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 0, 42 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(42, ro.readInt16());
        ro.free();
    }

    @Test
    public void readOnly_readInt32_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 0, 0, 1, 0 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(256, ro.readInt32());
        ro.free();
    }

    @Test
    public void readOnly_readInt64_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 0, 0, 0, 0, 0, 0, 0, 1 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(1L, ro.readInt64());
        ro.free();
    }

    @Test
    public void readOnly_readBuffer_ByteBuffer_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf ro = buf.asReadOnly();
        ByteBuffer dst = ByteBuffer.allocate(3);
        ro.readBuffer(dst);
        dst.flip();
        assertEquals(1, dst.get());
        assertEquals(2, dst.get());
        assertEquals(3, dst.get());
        ro.free();
    }

    @Test
    public void readOnly_asByteArray_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 5, 6, 7 });
        ByteBuf ro = buf.asReadOnly();
        assertArrayEquals(new byte[] { 5, 6, 7 }, ro.asByteArray());
        ro.free();
    }

    @Test
    public void readOnly_capacity_isDirect_work() {
        byte[] data = new byte[] { 1, 2, 3 };
        ByteBuf buf = ByteBuf.wrap(data);
        ByteBuf ro = buf.asReadOnly();
        assertEquals(3, ro.capacity());
        assertFalse(ro.isDirect());
        ro.free();
    }

    @Test
    public void readOnly_skipReadableBytes_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        ByteBuf ro = buf.asReadOnly();
        ro.skipReadableBytes(2);
        assertEquals(3, ro.readByte());
        ro.free();
    }

    @Test
    public void readOnly_markReader_resetReader_work() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        ByteBuf ro = buf.asReadOnly();
        ro.readByte(); // read=1, now at position 1
        ro.markReader();
        ro.readByte(); // read=2, now at position 2
        ro.resetReader();
        assertEquals(2, ro.readByte()); // back to marked position
        ro.free();
    }

    @Test
    public void readOnly_copy_works() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        ByteBuf ro = buf.asReadOnly();
        ByteBuf cpy = ro.copy();
        assertNotNull(cpy);
        cpy.free();
        ro.free();
    }

    @Test
    public void readOnly_writableBytes_isZero() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(0, ro.writableBytes());
        ro.free();
    }

    @Test
    public void readOnly_writtenBytes_isZero() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf ro = buf.asReadOnly();
        assertEquals(0, ro.writtenBytes());
        ro.free();
    }

    // ========== Write operations throw ReadOnlyBufferException ==========

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeByte_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeByte((byte) 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBytes_array_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBytes(new byte[] { 1, 2 });
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBytes_arrayOffLen_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBytes(new byte[] { 1, 2, 3 }, 0, 2);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeInt16_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeInt16((short) 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeInt24_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeInt24(1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeInt32_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeInt32(1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeUInt32_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeUInt32(1L);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeInt64_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeInt64(1L);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeFloat32_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeFloat32(1.0f);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeFloat64_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeFloat64(1.0d);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBuffer_ByteBuffer_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBuffer(ByteBuffer.allocate(4));
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBuffer_ByteBuffer_len_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBuffer(ByteBuffer.allocate(4), 2);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBuffer_ByteBuf_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBuffer(ByteBuf.wrap(new byte[] { 1 }));
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeBuffer_ByteBuf_len_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeBuffer(ByteBuf.wrap(new byte[] { 1 }), 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_writeString_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.writeString("hello", StandardCharsets.UTF_8);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setByte_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setByte(0, (byte) 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBytes_array_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBytes(0, new byte[] { 1 });
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBytes_arrayOffLen_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBytes(0, new byte[] { 1, 2 }, 0, 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setInt16_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setInt16(0, (short) 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setInt24_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setInt24(0, 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setInt32_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setInt32(0, 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setInt64_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setInt64(0, 1L);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setFloat32_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setFloat32(0, 1.0f);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setFloat64_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setFloat64(0, 1.0d);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBuffer_ByteBuffer_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBuffer(0, ByteBuffer.allocate(4));
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBuffer_ByteBuffer_len_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBuffer(0, ByteBuffer.allocate(4), 2);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBuffer_ByteBuf_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBuffer(0, ByteBuf.wrap(new byte[] { 1 }));
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setBuffer_ByteBuf_len_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setBuffer(0, ByteBuf.wrap(new byte[] { 1 }), 1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_setString_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.setString(0, "hi", StandardCharsets.UTF_8);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_write_ByteBuffer_channel_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.write(ByteBuffer.allocate(4));
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_skipWritableBytes_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.skipWritableBytes(1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_discardReadBytes_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.discardReadBytes();
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_sliceOff_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.sliceOff(1);
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_clear_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.clear();
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_markWriter_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.markWriter();
    }

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_resetWriter_throws() {
        ByteBuf ro = createTestBuf().asReadOnly();
        ro.resetWriter();
    }

    // ========== Lifecycle ==========

    @Test
    public void readOnly_refCnt_delegates() {
        ByteBuf buf = createTestBuf();
        ByteBuf ro = buf.asReadOnly();
        assertEquals(buf.refCnt(), ro.refCnt());
        ro.retain();
        assertEquals(2, ro.refCnt());
        ro.release();
        assertEquals(1, ro.refCnt());
        ro.free();
    }

    @Test
    public void readOnly_isFree_delegates() {
        ByteBuf buf = createTestBuf();
        ByteBuf ro = buf.asReadOnly();
        assertFalse(ro.isFree());
        ro.free();
        assertTrue(ro.isFree());
    }

    @Test
    public void readOnly_isOpen_delegates() {
        ByteBuf buf = createTestBuf();
        ByteBuf ro = buf.asReadOnly();
        assertTrue(ro.isOpen());
        ro.free();
        assertFalse(ro.isOpen());
    }
}
