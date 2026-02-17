package net.hasor.neta.bytebuf;

import org.junit.Test;

public class AutoTypeSliceTest {
    @Test
    public void testAutoArrayByteBufSliceType() {
        try {
            ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
            if (!(buf instanceof AutoArrayByteBuf)) {
                System.err.println("buf is not AutoArrayByteBuf: " + buf.getClass().getName());
                return;
            }

            buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
            buf.markWriter();

            ByteBuf sliced = buf.sliceOff(2);

            // sliceOff returns WrapByteBuffer (non-expandable snapshot), heap/direct follows source
            if (!(sliced instanceof WrapByteBuffer)) {
                throw new RuntimeException("sliced is not WrapByteBuffer: " + sliced.getClass().getName());
            }
            if (sliced.isDirect() != buf.isDirect()) {
                throw new RuntimeException("sliced isDirect mismatch: expected " + buf.isDirect() + " but got " + sliced.isDirect());
            }
            if (sliced.capacity() != 2) {
                throw new RuntimeException("sliced capacity mismatch: " + sliced.capacity());
            }
            if (sliced.readableBytes() != 2) {
                throw new RuntimeException("sliced readableBytes mismatch: " + sliced.readableBytes());
            }
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    public void testAutoByteBufferSliceType() {
        try {
            ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();
            if (!(buf instanceof AutoByteBuffer)) {
                System.err.println("buf is not AutoByteBuffer: " + buf.getClass().getName());
                return;
            }

            buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
            buf.markWriter();
            ByteBuf sliced = buf.sliceOff(2);

            // sliceOff returns WrapByteBuffer (non-expandable snapshot), heap/direct follows source
            if (!(sliced instanceof WrapByteBuffer)) {
                throw new RuntimeException("sliced is not WrapByteBuffer: " + sliced.getClass().getName());
            }
            if (sliced.isDirect() != buf.isDirect()) {
                throw new RuntimeException("sliced isDirect mismatch: expected " + buf.isDirect() + " but got " + sliced.isDirect());
            }
            if (sliced.capacity() != 2) {
                throw new RuntimeException("sliced capacity mismatch: " + sliced.capacity());
            }
            if (sliced.readableBytes() != 2) {
                throw new RuntimeException("sliced readableBytes mismatch: " + sliced.readableBytes());
            }
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }
    }
}
