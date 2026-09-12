/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.net.ntp;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import org.junit.Test;

public class NTPRealServerTest {
    @Test
    public void testNTPDuplexerGetTime() throws Throwable {
        // 1. Setup NetManager
        NetManager neta = new NetManager();

        // 2. Define Protocol Stack (Duplexer)
        ProtoInitializer initializer = c -> c.addLast(new NTPDuplex());

        // 3. Connect to NTP Server (UDP)
        InetSocketAddress serverAddress = new InetSocketAddress("ntp.aliyun.com", 123);
        UdpSoConfig udpConfig = UdpSoConfig.UDP();
        udpConfig.setRcvPacketSize(1024);
        NetChannel channel = neta.connectSync(serverAddress, initializer, udpConfig);

        // 4. Subscribe to responses
        CompletableFuture<NTPPacket> resultFuture = new CompletableFuture<>();
        channel.subscribe(payload -> {
            Object data = payload.getData();
            if (data instanceof NTPPacket) {
                resultFuture.complete((NTPPacket) data);
            }
        });

        // 5. Create and Send NTP Request
        NTPPacket request = new NTPPacket();
        request.setNtpMode(NTPMode.CLIENT);
        request.setVersion((byte) 3);
        request.setLeapIndicator((byte) 0);
        request.setStratum(0);
        request.setPollInterval(0);
        request.setPrecision((byte) 0);

        // Set Transmit Timestamp to current time (client time) with OFFSET
        long fakeOffset = 3600000; // 1 Hour ahead
        long clientTime = System.currentTimeMillis() + fakeOffset;
        request.setTransmitTimestamp(toNtpTime(clientTime));

        channel.sendData(request);

        // 6. Wait for response (Max 5 seconds)
        NTPPacket response = resultFuture.get(5, TimeUnit.SECONDS);
        long responseTime = System.currentTimeMillis() + fakeOffset; // T4

        // 7. Process and Print Time
        System.out.println("--------------------------------------------------");
        System.out.println("NTP Duplexer Server: ntp.aliyun.com");
        System.out.println("Local Time (Fake): " + new Date(clientTime));
        System.out.println("Fake Offset:       " + fakeOffset + " ms");

        printTime(response, clientTime, responseTime);
        System.out.println("--------------------------------------------------");

        neta.shutdown();
    }

    @Test
    public void testNTPv3GetTime() throws Throwable {
        // 1. Setup NetManager
        NetManager neta = new NetManager();

        // 2. Define Protocol Stack (Decoder -> Encoder)
        ProtoInitializer initializer = ctx -> {
            ctx.addLastDecoder(new NTPDecoder());
            ctx.addLastEncoder(new NTPEncoder());
        };

        // 3. Connect to NTP Server (UDP)
        InetSocketAddress serverAddress = new InetSocketAddress("ntp.aliyun.com", 123);

        CompletableFuture<NTPPacket> resultFuture = new CompletableFuture<>();

        UdpSoConfig udpConfig = UdpSoConfig.UDP();
        udpConfig.setRcvPacketSize(1024);

        NetChannel channel = neta.connectSync(serverAddress, initializer, udpConfig);

        // 4. Subscribe to responses
        channel.subscribe(payload -> {
            Object data = payload.getData();
            if (data instanceof NTPPacket) {
                resultFuture.complete((NTPPacket) data);
            }
        });

        // 5. Create and Send NTP Request
        NTPPacket request = new NTPPacket();
        request.setNtpMode(NTPMode.CLIENT);
        request.setVersion((byte) 3);
        request.setLeapIndicator((byte) 0);
        request.setStratum(0);
        request.setPollInterval(0);
        request.setPrecision((byte) 0);

        // Set Transmit Timestamp to current time (client time) with OFFSET
        long fakeOffset = 3600000; // 1 Hour ahead
        long clientTime = System.currentTimeMillis() + fakeOffset;
        request.setTransmitTimestamp(toNtpTime(clientTime));

        channel.sendData(request);

        // 6. Wait for response (Max 5 seconds)
        NTPPacket response = resultFuture.get(5, TimeUnit.SECONDS);
        long responseTime = System.currentTimeMillis() + fakeOffset; // T4

        // 7. Process and Print Time
        System.out.println("--------------------------------------------------");
        System.out.println("NTP v3 Server: ntp.aliyun.com");
        System.out.println("Local Time (Fake): " + new Date(clientTime));
        System.out.println("Fake Offset:       " + fakeOffset + " ms");

        printTime(response, clientTime, responseTime);
        System.out.println("--------------------------------------------------");

        neta.shutdown();
    }

