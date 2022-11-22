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

        while (byteBuf.loadData()) {
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
}
