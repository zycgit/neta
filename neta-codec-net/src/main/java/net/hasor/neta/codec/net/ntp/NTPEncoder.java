/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.net.ntp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * Encodes {@link NTPMessage} objects into raw {@link ByteBuf} bytes.
 * <p>
 * This handler serialises either {@link NTPPacket} (standard client/server)
 * or {@link NTPControlPacket} (control message) into the NTP wire format
 * defined in RFC 5905. The standard packet is always written as 48 bytes.
 * <p>
 * <b>Typical pipeline placement:</b>
 * <pre>
 *   ctx.addLastEncoder("ntpEncoder", new NTPEncoder());
 * </pre>
 * <b>Applicable transports:</b> UDP (RFC 5905 §2 mandates UDP port 123).
 * @author 赵永春 (zyc@hasor.net)
 * @see NTPDecoder
 * @see NTPDuplexer
 * @see NTPMessage
 */
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
        int rem = 0;
        rem |= (packet.getResponseBit() & 0x1) << 2;
        rem |= (packet.getErrorBit() & 0x1) << 1;
        rem |= (packet.getMoreBit() & 0x1);

        b1 |= (rem & 0x7) << 5;
        b1 |= (packet.getOperationCode() & 0x1F);
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