    @Test
    public void testNTPv4GetTime() throws Throwable {
        // 1. Setup NetManager
        NetManager neta = new NetManager();

        // 2. Define Protocol Stack (Decoder -> Encoder)
        ProtoInitializer initializer = ctx -> {
            ctx.addLastDecoder(new NTPDecoder());
            ctx.addLastEncoder(new NTPEncoder());
        };

        // 3. Connect to NTP Server (UDP)
        InetSocketAddress serverAddress = new InetSocketAddress("ntp.aliyun.com", 123);

        CompletableFuture<NTPPacket> resultFuture = new CompletableFuture<>();

        UdpSoConfig udpConfig = UdpSoConfig.UDP();
        udpConfig.setRcvPacketSize(1024);

        NetChannel channel = neta.connectSync(serverAddress, initializer, udpConfig);

        // 4. Subscribe to responses
        channel.subscribe(payload -> {
            Object data = payload.getData();
            if (data instanceof NTPPacket) {
                resultFuture.complete((NTPPacket) data);
            }
        });

        // 5. Create and Send NTP Request (Version 4)
        NTPPacket request = new NTPPacket();
        request.setNtpMode(NTPMode.CLIENT);
        request.setVersion((byte) 4); // Version 4
        request.setLeapIndicator((byte) 0);
        request.setStratum(0);
        request.setPollInterval(0);
        request.setPrecision((byte) 0);

        // Set Transmit Timestamp with OFFSET
        long fakeOffset = 3600000; // 1 Hour ahead
        long clientTime = System.currentTimeMillis() + fakeOffset;
        request.setTransmitTimestamp(toNtpTime(clientTime));

        channel.sendData(request);

        // 6. Wait for response
        NTPPacket response = resultFuture.get(5, TimeUnit.SECONDS);
        long responseTime = System.currentTimeMillis() + fakeOffset; // T4

        // 7. Process and Print Time
        System.out.println("--------------------------------------------------");
        System.out.println("NTP v4 Server: ntp.aliyun.com");
        System.out.println("Version:           " + response.getVersion());
        System.out.println("Local Time (Fake): " + new Date(clientTime));
        System.out.println("Fake Offset:       " + fakeOffset + " ms");

        printTime(response, clientTime, responseTime);

        List<NTPField> extensions = response.getExtensionFields();
        if (extensions != null && !extensions.isEmpty()) {
            System.out.println("Extension Fields (" + extensions.size() + "):");
            for (NTPField field : extensions) {
                System.out.println("  Type: " + field.getFieldType() + ", Length: " + field.getLength());
            }
        } else {
            System.out.println("No Extension Fields received.");
        }
        System.out.println("--------------------------------------------------");

        neta.shutdown();
    }

    private void printTime(NTPPacket response, long t1, long t4) {
        long t2 = toJavaTime(response.getReceiveTimestamp());
        long t3 = toJavaTime(response.getTransmitTimestamp());

        // NTP Offset Calculation: ((T2 - T1) + (T3 - T4)) / 2
        long offset = ((t2 - t1) + (t3 - t4)) / 2;

        // Round Trip Delay: (T4 - T1) - (T3 - T2)
        long delay = (t4 - t1) - (t3 - t2);

        System.out.println("  Originate Timestamp (T1): " + t1);
        System.out.println("  Receive Timestamp   (T2): " + t2);
        System.out.println("  Transmit Timestamp  (T3): " + t3);
        System.out.println("  Destination Timestamp(T4): " + t4);
        System.out.println("  --------------------------------");
        System.out.println("  Calculated Offset: " + offset + " ms");
        System.out.println("  Round Trip Delay:  " + delay + " ms");

        long correctedTime = t4 + offset;
        Date date = new Date(correctedTime);
        SimpleDateFormat bjFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        bjFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        System.out.println("  Corrected Time (Beijing): " + bjFormat.format(date));
    }

    // Helper: Java Time (ms) -> NTP Time (64-bit)
    private static long toNtpTime(long javaTimeMillis) {
        long offsetSeconds = 2208988800L;
        long seconds = (javaTimeMillis / 1000) + offsetSeconds;
        long fraction = ((javaTimeMillis % 1000) * 0x100000000L) / 1000;
        return (seconds << 32) | fraction;
    }

    // Helper: NTP Time (64-bit) -> Java Time (ms)
    private static long toJavaTime(long ntpTime) {
        long seconds = (ntpTime >>> 32) & 0xFFFFFFFFL;
        long fraction = ntpTime & 0xFFFFFFFFL;

        long offsetSeconds = 2208988800L;
        long javaSeconds = seconds - offsetSeconds;

        // fraction * 1000 / 2^32
        long javaMillis = (javaSeconds * 1000) + Math.round((fraction * 1000.0) / 0x100000000L);

        return javaMillis;
    }
}
