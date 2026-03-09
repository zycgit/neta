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
 * Decodes raw {@link ByteBuf} bytes into {@link NTPMessage} objects.
 * <p>
 * This handler parses the NTP wire format (RFC 5905) into either
 * {@link NTPPacket} (standard client/server mode) or
 * {@link NTPControlPacket} (control-message mode).
 * <p>
 * A standard NTP packet is exactly 48 bytes (without optional extension fields
 * or MAC). The decoder reads one message per {@code ByteBuf} item from the queue.
 * If the buffer is empty or null it is skipped.
 * <p>
 * <b>Typical pipeline placement:</b>
 * <pre>
 *   ctx.addLast("ntp", new NTPDuplexer());
 *   // or individually:
 *   ctx.addLastDecoder("ntpDecoder", new NTPDecoder());
 *   ctx.addLastEncoder("ntpEncoder", new NTPEncoder());
 * </pre>
 * <b>Applicable transports:</b> UDP (RFC 5905 §2 mandates UDP port 123).
 * @author 赵永春 (zyc@hasor.net)
 * @see NTPEncoder
 * @see NTPDuplexer
 * @see NTPMessage
 */
public class NTPDecoder implements ProtoHandler<ByteBuf, NTPMessage> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<NTPMessage> dst) {
        while (src.hasMore()) {
            ByteBuf buf = src.peekMessage();
            if (buf == null || buf.readableBytes() == 0) {
                src.skipMessage(1);
                continue;
            }

            boolean decodedAtLeastOne = false;
            while (buf.readableBytes() > 0) {
                buf.markReader();

                if (buf.readableBytes() < 1) {
                    buf.resetReader();
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
                        // Unknown mode, skip one byte
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
            readExtensionFields(buf, packet);
        }

        if (buf.readableBytes() > 0) {
            // Heuristic: If remaining bytes >= 48, check if it looks like a valid header.
            if (buf.readableBytes() >= 48) {
                buf.markReader();
                byte nextB0 = buf.readByte();
                buf.resetReader();

                int nextMode = nextB0 & 0x7;
                int nextVer = (nextB0 >> 3) & 0x7;

                if (nextVer >= 1 && nextVer <= 4 && nextMode >= 0 && nextMode <= 7) {
                    // It looks like a header. Stop.
                    return packet;
                }
            }

            // Otherwise, consume as Authenticator
            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

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
        int rem = (b1 >> 5) & 0x7;
        packet.setResponseBit((byte) ((rem >> 2) & 0x1));
        packet.setErrorBit((byte) ((rem >> 1) & 0x1));
        packet.setMoreBit((byte) (rem & 0x1));
        packet.setOperationCode((byte) (b1 & 0x1F));

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
            if (buf.readableBytes() >= 12) {
                buf.markReader();
                byte nextB0 = buf.readByte();
                buf.resetReader();
                int nextMode = nextB0 & 0x7;
                if (nextMode == 6 || (nextMode >= 0 && nextMode <= 5)) {
                    return packet;
                }
            }

            byte[] auth = new byte[buf.readableBytes()];
            buf.readBytes(auth);
            packet.setAuthenticator(auth);
        }

        return packet;
    }

    private void readExtensionFields(ByteBuf buf, NTPPacket packet) {
        while (buf.readableBytes() >= 4) {
            buf.markReader();
            int type = buf.readUInt16();
            int length = buf.readUInt16();

            if (length < 4 || (length % 4) != 0) {
                buf.resetReader();
                break;
            }

            if (length > (buf.readableBytes() + 4)) {
                buf.resetReader();
                break;
            }

            byte[] value = null;
            int valueLen = length - 4;
            if (valueLen > 0) {
                value = new byte[valueLen];
                buf.readBytes(value);
            }
            packet.addExtensionField(new NTPField((short) type, value));
        }
    }
}
