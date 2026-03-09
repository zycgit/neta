package net.hasor.neta.codec.net.ntp;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import org.junit.Test;

public class NTPDuplexerTest {
    @Test
    public void testDuplexer() throws Throwable {
        NetManager neta = new NetManager();

        // Server: Uses NTPDuplexer
        BlockingQueue<NTPMessage> serverReceived = new LinkedBlockingQueue<>();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLast(new NTPDuplexer());
        }, VrtSoConfig.asServer());
        server.subscribe(SubscribeMode.SYNC, d -> {
            if (d.getData() instanceof NTPMessage) {
                serverReceived.offer((NTPMessage) d.getData());
            }
        });

        // Client: Uses NTPDuplexer
        BlockingQueue<NTPMessage> clientReceived = new LinkedBlockingQueue<>();
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ctx.addLast(new NTPDuplexer());
        }, VrtSoConfig.asClient());
        client.subscribe(SubscribeMode.SYNC, d -> {
            if (d.getData() instanceof NTPMessage) {
                clientReceived.offer((NTPMessage) d.getData());
            }
        });

        // Link them
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        // 1. Client sends NTPPacket to Server
        NTPPacket request = new NTPPacket();
        request.setVersion((byte) 4);
        request.setNtpMode(NTPMode.CLIENT);
        request.setTransmitTimestamp(System.currentTimeMillis());

        client.sendData(request).get();

        // Verify Server received
        NTPMessage msg1 = serverReceived.poll(1, TimeUnit.SECONDS);
        assert msg1 instanceof NTPPacket;
        NTPPacket receivedRequest = (NTPPacket) msg1;
        assert receivedRequest.getVersion() == 4;
        assert receivedRequest.getNtpMode() == NTPMode.CLIENT;
        assert receivedRequest.getTransmitTimestamp() == request.getTransmitTimestamp();

        // 2. Server sends NTPControlPacket to Client
        NTPControlPacket response = new NTPControlPacket();
        response.setVersion((byte) 3);
        response.setOperationCode((byte) 1);
        response.setSequence(100);

        server.sendData(response).get();

        // Verify Client received
        NTPMessage msg2 = clientReceived.poll(1, TimeUnit.SECONDS);
        assert msg2 instanceof NTPControlPacket;
        NTPControlPacket receivedResponse = (NTPControlPacket) msg2;
        assert receivedResponse.getVersion() == 3;
        assert receivedResponse.getOperationCode() == 1;
        assert receivedResponse.getSequence() == 100;

        neta.shutdown();
    }
}
