package net.hasor.neta.codec.net.ntp;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.ProtoStatus;

public class NTPEncoder implements ProtoHandler<NTPMessage, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<NTPMessage> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        while (src.hasMore()) {
            NTPMessage message = src.takeMessage();
            if (message instanceof NTPPacket) {
                encodeNTPPacket(context, (NTPPacket) message, dst);
            } else if (message instanceof NTPControlPacket) {
                encodeControlPacket(context, (NTPControlPacket) message, dst);
            } else {
                throw new IllegalStateException("unknown message type.");
            }
        }
        return ProtoStatus.Next;
    }

    private void encodeNTPPacket(ProtoContext context, NTPPacket packet, ProtoSndQueue<ByteBuf> dst) {
        int length = 48 + (packet.getAuthenticator() == null ? 0 : packet.getAuthenticator().length);
        ByteBuf buf = context.getSoContext().getByteBufAllocator().buffer(length);

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

        if (packet.getAuthenticator() != null) {
            buf.writeBytes(packet.getAuthenticator());
        }

        buf.markWriter();
        dst.offerMessage(buf);
    }

    private void encodeControlPacket(ProtoContext context, NTPControlPacket packet, ProtoSndQueue<ByteBuf> dst) {
        int length = 12 + (packet.getData() == null ? 0 : packet.getData().length) + (packet.getAuthenticator() == null ? 0 : packet.getAuthenticator().length);
        ByteBuf buf = context.getSoContext().getByteBufAllocator().buffer(length);

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
}
