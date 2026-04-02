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
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Http2UpgradeRoutingIntegrationTest extends AbstractHttpTest {

    private static ByteBuf toBody(String text) {
        return ByteBuf.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testHttp11FallbackRouting() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            startServer(neta, port);
            Thread.sleep(300);

            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/plain").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            assertEquals(200, conn.getResponseCode());
            assertEquals("http/1.1:/plain", readAll(conn.getInputStream()));
            conn.disconnect();
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testH2cUpgradeAndFollowUpStream() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            startServer(neta, port);
            Thread.sleep(300);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                out.write(buildUpgradeRequest(port).getBytes(StandardCharsets.US_ASCII));
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

                out.write(buildHeadersFrame(port, 3, "/after"));
                out.flush();

                H2Response secondResponse = readResponse(in, 3);
                assertEquals("200", secondResponse.status);
                assertEquals("upgraded:/after", secondResponse.body);
            }
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testH2cUpgradeInterleavedRequestStreams() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            startServer(neta, port);
            Thread.sleep(300);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                out.write(buildUpgradeRequest(port).getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String responseHead = readHttp1Head(in);
                assertTrue(responseHead.startsWith("HTTP/1.1 101"));

                out.write(CLIENT_PREFACE);
                out.write(buildFrame(Http2FrameType.SETTINGS, Http2Flags.NONE, 0, new byte[0]));
                out.flush();

                Frame serverSettings = readFrame(in);
                assertEquals(Http2FrameType.SETTINGS, serverSettings.type);
                out.write(buildFrame(Http2FrameType.SETTINGS, Http2Flags.ACK, 0, new byte[0]));
                out.flush();

                H2Response upgradeResponse = readResponse(in, 1);
                assertEquals("200", upgradeResponse.status);
                assertEquals("upgraded:/upgrade", upgradeResponse.body);

                out.write(buildHeadersFrame(port, 3, HttpMethod.POST, "/alpha", false, 4));
                out.write(buildHeadersFrame(port, 5, HttpMethod.POST, "/beta", false, 2));
                out.write(buildDataFrame(3, false, "AB"));
                out.write(buildDataFrame(5, true, "12"));
                out.write(buildDataFrame(3, true, "CD"));
                out.flush();

                Map<Integer, H2Response> responses = readResponses(in, 2);
                assertEquals("200", responses.get(3).status);
                assertEquals("200", responses.get(5).status);
                assertEquals("upgraded:/alpha:ABCD", responses.get(3).body);
                assertEquals("upgraded:/beta:12", responses.get(5).body);
            }
        } finally {
            neta.shutdown();
        }
    }

    private void startServer(NetManager neta, int port) throws Exception {
        ProtoInitializer serverProto = ProtoHelper.standard()//
                .nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {//
                    routing.branch(HttpRouteKey.BRANCH_H2, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                            .nextDuplex("h2-message", new Http2ObjectDuplexe(true))//
                            .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
                                Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                ProtoPartitionControl control = partition.control();
                                partition.policy(policy).byInitializer(partitionCtx -> {
                                    partitionCtx.addLast("h2-stream-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy));
                                    partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576));
                                }).byDefault(partitionCtx -> partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy)));
                            })//
                            .nextDecoder("h2-handler", new InlineDispatchHandler()));
                    //
                    routing.branch(HttpRouteKey.BRANCH_H2C, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("h2c-upgrade", new H2cUpgradeServerDuplexe())//
                            .nextPartition("h2c-stream", new Http2ObjectPartitionSelector(), partition -> {
                                Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                ProtoPartitionControl control = partition.control();
                                partition.policy(policy).byInitializer(partitionCtx -> {
                                    partitionCtx.addLast("h2c-stream-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy));
                                    partitionCtx.addLast("h2c-aggregator", new HttpServerDuplexeAggregator(1048576));
                                }).byDefault(partitionCtx -> partitionCtx.addLast("h2c-control-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy)));
                            })//
                            .nextDecoder("h2c-handler", new InlineDispatchHandler()));
                    //
                    routing.branch(HttpRouteKey.BRANCH_H1, (ProtoBuilder<ByteBuf, ByteBuf> branch) -> branch//
                            .nextDuplex("http-codec", new HttpServerDuplexe())//
                            .nextDecoder("http-aggregator", new HttpRequestAggregator(1048576))//
                            .nextDecoder("http-handler", new InlineDispatchHandler()));
                }).build();

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
    }

    private String buildUpgradeRequest(int port) {
        byte[] settings = new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 };
        String encodedSettings = Base64.getUrlEncoder().withoutPadding().encodeToString(settings);
        return "GET /upgrade HTTP/1.1\r\n"//
                + "Host: 127.0.0.1:" + port + "\r\n"//
                + "Connection: Upgrade, HTTP2-Settings\r\n"//
                + "Upgrade: h2c\r\n"//
                + "HTTP2-Settings: " + encodedSettings + "\r\n"//
                + "\r\n";
    }

    private byte[] buildHeadersFrame(int port, int streamId, String path) {
        return buildHeadersFrame(port, streamId, HttpMethod.GET, path, true, -1);
    }

    private byte[] buildHeadersFrame(int port, int streamId, HttpMethod method, String path, boolean endStream, int contentLength) {
        HpackEncoder encoder = new HpackEncoder(4096);
        encoder.beginEncode();
        encoder.encodeHeaderDirect(HttpHeaderNames.PSEUDO_METHOD, method.name());
        encoder.encodeHeaderDirect(HttpHeaderNames.PSEUDO_PATH, path);
        encoder.encodeHeaderDirect(HttpHeaderNames.PSEUDO_SCHEME, "http");
        encoder.encodeHeaderDirect(HttpHeaderNames.PSEUDO_AUTHORITY, "127.0.0.1:" + port);
        if (contentLength >= 0) {
            encoder.encodeHeaderDirect(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        }
        byte[] headerBlock = new byte[encoder.encodedLength()];
        System.arraycopy(encoder.encodedBuffer(), 0, headerBlock, 0, headerBlock.length);
        int flags = Http2Flags.END_HEADERS | (endStream ? Http2Flags.END_STREAM : Http2Flags.NONE);
        return buildFrame(Http2FrameType.HEADERS, flags, streamId, headerBlock);
    }

    private byte[] buildDataFrame(int streamId, boolean endStream, String body) {
        return buildFrame(Http2FrameType.DATA, endStream ? Http2Flags.END_STREAM : Http2Flags.NONE, streamId, body.getBytes(StandardCharsets.UTF_8));
    }

    private H2Response readResponse(InputStream in, int streamId) throws IOException {
        return readResponses(in, 1, streamId).get(streamId);
    }

    private Map<Integer, H2Response> readResponses(InputStream in, int expectedCount) throws IOException {
        return readResponses(in, expectedCount, null);
    }

    private Map<Integer, H2Response> readResponses(InputStream in, int expectedCount, Integer targetStreamId) throws IOException {
        HpackDecoder decoder = new HpackDecoder(4096, 8192);
        Map<Integer, H2Response> responses = new LinkedHashMap<>();
        while (countCompleted(responses) < expectedCount) {
            Frame frame = readFrame(in);
            if (frame.streamId == 0) {
                continue;
            }
            if (targetStreamId != null && frame.streamId != targetStreamId.intValue()) {
                continue;
            }
            H2Response response = responses.get(frame.streamId);
            if (response == null) {
                response = new H2Response();
                responses.put(frame.streamId, response);
            }
            if (frame.type == Http2FrameType.HEADERS) {
                response.status = decoder.decode(frame.payload, 0, frame.payload.length).getString(HttpHeaderNames.PSEUDO_STATUS);
                response.endStream = Http2Flags.endStream(frame.flags);
            } else if (frame.type == Http2FrameType.DATA) {
                response.body += new String(frame.payload, StandardCharsets.UTF_8);
                response.endStream = Http2Flags.endStream(frame.flags);
            }
        }
        return responses;
    }

    private int countCompleted(Map<Integer, H2Response> responses) {
        int count = 0;
        for (H2Response response : responses.values()) {
            if (response.endStream) {
                count++;
            }
        }
        return count;
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
                String requestBody = request.content() == null ? "" : Http2UpgradeRoutingIntegrationTest.text(request.content().retain());
                if (!requestBody.isEmpty()) {
                    bodyText = bodyText + ":" + requestBody;
                }
                ByteBuf body = toBody(bodyText);
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpStatus.OK, body);
                response.setHeader("content-length", String.valueOf(body.readableBytes()));
                response.streamId(request.streamId());
                context.sendData(response).get();
            }
            return ProtoStatus.Next;
        }
    }
}