package net.hasor.neta.codec.net.ntp;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.udp.UdpSoConfig;
import org.junit.Test;

public class NTPRealServerTest {

    @Test
    public void testGetTimeFromAliYun() throws Throwable {
        // 1. Setup NetManager
        NetManager neta = new NetManager();

        // 2. Define Protocol Stack (Decoder -> Encoder)
        ProtoInitializer initializer = ctx -> {
            ctx.addLastDecoder(new NTPDecoder());
            ctx.addLastEncoder(new NTPEncoder());
        };

        // 3. Connect to NTP Server (UDP)
        // ntp.aliyun.com = 203.107.6.88 (One of the IPs)
        // Using domain name resolution might be better, but InetSocketAddress handles it.
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

        // Set Transmit Timestamp to current time (client time)
        long clientTime = System.currentTimeMillis();
        request.setTransmitTimestamp(toNtpTime(clientTime));

        channel.sendData(request);

        // 6. Wait for response (Max 5 seconds)
        NTPPacket response = resultFuture.get(5, TimeUnit.SECONDS);

        // 7. Process and Print Time
        long transmitTimestamp = response.getTransmitTimestamp();
        long javaTime = toJavaTime(transmitTimestamp);
        Date date = new Date(javaTime);

        SimpleDateFormat bjFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        bjFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        SimpleDateFormat gmtFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        gmtFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        System.out.println("--------------------------------------------------");
        System.out.println("NTP Server: ntp.aliyun.com");
        System.out.println("Beijing Time: " + bjFormat.format(date));
        System.out.println("GMT Time:     " + gmtFormat.format(date));
        System.out.println("--------------------------------------------------");

        neta.shutdown();
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
