package net.hasor.cobble.bytebuf;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class StreamByteBufTest {
    @Test
    public void writeByteTest01() throws IOException {
        ByteArrayInputStream inStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 });
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        StreamByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapStreamBuffer(4, inStream, outStream);

        while (byteBuf.fetch()) {
            while (byteBuf.readableBytes() > 0) {
                byte b = byteBuf.readByte();
                byteBuf.markReader();
                byteBuf.writeByte((byte) (b + 10));
                byteBuf.markWriter();
            }
        }

        byte[] array = outStream.toByteArray();
        assert array[0] == 11;
        assert array[1] == 12;
        assert array[2] == 13;
        assert array[3] == 14;
        assert array[4] == 15;
    }

    @Test
    public void inputStreamByteTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(17);

        byteBuf.writeInt16((short) 30047);
        byteBuf.writeInt24(15793921);
        byteBuf.writeInt32(1894780842);
        byteBuf.writeInt64(8071575397336023920L);
        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == 0;

        byteBuf.flush();
        assert byteBuf.readableBytes() == byteBuf.capacity();
        assert byteBuf.writableBytes() == 0;

        ByteBufInputStream bufIn = new ByteBufInputStream(byteBuf);
        assert bufIn.readShort() == 30047;
        assert bufIn.readMedium() == 15793921;
        assert bufIn.readInt() == 1894780842;
        assert bufIn.readLong() == 8071575397336023920L;

        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == byteBuf.capacity();
    }

    @Test
    public void outputStreamByteTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(17);

        ByteBufOutputStream bufOutput = new ByteBufOutputStream(byteBuf);
        bufOutput.writeShort((short) 30047);
        bufOutput.writeMedium(15793921);
        bufOutput.writeInt(1894780842);
        bufOutput.writeLong(8071575397336023920L);
        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == 0;

        bufOutput.flush();
        assert byteBuf.readableBytes() == byteBuf.capacity();
        assert byteBuf.writableBytes() == 0;

        assert byteBuf.readableBytes() == byteBuf.capacity();
        assert byteBuf.writableBytes() == 0;

        ByteBufInputStream bufIn = new ByteBufInputStream(byteBuf);
        assert bufIn.readShort() == 30047;
        assert bufIn.readMedium() == 15793921;
        assert bufIn.readInt() == 1894780842;
        assert bufIn.readLong() == 8071575397336023920L;

        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == byteBuf.capacity();
    }
}
