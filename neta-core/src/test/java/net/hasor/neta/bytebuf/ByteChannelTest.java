package net.hasor.neta.bytebuf;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ByteChannelTest {
    @Test
    public void writeByteTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(4);

        byteBuf.writeBytes(new byte[] { 20, 30 });
        ByteBuffer array = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        int writeStep1 = byteBuf.write(array);
        assert writeStep1 == 2;
        byteBuf.flush();

        assert byteBuf.readByte() == 20;
        assert byteBuf.readByte() == 30;
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        byteBuf.markReader();

        int writeStep2 = byteBuf.write(array);
        assert writeStep2 == 4;
        byteBuf.flush();

        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        byteBuf.markReader();

        int writeStep3 = byteBuf.write(array);
        assert writeStep3 == 2;
        byteBuf.flush();

        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        byteBuf.markReader();
    }

    @Test
    public void readerByteTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(10);

        byteBuf.write(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
        byteBuf.flush();

        ByteBuffer array1 = ByteBuffer.allocate(2);
        assert byteBuf.readBuffer(array1) == 2;
        assert array1.get(0) == 1;
        assert array1.get(1) == 2;

        ByteBuffer array2 = ByteBuffer.allocate(10);
        assert byteBuf.readBuffer(array2) == 6;
        assert array2.get(0) == 3;
        assert array2.get(1) == 4;
        assert array2.get(2) == 5;
        assert array2.get(3) == 6;
        assert array2.get(4) == 7;
        assert array2.get(5) == 8;
    }
}
