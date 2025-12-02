package net.hasor.neta.codec.net.ntp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

public class NTPEncoder implements ProtoHandler<NTPMessage, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<NTPMessage> src, ProtoSndQueue<ByteBuf> dst) {
        while (src.hasMore()) {
            NTPMessage message = src.takeMessage();
            if (message instanceof NTPPacket) {
                encodePacket(context, (NTPPacket) message, dst);
            } else if (message instanceof NTPControlPacket) {
                encodePacket(context, (NTPControlPacket) message, dst);
            } else {
                throw new IllegalStateException("unknown message type.");
            }
        }
        return ProtoStatus.Next;
    }

    private void encodePacket(ProtoContext context, NTPPacket packet, ProtoSndQueue<ByteBuf> dst) {
        int length = 48;
        if (packet.getVersion() == 4 && packet.getExtensionFields() != null) {
            for (NTPField field : packet.getExtensionFields()) {
                length += field.getLength();
            }
        }
        length += this.evalLength(packet.getAuthenticator());
        ByteBuf buf = context.byteBufAllocator().buffer(length);

        byte b0 = 0;
        b0 |= (packet.getLeapIndicator() & 0x3) << 6;
        b0 |= (packet.getVersion() & 0x7) << 3;
        b0 |= (packet.getNtpMode().getMode() & 0x7);
        buf.writeByte(b0);

        buf.writeByte((byte) packet.getStratum());
        buf.writeByte((byte) packet.getPollInterval());
        buf.writeByte(packet.getPrecision());

        buf.writeInt32((int) packet.getRootDelay());
        buf.writeInt32(packet.getRootDispersion());
        buf.writeInt32(packet.getReferenceID());

        buf.writeInt64(packet.getReferenceTimestamp());
        buf.writeInt64(packet.getOriginateTimestamp());
        buf.writeInt64(packet.getReceiveTimestamp());
        buf.writeInt64(packet.getTransmitTimestamp());

        if (packet.getVersion() == 4 && packet.getExtensionFields() != null) {
            for (NTPField field : packet.getExtensionFields()) {
                buf.writeInt16(field.getFieldType());
                buf.writeInt16((short) field.getLength());
                if (field.getValue() != null) {
                    buf.writeBytes(field.getValue());
                }
                // Padding
                int padding = field.getLength() - 4 - (field.getValue() == null ? 0 : field.getValue().length);
                if (padding > 0) {
                    buf.writeBytes(new byte[padding]);
                }
            }
        }

        if (packet.getAuthenticator() != null) {
            buf.writeBytes(packet.getAuthenticator());
        }

        buf.markWriter();
        dst.offerMessage(buf);
    }

    private void encodePacket(ProtoContext context, NTPControlPacket packet, ProtoSndQueue<ByteBuf> dst) {
        int length = 12 + this.evalLength(packet.getData()) + this.evalLength(packet.getAuthenticator());
        ByteBuf buf = context.byteBufAllocator().buffer(length);

        byte b0 = 0;
        b0 |= (packet.getLeapIndicator() & 0x3) << 6;
        b0 |= (packet.getVersion() & 0x7) << 3;
        b0 |= (6 & 0x7); // Mode 6
        buf.writeByte(b0);

        byte b1 = 0;
        b1 |= (packet.getRem() & 0x7) << 5;
        b1 |= (packet.getOp() & 0x1F);
        buf.writeByte(b1);

        buf.writeInt16((short) packet.getSequence());
        buf.writeInt16((short) packet.getStatus());
        buf.writeInt16((short) packet.getAssociationID());
        buf.writeInt16((short) packet.getOffset());
        buf.writeInt16((short) packet.getCount());

        if (packet.getData() != null) {
            buf.writeBytes(packet.getData());
        }

        if (packet.getAuthenticator() != null) {
            buf.writeBytes(packet.getAuthenticator());
        }

        buf.markWriter();
        dst.offerMessage(buf);
    }

    private int evalLength(byte[] data) {
        return data == null ? 0 : data.length;
    }
}
