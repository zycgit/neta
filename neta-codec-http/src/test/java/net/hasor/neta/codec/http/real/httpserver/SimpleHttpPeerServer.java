package net.hasor.neta.codec.http.real.httpserver;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SimpleHttpPeerServer {
    private final ServerSocket               serverSocket;
    private final RawHttpHandler             handler;
    private final CountDownLatch             startLatch = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure    = new AtomicReference<Throwable>();
    private final Thread                     worker;

    public static SimpleHttpPeerServer start(int port, RawHttpHandler handler) throws IOException, InterruptedException {
        SimpleHttpPeerServer server = new SimpleHttpPeerServer(port, handler);
        server.start();
        return server;
    }

    public SimpleHttpPeerServer(int port, RawHttpHandler handler) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        this.handler = handler;
        this.worker = new Thread(() -> {
            this.startLatch.countDown();
            try (Socket socket = this.serverSocket.accept()) {
                RawHttpRequest request = readRequest(socket.getInputStream());
                RawHttpResponse response = this.handler.handle(request);
                writeResponse(socket.getOutputStream(), response);
            } catch (Throwable e) {
                this.failure.set(e);
            } finally {
                try {
                    this.serverSocket.close();
                } catch (IOException e) {
                    this.failure.compareAndSet(null, e);
                }
            }
        }, "simple-http-peer-" + port);
        this.worker.setDaemon(true);
    }

    public void close() throws InterruptedException {
        this.worker.join(3000);
        if (this.failure.get() != null) {
            throw new AssertionError(this.failure.get());
        }
    }

    private void start() throws InterruptedException {
        this.worker.start();
        assertTrue(this.startLatch.await(2, TimeUnit.SECONDS));
    }

    private static RawHttpRequest readRequest(InputStream inputStream) throws IOException {
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = inputStream.read();
            if (b < 0) {
                throw new IOException("unexpected end of stream while reading headers");
            }
            headerOut.write(b);
            if ((matched == 0 && b == '\r') || (matched == 2 && b == '\r')) {
                matched++;
            } else if ((matched == 1 && b == '\n') || (matched == 3 && b == '\n')) {
                matched++;
            } else {
                matched = (b == '\r') ? 1 : 0;
            }
        }

        String headerText = new String(headerOut.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\\r\\n");
        String[] requestLine = lines[0].split(" ");
        String method = requestLine[0];
        String path = requestLine[1];
        int contentLength = 0;
        String contentType = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int p = line.indexOf(':');
            if (p < 0) {
                continue;
            }
            String name = line.substring(0, p).trim();
            String value = line.substring(p + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("Content-Type")) {
                contentType = value;
            }
        }

        byte[] bodyBytes = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int len = inputStream.read(bodyBytes, offset, contentLength - offset);
            if (len < 0) {
                throw new IOException("unexpected end of stream while reading body");
            }
            offset += len;
        }
        return new RawHttpRequest(method, path, contentType, bodyBytes);
    }

    private static void writeResponse(OutputStream outputStream, RawHttpResponse response) throws IOException {
        byte[] bodyBytes = response.body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + response.statusCode + " " + response.reason + "\r\n" + "Content-Type: " + response.contentType + "\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Connection: close\r\n\r\n";
        outputStream.write(header.getBytes(StandardCharsets.US_ASCII));
        outputStream.write(bodyBytes);
        outputStream.flush();
    }
}