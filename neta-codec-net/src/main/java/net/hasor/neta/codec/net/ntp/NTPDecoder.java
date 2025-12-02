package net.hasor.neta.codec.net.ntp;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.ProtoStatus;

public class NTPDecoder implements ProtoHandler<ByteBuf, NTPMessage> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<NTPMessage> dst) throws Throwable {
        while (src.hasMore()) {
            ByteBuf buf = src.takeMessage();
            try {
                if (buf.readableBytes() < 1) {
                    continue;
                }

                // Peek mode
                byte b0 = buf.getByte(buf.readerIndex());
                int mode = b0 & 0x7;

                if (mode == 6) {
                    decodeControlPacket(buf, dst);
                } else if (mode >= 0 && mode <= 5) {
                    decodeNTPPacket(buf, dst);
                } else {
                    throw new IllegalStateException("unsupported ntp mode: " + mode);
                }
            } finally {
                // Handle buffer release if necessary
            }
        }
        return ProtoStatus.Next;
    }

    private void decodeNTPPacket(ByteBuf buf, ProtoSndQueue<NTPMessage> dst) {
        if (buf.readableBytes() < 48) {
            throw new IllegalStateException("packet length too short: " + buf.readableBytes());
        }

        NTPPacket packet = new NTPPacket();
        byte b0 = buf.readByte();
        packet.setLeapIndicator((byte) ((b0 >> 6) & 0x3));
        packet.setVersion((byte) ((b0 >> 3) & 0x7));
        packet.setNtpMode(NTPMode.fromMode(b0 & 0x7));

        packet.setStratum(buf.readUInt8());
        packet.setPollInterval(buf.readByte());
        packet.setPrecision(buf.readByte());

        packet.setRootDelay(buf.readInt32());
        packet.setRootDispersion(buf.readInt32());
        packet.setReferenceID(buf.readInt32());

        packet.setReferenceTimestamp(buf.readInt64());
        packet.setOriginateTimestamp(buf.readInt64());
        packet.setReceiveTimestamp(buf.readInt64());
        packet.setTransmitTimestamp(buf.readInt64());

        if (buf.readableBytes() > 0) {
            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

        buf.markReader();
        dst.offerMessage(packet);
    }

    private void decodeControlPacket(ByteBuf buf, ProtoSndQueue<NTPMessage> dst) {
        if (buf.readableBytes() < 12) {
            throw new IllegalStateException("control packet length too short: " + buf.readableBytes());
        }

        NTPControlPacket packet = new NTPControlPacket();
        byte b0 = buf.readByte();
        packet.setLeapIndicator((byte) ((b0 >> 6) & 0x3));
        packet.setVersion((byte) ((b0 >> 3) & 0x7));
        packet.setNtpMode(NTPMode.CONTROL_MESSAGE);

        byte b1 = buf.readByte();
        packet.setRem((byte) ((b1 >> 5) & 0x7));
        packet.setOp((byte) (b1 & 0x1F));

        packet.setSequence(buf.readUInt16());
        packet.setStatus(buf.readUInt16());
        packet.setAssociationID(buf.readUInt16());
        packet.setOffset(buf.readUInt16());
        packet.setCount(buf.readUInt16());

        int dataLen = packet.getCount();
        if (dataLen > 0 && buf.readableBytes() >= dataLen) {
            byte[] data = new byte[dataLen];
            buf.readBytes(data);
            packet.setData(data);
        }

        if (buf.readableBytes() > 0) {
            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

        buf.markReader();
        dst.offerMessage(packet);
    }
}
