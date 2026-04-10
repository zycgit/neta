package net.hasor.neta.channel.transport.sctp;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import org.junit.Test;

public class SctpSimpleTest {
    @Test
    public void testSimpleSctp() throws Throwable {
        try {
            SctpChannel.open().close();
        } catch (Throwable t) {
            System.out.println("SCTP not supported: " + t.getMessage());
            return;
        }

        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 18888);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch serverStarted = new CountDownLatch(1);

        new Thread(() -> {
            try {
                SctpServerChannel server = SctpServerChannel.open();
                server.bind(address);
                System.out.println("Server bound at " + address);
                serverStarted.countDown();

                SctpChannel client = server.accept();
                System.out.println("Server accepted connection");

                ByteBuffer buf = ByteBuffer.allocate(1024);
                client.receive(buf, null, null);
                System.out.println("Server received data");
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        serverStarted.await(5, TimeUnit.SECONDS);

        SctpChannel client = SctpChannel.open();
        System.out.println("Client connecting...");
        boolean connected = client.connect(address);
        System.out.println("Client connected: " + connected);

        System.out.println("Client sending...");
        client.send(ByteBuffer.wrap("hello".getBytes()), com.sun.nio.sctp.MessageInfo.createOutgoing(null, 0));
        System.out.println("Client sent");

        boolean received = latch.await(5, TimeUnit.SECONDS);
        if (!received) {
            throw new RuntimeException("Test timed out");
        }
    }
}
