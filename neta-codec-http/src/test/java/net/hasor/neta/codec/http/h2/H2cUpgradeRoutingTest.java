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
package net.hasor.neta.codec.http.h2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class H2cUpgradeRoutingTest {
    private NetManager neta;
    private int        port;

    @Before
    public void setUp() throws Exception {
        this.port = findFreePort();
        this.neta = new NetManager();
        startServer();
        Thread.sleep(300);
    }

    @After
    public void tearDown() throws IOException {
        if (this.neta != null) {
            this.neta.shutdown();
        }
    }

    @Test
    public void testHttp11FallbackRouting() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + this.port + "/plain").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        assertEquals("http/1.1:/plain", readAll(conn.getInputStream()));
        conn.disconnect();
    }

    @Test
    public void testH2cUpgradeAndFollowUpStream() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", this.port)) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(buildUpgradeRequest().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String responseHead = readHttp1Head(in);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"));
            assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

            out.write(CLIENT_PREFACE);
            out.write(buildFrame(Http2FrameType.SETTINGS, Http2Flags.NONE, 0, new byte[0]));
            out.flush();

            Frame serverSettings = readFrame(in);
            assertEquals(Http2FrameType.SETTINGS, serverSettings.type);
            assertEquals(0, serverSettings.streamId);

            out.write(buildFrame(Http2FrameType.SETTINGS, Http2Flags.ACK, 0, new byte[0]));
            out.flush();

            H2Response upgradeResponse = readResponse(in, 1);
            assertEquals("200", upgradeResponse.status);
            assertEquals("upgraded:/upgrade", upgradeResponse.body);

            out.write(buildHeadersFrame(3, "/after"));
            out.flush();

            H2Response secondResponse = readResponse(in, 3);
            assertEquals("200", secondResponse.status);
            assertEquals("upgraded:/after", secondResponse.body);
        }
    }

    private void startServer() throws Exception {
        ProtoInitializer serverProto = ProtoHelper.standard()//
                .nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {//
                    routing.branch(HttpRouteKey.BRANCH_H2, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("h2-codec", new Http2ServerDuplexe())//
                            .nextDecoder("h2-http-dec", new Http2MessageToHttpDecoder())//
                            .nextEncoder("h2-http-enc", new Http2HttpToMessageEncoder(true))//
                            .nextDecoder("h2-aggregator", new HttpRequestAggregator(1048576))//
                            .nextDecoder("h2-handler", new InlineDispatchHandler()));//
                    //
                    routing.branch(HttpRouteKey.BRANCH_H2C, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("h2c-upgrade", new H2cUpgradeServerDuplexe())//
                            .nextDecoder("h2c-aggregator", new HttpRequestAggregator(1048576))//
                            .nextDecoder("h2c-handler", new InlineDispatchHandler()));
                    //
                    routing.branch(HttpRouteKey.BRANCH_H1, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("http-codec", new HttpServerDuplexe())//
                            .nextDecoder("http-aggregator", new HttpRequestAggregator(1048576))//
                            .nextDecoder("http-handler", new InlineDispatchHandler()));
                }).build();

        this.neta.bind(new InetSocketAddress("0.0.0.0", this.port), serverProto, SoConfig.TCP());
    }

    private String buildUpgradeRequest() {
        byte[] settings = new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 };
        String encodedSettings = Base64.getUrlEncoder().withoutPadding().encodeToString(settings);
        return "GET /upgrade HTTP/1.1\r\n"//
                + "Host: 127.0.0.1:" + this.port + "\r\n"//
                + "Connection: Upgrade, HTTP2-Settings\r\n"//
                + "Upgrade: h2c\r\n"//
                + "HTTP2-Settings: " + encodedSettings + "\r\n"//
                + "\r\n";
    }

    private byte[] buildHeadersFrame(int streamId, String path) {
        HpackEncoder encoder = new HpackEncoder(4096);
        encoder.beginEncode();
        encoder.encodeHeaderDirect(":method", HttpMethod.GET.name());
        encoder.encodeHeaderDirect(":path", path);
        encoder.encodeHeaderDirect(":scheme", "http");
        encoder.encodeHeaderDirect(":authority", "127.0.0.1:" + this.port);
        byte[] headerBlock = new byte[encoder.encodedLength()];
        System.arraycopy(encoder.encodedBuffer(), 0, headerBlock, 0, headerBlock.length);
        return buildFrame(Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, streamId, headerBlock);
    }

    private H2Response readResponse(InputStream in, int streamId) throws IOException {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        H2Response response = new H2Response();
        while (!response.endStream) {
            Frame frame = readFrame(in);
            if (frame.streamId == 0) {
                continue;
            }
            if (frame.streamId != streamId) {
                continue;
            }
            if (frame.type == Http2FrameType.HEADERS) {
                response.status = decoder.decode(frame.payload, 0, frame.payload.length).getString(":status");
                response.endStream = Http2Flags.endStream(frame.flags);
            } else if (frame.type == Http2FrameType.DATA) {
                response.body += new String(frame.payload, StandardCharsets.UTF_8);
                response.endStream = Http2Flags.endStream(frame.flags);
            }
        }
        return response;
    }

    private Frame readFrame(InputStream in) throws IOException {
        byte[] header = readExact(in, 9);
        int payloadLength = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
        int type = header[3] & 0xFF;
        int flags = header[4] & 0xFF;
        int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
        byte[] payload = readExact(in, payloadLength);
        return new Frame(type, flags, streamId, payload);
    }

    private static byte[] buildFrame(int type, int flags, int streamId, byte[] payload) {
        byte[] frame = new byte[9 + payload.length];
        int length = payload.length;
        frame[0] = (byte) ((length >> 16) & 0xFF);
        frame[1] = (byte) ((length >> 8) & 0xFF);
        frame[2] = (byte) (length & 0xFF);
        frame[3] = (byte) type;
        frame[4] = (byte) flags;
        frame[5] = (byte) ((streamId >> 24) & 0x7F);
        frame[6] = (byte) ((streamId >> 16) & 0xFF);
        frame[7] = (byte) ((streamId >> 8) & 0xFF);
        frame[8] = (byte) (streamId & 0xFF);
        System.arraycopy(payload, 0, frame, 9, payload.length);
        return frame;
    }

    private static String readHttp1Head(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int ch = in.read();
            if (ch < 0) {
                throw new IOException("unexpected EOF while reading HTTP/1.1 response head");
            }
            out.write(ch);
            if ((matched == 0 || matched == 2) && ch == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && ch == '\n') {
                matched++;
            } else if (ch == '\r') {
                matched = 1;
            } else {
                matched = 0;
            }
        }
        return out.toString(StandardCharsets.US_ASCII.name());
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("unexpected EOF while reading " + length + " bytes");
            }
            offset += read;
        }
        return data;
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static ByteBuf toBody(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() + 16);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return buf;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static class H2Response {
        private String  status;
        private String  body      = "";
        private boolean endStream = false;
    }

    private static class Frame {
        private final int    type;
        private final int    flags;
        private final int    streamId;
        private final byte[] payload;

        private Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }
    }

    @SuppressWarnings("rawtypes")
    private static class InlineDispatchHandler implements ProtoHandler<HttpObject, Object> {
        private final AtomicInteger requests = new AtomicInteger();

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                Object msg = ((ProtoRcvQueue) src).takeMessage();
                if (!(msg instanceof FullHttpRequest)) {
                    continue;
                }
                FullHttpRequest request = (FullHttpRequest) msg;
                String bodyText = request.protocolVersion().text().toLowerCase() + ":" + request.uri();
                if (HttpVersion.HTTP_2_0.equals(request.protocolVersion())) {
                    bodyText = "upgraded:" + request.uri();
                }
                ByteBuf body = toBody(bodyText);
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpStatus.OK, body);
                response.setHeader("content-length", String.valueOf(body.readableBytes()));
                response.streamId(request.streamId());
                context.sendData(response).get();
                this.requests.incrementAndGet();
            }
            return ProtoStatus.Next;
        }
    }
}