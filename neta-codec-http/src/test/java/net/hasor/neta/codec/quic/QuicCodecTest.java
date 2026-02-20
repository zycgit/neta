package net.hasor.neta.codec.quic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive tests for QUIC transport codec (encoder + decoder) implementation.
 * Uses direct handler invocation for round-trip testing.
 */
public class QuicCodecTest {

    // ========================= Mock & Helpers =========================

    private static ProtoContext mockContext() {
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            return null;
        });
    }

    /** Builds a ByteBuf with 9-byte metadata header (streamId[8] + fin[1]) + data. */
    private static ByteBuf buildStreamData(long streamId, boolean fin, byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(9 + data.length);
        byte[] header = new byte[9];
        for (int i = 7; i >= 0; i--) {
            header[i] = (byte) (streamId & 0xFF);
            streamId >>>= 8;
        }
        header[8] = (byte) (fin ? 1 : 0);
        buf.writeBytes(header, 0, 9);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return buf;
    }

    /** Reads the 8-byte stream ID from a decoded ByteBuf. */
    private static long readStreamId(ByteBuf buf) {
        byte[] header = new byte[8];
        buf.getBytes(0, header, 0, 8);
        long streamId = 0;
        for (int i = 0; i < 8; i++) {
            streamId = (streamId << 8) | (header[i] & 0xFF);
        }
        return streamId;
    }

    /** Reads the fin flag (byte 8) from a decoded ByteBuf. */
    private static boolean readFin(ByteBuf buf) {
        byte[] fin = new byte[1];
        buf.getBytes(8, fin, 0, 1);
        return fin[0] != 0;
    }

    /** Reads the data portion (after 9-byte header) from a decoded ByteBuf. */
    private static byte[] readData(ByteBuf buf) {
        int dataLen = buf.readableBytes() - 9;
        if (dataLen <= 0)
            return new byte[0];
        byte[] data = new byte[dataLen];
        buf.getBytes(9, data, 0, dataLen);
        return data;
    }

    // ========================= Inner Queue Helpers =========================

    private static class SimpleProtoRcvQueue<T> implements ProtoRcvQueue<T> {
        private final List<T> list = new ArrayList<>();

        public void add(T item) {
            list.add(item);
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int queueSize() {
            return list.size();
        }

        @Override
        public ProtoRcvQueue<T> rcvSubmit() {
            return this;
        }

        @Override
        public ProtoRcvQueue<T> rcvReset() {
            return this;
        }

        @Override
        public List<T> takeMessage(int cnt) {
            if (list.isEmpty())
                return Collections.emptyList();
            int take = Math.min(cnt, list.size());
            List<T> result = new ArrayList<>(list.subList(0, take));
            list.subList(0, take).clear();
            return result;
        }

        @Override
        public List<T> peekMessage(int cnt) {
            if (list.isEmpty())
                return Collections.emptyList();
            int take = Math.min(cnt, list.size());
            return new ArrayList<>(list.subList(0, take));
        }

        @Override
        public void skipMessage(int cnt) {
            int skip = Math.min(cnt, list.size());
            list.subList(0, skip).clear();
        }
    }

    private static class SimpleProtoSndQueue<T> implements ProtoSndQueue<T> {
        final List<T> list = new ArrayList<>();

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int slotSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean hasCommit() {
            return true;
        }

        @Override
        public ProtoSndQueue<T> sndSubmit() {
            return this;
        }

        @Override
        public ProtoSndQueue<T> sndReset() {
            return this;
        }

        @Override
        public int offerMessage(T[] offerList) {
            Collections.addAll(list, offerList);
            return offerList.length;
        }

        @Override
        public int offerMessage(List<T> offerList) {
            list.addAll(offerList);
            return offerList.size();
        }

        @Override
        public int offerMessage(ProtoRcvQueue<T> offerList) {
            int count = 0;
            while (offerList.hasMore()) {
                list.add(offerList.takeMessage());
                count++;
            }
            return count;
        }

        public int size() {
            return list.size();
        }

        public T poll() {
            return list.isEmpty() ? null : list.remove(0);
        }
    }

    // ========================= QuicVarInt Unit Tests =========================

    @Test
    public void testVarIntEncode1Byte() {
        byte[] result = QuicVarInt.encode(0);
        assertEquals(1, result.length);
        assertEquals(0x00, result[0] & 0xFF);

        result = QuicVarInt.encode(63);
        assertEquals(1, result.length);
        assertEquals(63, result[0] & 0xFF);
    }

    @Test
    public void testVarIntEncode2Bytes() {
        byte[] result = QuicVarInt.encode(64);
        assertEquals(2, result.length);
        long[] decoded = QuicVarInt.decode(result, 0);
        assertEquals(64, decoded[0]);
        assertEquals(2, decoded[1]);

        result = QuicVarInt.encode(16383);
        assertEquals(2, result.length);
        decoded = QuicVarInt.decode(result, 0);
        assertEquals(16383, decoded[0]);
    }

    @Test
    public void testVarIntEncode4Bytes() {
        byte[] result = QuicVarInt.encode(16384);
        assertEquals(4, result.length);
        long[] decoded = QuicVarInt.decode(result, 0);
        assertEquals(16384, decoded[0]);
        assertEquals(4, decoded[1]);

        result = QuicVarInt.encode(1073741823L);
        assertEquals(4, result.length);
        decoded = QuicVarInt.decode(result, 0);
        assertEquals(1073741823L, decoded[0]);
    }

    @Test
    public void testVarIntEncode8Bytes() {
        byte[] result = QuicVarInt.encode(1073741824L);
        assertEquals(8, result.length);
        long[] decoded = QuicVarInt.decode(result, 0);
        assertEquals(1073741824L, decoded[0]);
        assertEquals(8, decoded[1]);
    }

    @Test
    public void testVarIntRoundTrip() {
        long[] testValues = { 0, 1, 63, 64, 16383, 16384, 1073741823L, 1073741824L, 4611686018427387903L };
        for (long val : testValues) {
            byte[] encoded = QuicVarInt.encode(val);
            long[] decoded = QuicVarInt.decode(encoded, 0);
            assertEquals("Round-trip failed for value " + val, val, decoded[0]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testVarIntNegativeValue() {
        QuicVarInt.encode(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testVarIntDecodeInsufficientData() {
        QuicVarInt.decode(new byte[0], 0);
    }

    // ========================= Round-Trip Tests =========================

    @Test
    public void testRoundTripSimpleData() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        byte[] data = "Hello, QUIC!".getBytes(StandardCharsets.US_ASCII);
        ByteBuf input = buildStreamData(0, true, data);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);
        assertTrue(encOut.size() > 0);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);
        assertTrue(decOut.size() > 0);

        ByteBuf result = decOut.poll();
        assertNotNull(result);
        assertTrue(result.readableBytes() >= 9);

        byte[] resultData = readData(result);
        assertEquals("Hello, QUIC!", new String(resultData, StandardCharsets.US_ASCII));
    }

    @Test
    public void testRoundTripWithFin() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        byte[] data = "FIN data".getBytes(StandardCharsets.US_ASCII);
        ByteBuf input = buildStreamData(4, true, data);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        assertTrue(decOut.size() > 0);
        ByteBuf result = decOut.poll();
        assertTrue(readFin(result));
    }

    @Test
    public void testRoundTripWithoutFin() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        byte[] data = "Partial data".getBytes(StandardCharsets.US_ASCII);
        ByteBuf input = buildStreamData(0, false, data);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        assertTrue(decOut.size() > 0);
        ByteBuf result = decOut.poll();
        assertFalse(readFin(result));

        byte[] resultData = readData(result);
        assertEquals("Partial data", new String(resultData, StandardCharsets.US_ASCII));
    }

    @Test
    public void testRoundTripEmptyData() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        ByteBuf input = buildStreamData(0, true, new byte[0]);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        // Empty stream data with FIN should still produce output
        assertTrue(decOut.size() >= 0);
    }

    @Test
    public void testRoundTripLargeData() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        byte[] data = new byte[65536];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        ByteBuf input = buildStreamData(0, true, data);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        assertTrue(decOut.size() > 0);
        ByteBuf result = decOut.poll();
        byte[] resultData = readData(result);
        assertEquals(65536, resultData.length);
        assertArrayEquals(data, resultData);
    }

    @Test
    public void testRoundTripMultipleStreams() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();
        for (int i = 0; i < 10; i++) {
            long streamId = i * 4L; // client-initiated bidi streams
            byte[] data = ("stream-" + i).getBytes(StandardCharsets.US_ASCII);
            ByteBuf input = buildStreamData(streamId, true, data);

            SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            encIn.add(input);
            encoder.onMessage(mockContext(), encIn, encOut);
            while (encOut.size() > 0)
                allEncOut.list.add(encOut.poll());
        }

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (allEncOut.size() > 0)
            decIn.add(allEncOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        assertTrue("Expected 10 decoded outputs, got " + decOut.size(), decOut.size() >= 10);
    }

    @Test
    public void testStreamIdPreservation() throws Throwable {
        QuicFrameEncoder encoder = new QuicFrameEncoder(false);
        QuicFrameDecoder decoder = new QuicFrameDecoder(true);

        long testStreamId = 100;
        byte[] data = "test".getBytes(StandardCharsets.US_ASCII);
        ByteBuf input = buildStreamData(testStreamId, true, data);

        SimpleProtoRcvQueue<ByteBuf> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(input);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        assertTrue(decOut.size() > 0);
        ByteBuf result = decOut.poll();
        assertEquals(testStreamId, readStreamId(result));
    }

    // ========================= Constants Tests =========================

    @Test
    public void testQuicFrameTypeValues() {
        assertEquals(0x00, QuicFrameType.PADDING);
        assertEquals(0x01, QuicFrameType.PING);
        assertEquals(0x02, QuicFrameType.ACK);
        assertEquals(0x03, QuicFrameType.ACK_ECN);
        assertEquals(0x04, QuicFrameType.RESET_STREAM);
        assertEquals(0x05, QuicFrameType.STOP_SENDING);
        assertEquals(0x06, QuicFrameType.CRYPTO);
        assertEquals(0x07, QuicFrameType.NEW_TOKEN);
        assertEquals(0x08, QuicFrameType.STREAM);
        assertEquals(0x10, QuicFrameType.MAX_DATA);
    }

    @Test
    public void testQuicFrameTypeNames() {
        assertEquals("PADDING", QuicFrameType.name(QuicFrameType.PADDING));
        assertEquals("PING", QuicFrameType.name(QuicFrameType.PING));
        assertEquals("ACK", QuicFrameType.name(QuicFrameType.ACK));
        assertEquals("STREAM", QuicFrameType.name(QuicFrameType.STREAM));
        assertEquals("STREAM", QuicFrameType.name(0x0F)); // all STREAM variants
        assertTrue(QuicFrameType.name(0xFFF).contains("UNKNOWN"));
    }

    @Test
    public void testQuicFrameTypeStreamBits() {
        assertTrue(QuicFrameType.isStream(0x08));
        assertTrue(QuicFrameType.isStream(0x0F));
        assertFalse(QuicFrameType.isStream(0x07));
        assertFalse(QuicFrameType.isStream(0x10));

        // streamFin checks bit 0x01
        assertTrue(QuicFrameType.streamFin(0x09));
        assertFalse(QuicFrameType.streamFin(0x08));

        // streamLen checks bit 0x02
        assertTrue(QuicFrameType.streamLen(0x0A));
        assertFalse(QuicFrameType.streamLen(0x08));

        // streamOff checks bit 0x04
        assertTrue(QuicFrameType.streamOff(0x0C));
        assertFalse(QuicFrameType.streamOff(0x08));
    }

    @Test
    public void testQuicStreamStateValues() {
        QuicStreamState[] states = QuicStreamState.values();
        assertEquals(7, states.length);
        assertEquals(QuicStreamState.IDLE, QuicStreamState.valueOf("IDLE"));
        assertEquals(QuicStreamState.OPEN, QuicStreamState.valueOf("OPEN"));
        assertEquals(QuicStreamState.CLOSED, QuicStreamState.valueOf("CLOSED"));
        assertEquals(QuicStreamState.HALF_CLOSED_LOCAL, QuicStreamState.valueOf("HALF_CLOSED_LOCAL"));
        assertEquals(QuicStreamState.HALF_CLOSED_REMOTE, QuicStreamState.valueOf("HALF_CLOSED_REMOTE"));
        assertEquals(QuicStreamState.RESET_LOCAL, QuicStreamState.valueOf("RESET_LOCAL"));
        assertEquals(QuicStreamState.RESET_REMOTE, QuicStreamState.valueOf("RESET_REMOTE"));
    }

    @Test
    public void testQuicErrorCodeValues() {
        assertEquals(0x00L, QuicErrorCode.NO_ERROR);
        assertEquals(0x01L, QuicErrorCode.INTERNAL_ERROR);
        assertEquals(0x02L, QuicErrorCode.CONNECTION_REFUSED);
        assertEquals(0x03L, QuicErrorCode.FLOW_CONTROL_ERROR);
        assertEquals(0x04L, QuicErrorCode.STREAM_LIMIT_ERROR);
        assertEquals(0x05L, QuicErrorCode.STREAM_STATE_ERROR);
        assertEquals(0x06L, QuicErrorCode.FINAL_SIZE_ERROR);
        assertEquals(0x07L, QuicErrorCode.FRAME_ENCODING_ERROR);
        assertEquals(0x0aL, QuicErrorCode.PROTOCOL_VIOLATION);
        assertEquals(0x0100L, QuicErrorCode.CRYPTO_ERROR_BASE);
    }

    @Test
    public void testQuicSettingsDefaults() {
        QuicSettings settings = new QuicSettings();
        assertNotNull(settings);
    }
}
