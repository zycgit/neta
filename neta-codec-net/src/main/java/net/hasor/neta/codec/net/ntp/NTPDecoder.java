package net.hasor.neta.codec.net.ntp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

public class NTPDecoder implements ProtoHandler<ByteBuf, NTPMessage> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<NTPMessage> dst) {
        while (src.hasMore()) {
            ByteBuf buf = src.peekMessage();
            if (buf == null) {
                src.skipMessage(1);
                continue;
            }

            if (buf.readableBytes() == 0) {
                src.skipMessage(1);
                continue;
            }

            boolean decodedAtLeastOne = false;
            while (buf.readableBytes() > 0) {
                buf.markReader();

                if (buf.readableBytes() < 1) {
                    buf.resetReader();
                    if (decodedAtLeastOne) {
                        return ProtoStatus.Next;
                    }
                    return ProtoStatus.Next;
                }

                byte b0 = buf.getByte(buf.readerIndex());
                int mode = b0 & 0x7;
                int minLen = (mode == 6) ? 12 : 48;

                if (buf.readableBytes() < minLen) {
                    buf.resetReader();
                    return ProtoStatus.Next;
                }

                try {
                    if (mode == 6) {
                        NTPControlPacket packet = readCtlPacket(buf);
                        if (packet == null) {
                            buf.resetReader();
                            return ProtoStatus.Next;
                        }
                        dst.offerMessage(packet);
                        decodedAtLeastOne = true;
                    } else if (mode >= 0 && mode <= 5) {
                        NTPPacket packet = readPacket(buf);
                        if (packet == null) {
                            buf.resetReader();
                            return ProtoStatus.Next;
                        }
                        dst.offerMessage(packet);
                        decodedAtLeastOne = true;
                    } else {
                        buf.readByte();
                    }
                } catch (Exception e) {
                    buf.resetReader();
                    src.skipMessage(1);
                    throw e;
                }
            }

            src.skipMessage(1);
        }
        return ProtoStatus.Next;
    }

    private NTPPacket readPacket(ByteBuf buf) {
        if (buf.readableBytes() < 48) {
            return null;
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

        // Try to parse Extension Fields (Only for V4)
        if (packet.getVersion() == 4) {
            while (buf.readableBytes() >= 4) {
                buf.markReader();
                int type = buf.readUInt16();
                int length = buf.readUInt16();

                // Validation: Length must be at least 4, multiple of 4, and fit in remaining bytes
                // Note: If length > remaining, it might be a partial packet.
                // But we don't know if it's a partial packet or an Authenticator.
                // If we assume "Multiple Packets", we should be careful.

                if (length < 4 || (length % 4) != 0) {
                    // Invalid length, treat as Authenticator or End
                    buf.resetReader();
                    break;
                }

                if (length > (buf.readableBytes() + 4)) {
                    // Length exceeds available data.
                    // This could be a partial packet.
                    // We should return null to wait for more data?
                    // BUT, if it was actually an Authenticator (which doesn't have length field),
                    // interpreting it as extension length is wrong.
                    // Given the ambiguity, we assume:
                    // If it looks like a valid extension (valid type/len), we wait for it.
                    // If not, we stop.

                    // For now, let's assume if it exceeds, it's NOT an extension (or we can't handle it yet).
                    // But if we return null, we stall.
                    // Let's stick to: If it exceeds, we assume it's NOT an extension (so we break).
                    buf.resetReader();
                    break;
                }

                // It's a valid extension field
                byte[] value = null;
                int valueLen = length - 4;
                if (valueLen > 0) {
                    value = new byte[valueLen];
                    buf.readBytes(value);
                }
                packet.addExtensionField(new NTPField((short) type, value));
            }
        }

        // Authenticator Handling
        // If we have remaining bytes, are they Authenticator or Next Packet?
        // We assume: If remaining < 48, it MUST be Authenticator (too small for header).
        // If remaining >= 48, it COULD be next packet.
        // We check if it looks like a header? (LI/VN/Mode)
        // This is heuristic.

        if (buf.readableBytes() > 0) {
            // Heuristic: If remaining bytes >= 48, check if it looks like a valid header.
            // If so, don't consume.
            if (buf.readableBytes() >= 48) {
                buf.markReader();
                byte nextB0 = buf.readByte();
                buf.resetReader();

                int nextMode = nextB0 & 0x7;
                int nextVer = (nextB0 >> 3) & 0x7;

                // Valid Mode: 0-7. Valid Ver: 1-4.
                // If it looks valid, we assume it's the next packet and STOP consuming.
                if (nextVer >= 1 && nextVer <= 4 && nextMode >= 0 && nextMode <= 7) {
                    // It looks like a header. Stop.
                    buf.markReader();
                    return packet;
                }
            }

            // Otherwise, consume as Authenticator
            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

        buf.markReader();
        return packet;
    }

    private NTPControlPacket readCtlPacket(ByteBuf buf) {
        if (buf.readableBytes() < 12) {
            return null;
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
        if (dataLen > 0) {
            if (buf.readableBytes() < dataLen) {
                return null; // Wait for data
            }
            byte[] data = new byte[dataLen];
            buf.readBytes(data);
            packet.setData(data);
        }

        if (buf.readableBytes() > 0) {
            // Same heuristic for Control Packet?
            // Control packet is usually standalone.
            // But let's apply same logic.
            if (buf.readableBytes() >= 12) { // Min control packet size
                buf.markReader();
                byte nextB0 = buf.readByte();
                buf.resetReader();
                int nextMode = nextB0 & 0x7;
                if (nextMode == 6 || (nextMode >= 0 && nextMode <= 5)) {
                    buf.markReader();
                    return packet;
                }
            }

            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

        buf.markReader();
        return packet;
    }
}
