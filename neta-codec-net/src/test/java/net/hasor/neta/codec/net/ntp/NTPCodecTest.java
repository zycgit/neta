/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.net.ntp;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import org.junit.Test;

public class NTPCodecTest {
    @Test
    public void testNTPPacketEncoding() throws Throwable {
        NetManager neta = new NetManager();

        // Server: Receives ByteBuf (No Codec)
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
        }, VrtSoConfig.asServer());

        // Client: Sends NTPPacket (With NTPEncoder)
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ctx.addLastEncoder(new NTPEncoder());
        }, VrtSoConfig.asClient());

        // Link
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Capture received data
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        // Prepare Data
        long refTs = System.currentTimeMillis();
        long origTs = System.currentTimeMillis() + 1000;
        long recvTs = System.currentTimeMillis() + 2000;
        long transTs = System.currentTimeMillis() + 3000;

        NTPPacket packet = new NTPPacket();
        packet.setLeapIndicator((byte) 1);
        packet.setVersion((byte) 3);
        packet.setNtpMode(NTPMode.CLIENT);
        packet.setStratum(2);
        packet.setPollInterval(4);
        packet.setPrecision((byte) -6);
        packet.setRootDelay(100);
        packet.setRootDispersion(200);
        packet.setReferenceID(0x12345678);
        packet.setReferenceTimestamp(refTs);
        packet.setOriginateTimestamp(origTs);
        packet.setReceiveTimestamp(recvTs);
        packet.setTransmitTimestamp(transTs);
        packet.setAuthenticator(new byte[] { 1, 2, 3, 4 });

        // Send
        client.sendData(packet).get();

        // Verify
        assert rcvData.size() == 1;
        ByteBuf buf = (ByteBuf) rcvData.poll();
        assert buf != null;
        assert buf.readableBytes() >= 48;

        // Verify Header
        byte b0 = buf.readByte();
        assert ((b0 >> 6) & 0x3) == 1; // LI
        assert ((b0 >> 3) & 0x7) == 3; // VN
        assert (b0 & 0x7) == 3;        // Mode (Client=3)

        assert buf.readByte() == 2;  // Stratum
        assert buf.readByte() == 4;  // Poll
        assert buf.readByte() == -6; // Precision

        assert buf.readInt32() == 100; // Root Delay
        assert buf.readInt32() == 200; // Root Dispersion
        assert buf.readInt32() == 0x12345678; // Reference ID

        assert buf.readInt64() == refTs; // Reference Timestamp
        assert buf.readInt64() == origTs; // Originate Timestamp
        assert buf.readInt64() == recvTs; // Receive Timestamp
        assert buf.readInt64() == transTs; // Transmit Timestamp

        // Verify Authenticator
        byte[] auth = new byte[4];
        buf.readBytes(auth);
        assert Arrays.equals(new byte[] { 1, 2, 3, 4 }, auth);

        neta.shutdown();
    }

    @Test
    public void testNTPPacketDecoding() throws Throwable {
        NetManager neta = new NetManager();

        // Server: Receives NTPPacket (With NTPDecoder)
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new NTPDecoder());
        }, VrtSoConfig.asServer());

        // Client: Sends ByteBuf (No Codec)
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
        }, VrtSoConfig.asClient());

        // Link
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Capture received data
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        // Prepare Data (Manually construct ByteBuf)
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        // Mode 3 (Client), Version 3, LI 0
        byte b0 = 0;
        b0 |= (0 & 0x3) << 6;
        b0 |= (3 & 0x7) << 3;
        b0 |= (3 & 0x7);
        buf.writeByte(b0);

        buf.writeByte((byte) 2); // Stratum
        buf.writeByte((byte) 4); // Poll
        buf.writeByte((byte) -6); // Precision

        buf.writeInt32(100); // Root Delay
        buf.writeInt32(200); // Root Dispersion
        buf.writeInt32(0x12345678); // Reference ID

        long refTs = System.currentTimeMillis();
        long origTs = refTs + 1000;
        long recvTs = refTs + 2000;
        long transTs = refTs + 3000;

        buf.writeInt64(refTs); // Ref TS
        buf.writeInt64(origTs); // Orig TS
        buf.writeInt64(recvTs); // Recv TS
        buf.writeInt64(transTs); // Trans TS

        buf.writeBytes(new byte[] { 1, 2, 3, 4 }); // Authenticator

        buf.markWriter();

        // Send
        client.sendData(buf).get();

        // Verify
        assert rcvData.size() == 1;
        Object msg = rcvData.poll();
        assert msg instanceof NTPPacket;
        NTPPacket decodedPacket = (NTPPacket) msg;

        assert decodedPacket.getLeapIndicator() == 0;
        assert decodedPacket.getVersion() == 3;
        assert decodedPacket.getNtpMode() == NTPMode.CLIENT;
        assert decodedPacket.getStratum() == 2;
        assert decodedPacket.getPollInterval() == 4;
        assert decodedPacket.getPrecision() == -6;
        assert decodedPacket.getRootDelay() == 100;
        assert decodedPacket.getRootDispersion() == 200;
        assert decodedPacket.getReferenceID() == 0x12345678;
        assert decodedPacket.getReferenceTimestamp() == refTs;
        assert decodedPacket.getOriginateTimestamp() == origTs;
        assert decodedPacket.getReceiveTimestamp() == recvTs;
        assert decodedPacket.getTransmitTimestamp() == transTs;
        assert Arrays.equals(new byte[] { 1, 2, 3, 4 }, decodedPacket.getAuthenticator());

        neta.shutdown();
    }

    @Test
    public void testNTPControlPacketEncoding() throws Throwable {
        NetManager neta = new NetManager();

        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
        }, VrtSoConfig.asServer());

        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ctx.addLastEncoder(new NTPEncoder());
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        NTPControlPacket packet = new NTPControlPacket();
        packet.setLeapIndicator((byte) 0);
        packet.setVersion((byte) 3);
        // Rem = 1 (001) -> R=0, E=0, M=1
        packet.setResponseBit((byte) 0);
        packet.setErrorBit((byte) 0);
        packet.setMoreBit((byte) 1);
        packet.setOperationCode((byte) 2);
        packet.setSequence(100);
        packet.setStatus(200);
        packet.setAssociationID(300);
        packet.setOffset(400);
        packet.setCount(4);
        packet.setData(new byte[] { 0xA, 0xB, 0xC, 0xD });
        packet.setAuthenticator(new byte[] { 9, 8, 7 });

        client.sendData(packet).get();

        assert rcvData.size() == 1;
        ByteBuf buf = (ByteBuf) rcvData.poll();
        assert buf != null;
        assert buf.readableBytes() >= 12;

        // Verify Header
        byte b0 = buf.readByte();
        assert ((b0 >> 6) & 0x3) == 0; // LI
        assert ((b0 >> 3) & 0x7) == 3; // VN
        assert (b0 & 0x7) == 6;        // Mode (Control=6)

        byte b1 = buf.readByte();
        assert ((b1 >> 5) & 0x7) == 1; // REM
        assert (b1 & 0x1F) == 2;       // Op

        assert buf.readInt16() == 100; // Sequence
        assert buf.readInt16() == 200; // Status
        assert buf.readInt16() == 300; // Assoc ID
        assert buf.readInt16() == 400; // Offset
        assert buf.readInt16() == 4;   // Count

        byte[] data = new byte[4];
        buf.readBytes(data);
        assert Arrays.equals(new byte[] { 0xA, 0xB, 0xC, 0xD }, data);

        byte[] auth = new byte[3];
        buf.readBytes(auth);
        assert Arrays.equals(new byte[] { 9, 8, 7 }, auth);

        neta.shutdown();
    }

    @Test
    public void testNTPControlPacketDecoding() throws Throwable {
        NetManager neta = new NetManager();

        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new NTPDecoder());
        }, VrtSoConfig.asServer());

        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        // Mode 6 (Control), Version 3, LI 0
        byte b0 = 0;
        b0 |= (0 & 0x3) << 6;
        b0 |= (3 & 0x7) << 3;
        b0 |= (6 & 0x7);
        buf.writeByte(b0);

        // Rem 1, Op 2
        byte b1 = 0;
        b1 |= (1 & 0x7) << 5;
        b1 |= (2 & 0x1F);
        buf.writeByte(b1);

        buf.writeInt16((short) 100); // Sequence
        buf.writeInt16((short) 200); // Status
        buf.writeInt16((short) 300); // Assoc ID
        buf.writeInt16((short) 400); // Offset
        buf.writeInt16((short) 4);   // Count

        buf.writeBytes(new byte[] { 0xA, 0xB, 0xC, 0xD }); // Data
        buf.writeBytes(new byte[] { 9, 8, 7 }); // Authenticator

        buf.markWriter();

        client.sendData(buf).get();

        assert rcvData.size() == 1;
        Object msg = rcvData.poll();
        assert msg instanceof NTPControlPacket;
        NTPControlPacket decodedPacket = (NTPControlPacket) msg;

        assert decodedPacket.getLeapIndicator() == 0;
        assert decodedPacket.getVersion() == 3;
        assert decodedPacket.getNtpMode() == NTPMode.CONTROL_MESSAGE;
        // Rem = 1 -> R=0, E=0, M=1
        assert decodedPacket.getResponseBit() == 0;
        assert decodedPacket.getErrorBit() == 0;
        assert decodedPacket.getMoreBit() == 1;
        assert decodedPacket.getOperationCode() == 2;
        assert decodedPacket.getSequence() == 100;
        assert decodedPacket.getStatus() == 200;
        assert decodedPacket.getAssociationID() == 300;
        assert decodedPacket.getOffset() == 400;
        assert decodedPacket.getCount() == 4;
        assert Arrays.equals(new byte[] { 0xA, 0xB, 0xC, 0xD }, decodedPacket.getData());
        assert Arrays.equals(new byte[] { 9, 8, 7 }, decodedPacket.getAuthenticator());

        neta.shutdown();
    }

    @Test
    public void testNTPv4ExtensionFields() throws Throwable {
        NetManager neta = new NetManager();

        // Server: Receives NTPPacket (With NTPDecoder)
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new NTPDecoder());
        }, VrtSoConfig.asServer());

        // Client: Sends ByteBuf (No Codec)
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
        }, VrtSoConfig.asClient());

        // Link
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Capture received data
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        // Prepare Data (Manually construct ByteBuf for V4)
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        // Mode 3 (Client), Version 4, LI 0
        byte b0 = 0;
        b0 |= (0 & 0x3) << 6;
        b0 |= (4 & 0x7) << 3; // Version 4
        b0 |= (3 & 0x7);
        buf.writeByte(b0);

        buf.writeByte((byte) 2); // Stratum
        buf.writeByte((byte) 4); // Poll
        buf.writeByte((byte) -6); // Precision

        buf.writeInt32(100); // Root Delay
        buf.writeInt32(200); // Root Dispersion
        buf.writeInt32(0x12345678); // Reference ID

        long ts = System.currentTimeMillis();
        buf.writeInt64(ts); // Ref TS
        buf.writeInt64(ts); // Orig TS
        buf.writeInt64(ts); // Recv TS
        buf.writeInt64(ts); // Trans TS

        // Extension Field 1: Type 0x0001, Length 8 (4 header + 4 value)
        buf.writeInt16((short) 0x0001);
        buf.writeInt16((short) 8);
        buf.writeBytes(new byte[] { 0xA, 0xB, 0xC, 0xD });

        // Extension Field 2: Type 0x0002, Length 4 (4 header + 0 value)
        buf.writeInt16((short) 0x0002);
        buf.writeInt16((short) 4);

        // Authenticator (Optional, but let's add it to test boundary)
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });

        buf.markWriter();

        // Send
        client.sendData(buf).get();

        // Verify
        assert rcvData.size() == 1;
        Object msg = rcvData.poll();
        assert msg instanceof NTPPacket;
        NTPPacket decodedPacket = (NTPPacket) msg;

        assert decodedPacket.getVersion() == 4;
        assert decodedPacket.getExtensionFields().size() == 2;

        NTPField field1 = decodedPacket.getExtensionFields().get(0);
        assert field1.getFieldType() == 1;
        assert field1.getLength() == 8;
        assert Arrays.equals(new byte[] { 0xA, 0xB, 0xC, 0xD }, field1.getValue());

        NTPField field2 = decodedPacket.getExtensionFields().get(1);
        assert field2.getFieldType() == 2;
        assert field2.getLength() == 4;
        assert field2.getValue() == null || field2.getValue().length == 0;

        assert Arrays.equals(new byte[] { 1, 2, 3, 4 }, decodedPacket.getAuthenticator());

        neta.shutdown();
    }

    @Test
    public void testNTPv4ExtensionEncoding() throws Throwable {
        NetManager neta = new NetManager();

        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
        }, VrtSoConfig.asServer());

        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ctx.addLastEncoder(new NTPEncoder());
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        NTPPacket packet = new NTPPacket();
        packet.setVersion((byte) 4);
        packet.setNtpMode(NTPMode.CLIENT);

        packet.addExtensionField(new NTPField((short) 1, new byte[] { 1, 2, 3, 4 }));
        packet.addExtensionField(new NTPField((short) 2, null));

        packet.setAuthenticator(new byte[] { 9, 9, 9, 9 });

        client.sendData(packet).get();

        assert rcvData.size() == 1;
        ByteBuf buf = (ByteBuf) rcvData.poll();
        assert buf != null;

        // Skip Header (48 bytes)
        buf.skipReadableBytes(48);

        // Verify Ext Field 1
        assert buf.readInt16() == 1; // Type
        assert buf.readInt16() == 8; // Length
        byte[] val1 = new byte[4];
        buf.readBytes(val1);
        assert Arrays.equals(new byte[] { 1, 2, 3, 4 }, val1);

        // Verify Ext Field 2
        assert buf.readInt16() == 2; // Type
        assert buf.readInt16() == 4; // Length

        // Verify Authenticator
        byte[] auth = new byte[4];
        buf.readBytes(auth);
        assert Arrays.equals(new byte[] { 9, 9, 9, 9 }, auth);

        neta.shutdown();
    }

    @Test
    public void testStickyPackets() throws Throwable {
        NetManager neta = new NetManager();

        // Server: NTPDecoder
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new NTPDecoder());
        }, VrtSoConfig.asServer());

        // Client: No Codec (Raw ByteBuf)
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            // No encoder
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer(d.getData()));

        // Construct Sticky Buffer
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(96);

        // Packet 1: V3 (3<<3 = 24), Client (3), Stratum 1
        // 00 011 011 = 0x1B
        buf.writeByte((byte) 0x1B);
        buf.writeByte((byte) 1); // Stratum
        buf.writeBytes(new byte[46]); // Rest of header

        // Packet 2: V4 (4<<3 = 32), Server (4), Stratum 2
        // 00 100 100 = 0x24
        buf.writeByte((byte) 0x24);
        buf.writeByte((byte) 2); // Stratum
        buf.writeBytes(new byte[46]); // Rest of header

        buf.markWriter();

        // Send
        client.sendData(buf).get();

        // Verify
        Thread.sleep(100);
        assert rcvData.size() == 2;

        NTPPacket p1 = (NTPPacket) rcvData.poll();
        assert p1.getVersion() == 3;
        assert p1.getStratum() == 1;

        NTPPacket p2 = (NTPPacket) rcvData.poll();
        assert p2.getVersion() == 4;
        assert p2.getStratum() == 2;

        neta.shutdown();
    }

    @org.junit.Test
    public void testV3IgnoreExtensionFields() throws Throwable {
        NetManager neta = new NetManager();

        // Server: No Codec (Raw ByteBuf)
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            // No decoder
        }, VrtSoConfig.asServer());

        // Client: NTPEncoder
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ctx.addLastEncoder(new NTPEncoder());
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> {
            ByteBuf b = (ByteBuf) d.getData();
            // Copy buffer because it might be recycled
            rcvData.offer(b.copy());
        });

        // Construct V3 Packet with Extension Field
        NTPPacket packet = new NTPPacket();
        packet.setVersion((byte) 3);
        packet.setNtpMode(NTPMode.CLIENT);

        NTPField ext = new NTPField((short) 1, new byte[] { 1, 2, 3, 4 });
        packet.addExtensionField(ext);

        // Send
        client.sendData(packet).get();

        // Verify
        Thread.sleep(100);
        assert rcvData.size() == 1;

        ByteBuf received = rcvData.poll();
        // Should be exactly 48 bytes (header only), ignoring the 8 byte extension
        assert received.readableBytes() == 48;

        neta.shutdown();
    }
}
