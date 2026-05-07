package net.hasor.neta.codec.http.websocket;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.ReferenceCounted;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoDuplex;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.DefaultHttpByteBuf;
import net.hasor.neta.codec.http.DefaultHttpContent;
import net.hasor.neta.codec.http.DefaultHttpRequest;
import net.hasor.neta.codec.http.DefaultHttpResponse;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.neta.codec.http.HttpResponse;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
public class WebSocketBenchmark {
    private static final class HandshakeRequestSnapshot {
        private final String requestText;
        private final int    outboundBytes;

        private HandshakeRequestSnapshot(String requestText, int outboundBytes) {
            this.requestText = requestText;
            this.outboundBytes = outboundBytes;
        }
    }

    private static final AtomicInteger ADDRESS = new AtomicInteger(300);
    private static final String        WS_URI = "ws://example.com/chat";
    private static final byte[]        MASK_KEY = new byte[] { 0x11, 0x22, 0x33, 0x44 };
    private static final int           MAX_MESSAGE_PAYLOAD = 1024 * 1024;
    private static final int           MESSAGE_FRAGMENT_SIZE = 512;
    private static final String        TEXT_FRAME = "hello websocket benchmark";
    private static final String        LARGE_TEXT_MESSAGE;
    private static final byte[]        TEXT_FRAME_BYTES = TEXT_FRAME.getBytes(StandardCharsets.UTF_8);
    private static final byte[]        BINARY_FRAME_BYTES = new byte[4096];
    private static final byte[]        MASKED_TEXT_FRAME;
    private static final byte[]        MASKED_BINARY_FRAME;
    private static final byte[][]      MASKED_FRAGMENTED_TEXT_MESSAGE;
    private NetManager                 neta;
    private VirtualPipe                framePipe;
    private VirtualPipe                messagePipe;
    private LeakMetricSnapshot         before;

    static {
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            textBuilder.append("websocket-message-").append(i).append('-');
        }
        LARGE_TEXT_MESSAGE = textBuilder.toString();

        for (int i = 0; i < BINARY_FRAME_BYTES.length; i++) {
            BINARY_FRAME_BYTES[i] = (byte) (i & 0xFF);
        }

        MASKED_TEXT_FRAME = buildRfc6455Frame(WebSocketOpcode.TEXT.code(), true, true, MASK_KEY, TEXT_FRAME_BYTES);
        MASKED_BINARY_FRAME = buildRfc6455Frame(WebSocketOpcode.BINARY.code(), true, true, MASK_KEY, BINARY_FRAME_BYTES);

        byte[] fragmentedPayload = LARGE_TEXT_MESSAGE.getBytes(StandardCharsets.UTF_8);
        MASKED_FRAGMENTED_TEXT_MESSAGE = splitToMaskedFrames(fragmentedPayload, MESSAGE_FRAGMENT_SIZE);
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.neta = new NetManager();
        this.framePipe = this.openVirtualPipe(ctx -> {
            ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
            ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
        }, VrtSoConfig.asServer(), ADDRESS.incrementAndGet());
        this.messagePipe = this.openVirtualPipe(ctx -> {
            ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
            ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
            ctx.addLast("ws-message", new WebSocketMessageDuplex(true, MAX_MESSAGE_PAYLOAD, MESSAGE_FRAGMENT_SIZE));
        }, VrtSoConfig.asServer(), ADDRESS.incrementAndGet());
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        closePipe(this.framePipe);
        closePipe(this.messagePipe);
        if (this.neta != null) {
            this.neta.shutdown();
        }
    }

    @Setup(Level.Iteration)
    public void captureBaseline() {
        this.before = LeakMetricSnapshot.capture(ByteBufAllocator.DEFAULT.metric());
        if (this.framePipe != null) {
            this.framePipe.reset();
        }
        if (this.messagePipe != null) {
            this.messagePipe.reset();
        }
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        if (this.framePipe != null) {
            this.framePipe.reset();
        }
        if (this.messagePipe != null) {
            this.messagePipe.reset();
        }
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int neta_handshakeServerRfc6455() throws Throwable {
        VirtualPipe pipe = this.openVirtualPipe(ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asServer(), ADDRESS.incrementAndGet());
        try {
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            pipe.channel().receiveData(request);
            assertNoErrors(pipe);
            int observed = releaseVirtualOutbound(pipe.outbound());
            releaseVirtualInbound(pipe.inbound());
            if (!WebSocketUtils.isReady(pipe.channel())) {
                throw new IllegalStateException("neta websocket server handshake was not marked ready");
            }
            return observed;
        } finally {
            closePipe(pipe);
        }
    }

    @Benchmark
    public int netty_handshakeServerRfc6455() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        request.headers().set(HttpHeaderNames.HOST, "example.com");
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(WebSocketVersion.V13.code()));
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");

        EmbeddedChannel channel = new EmbeddedChannel(
                new io.netty.handler.codec.http.HttpRequestDecoder(),
                new io.netty.handler.codec.http.HttpResponseEncoder());
        try {
            WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(WS_URI, null, true, MAX_MESSAGE_PAYLOAD);
            WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
            ChannelFuture future = handshaker.handshake(channel, request.retain());
            future.syncUninterruptibly();
            return releaseNettyOutbound(channel);
        } finally {
            releaseNettyObject(request);
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_handshakeClientRfc6455() throws Throwable {
        VirtualPipe pipe = this.openVirtualPipe(ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asClient(), ADDRESS.incrementAndGet());
        try {
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            String key = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            pipe.channel().sendData(request).get();
            int observed = releaseVirtualOutbound(pipe.outbound());
            pipe.channel().receiveData(newUpgradeResponse(key));
            assertNoErrors(pipe);
            observed += releaseVirtualInbound(pipe.inbound());
            if (!WebSocketUtils.isReady(pipe.channel())) {
                throw new IllegalStateException("neta websocket client handshake was not marked ready");
            }
            return observed;
        } finally {
            closePipe(pipe);
        }
    }

    @Benchmark
    public int netty_handshakeClientRfc6455() {
        EmbeddedChannel channel = new EmbeddedChannel(
            new io.netty.handler.codec.http.HttpRequestEncoder(),
            new io.netty.handler.codec.http.HttpResponseDecoder());
        try {
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    URI.create(WS_URI),
                    io.netty.handler.codec.http.websocketx.WebSocketVersion.V13,
                    null,
                    true,
                    new DefaultHttpHeaders(),
                    MAX_MESSAGE_PAYLOAD);
            handshaker.handshake(channel).syncUninterruptibly();

            HandshakeRequestSnapshot request = captureNettyHandshakeRequest(channel);
            String key = extractSecWebSocketKey(request.requestText);
            int observed = request.outboundBytes;

            io.netty.handler.codec.http.FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.UPGRADE, io.netty.handler.codec.http.HttpHeaderValues.WEBSOCKET);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION, io.netty.handler.codec.http.HttpHeaderValues.UPGRADE);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key));
            handshaker.finishHandshake(channel, response.retain());

            releaseNettyObject(request);
            releaseNettyObject(response);
            return observed;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_frameEncodeText() throws Throwable {
        this.framePipe.reset();
        this.framePipe.channel().sendData(WebSocketUtils.textFrame(true, false, null, utf8(TEXT_FRAME))).get();
        assertNoErrors(this.framePipe);
        return releaseVirtualOutbound(this.framePipe.outbound());
    }

    @Benchmark
    public int netty_frameEncodeText() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        try {
            channel.writeOutbound(new TextWebSocketFrame(TEXT_FRAME));
            return releaseNettyOutbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_frameDecodeMaskedText() {
        this.framePipe.reset();
        this.framePipe.channel().receiveData(httpByteBuf(MASKED_TEXT_FRAME));
        assertNoErrors(this.framePipe);
        return releaseVirtualInbound(this.framePipe.inbound());
    }

    @Benchmark
    public int netty_frameDecodeMaskedText() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD));
        try {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(MASKED_TEXT_FRAME));
            return releaseNettyInbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_frameEncodeBinary4k() throws Throwable {
        this.framePipe.reset();
        this.framePipe.channel().sendData(WebSocketUtils.binaryFrame(true, false, null, binary(BINARY_FRAME_BYTES))).get();
        assertNoErrors(this.framePipe);
        return releaseVirtualOutbound(this.framePipe.outbound());
    }

    @Benchmark
    public int netty_frameEncodeBinary4k() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        try {
            channel.writeOutbound(new BinaryWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(BINARY_FRAME_BYTES)));
            return releaseNettyOutbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_frameDecodeMaskedBinary4k() {
        this.framePipe.reset();
        this.framePipe.channel().receiveData(httpByteBuf(MASKED_BINARY_FRAME));
        assertNoErrors(this.framePipe);
        return releaseVirtualInbound(this.framePipe.inbound());
    }

    @Benchmark
    public int netty_frameDecodeMaskedBinary4k() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD));
        try {
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(MASKED_BINARY_FRAME));
            return releaseNettyInbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_messageEncodeTextAutoFragment() throws Throwable {
        this.messagePipe.reset();
        this.messagePipe.channel().sendData(WebSocketUtils.textMessage(utf8(LARGE_TEXT_MESSAGE))).get();
        assertNoErrors(this.messagePipe);
        return releaseVirtualOutbound(this.messagePipe.outbound());
    }

    @Benchmark
    public int netty_messageEncodeTextAutoFragment() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        try {
            writeFragmentedNettyText(channel, LARGE_TEXT_MESSAGE.getBytes(StandardCharsets.UTF_8), MESSAGE_FRAGMENT_SIZE);
            return releaseNettyOutbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Benchmark
    public int neta_messageDecodeFragmentedText() {
        this.messagePipe.reset();
        for (byte[] frame : MASKED_FRAGMENTED_TEXT_MESSAGE) {
            this.messagePipe.channel().receiveData(httpByteBuf(frame));
        }
        assertNoErrors(this.messagePipe);
        return releaseVirtualInbound(this.messagePipe.inbound());
    }

    @Benchmark
    public int netty_messageDecodeFragmentedText() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD),
                new WebSocketFrameAggregator(MAX_MESSAGE_PAYLOAD));
        try {
            for (byte[] frame : MASKED_FRAGMENTED_TEXT_MESSAGE) {
                channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(frame));
            }
            return releaseNettyInbound(channel);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder().include(WebSocketBenchmark.class.getSimpleName()).build();
        new Runner(options).run();
    }

    private VirtualPipe openVirtualPipe(ProtoInitializer initializer, VrtSoConfig config, int addressId) throws IOException {
        Queue<Object> inbound = new ConcurrentLinkedQueue<>();
        Queue<Object> outbound = new ConcurrentLinkedQueue<>();
        Queue<Throwable> inboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> outboundErrors = new ConcurrentLinkedQueue<>();
        VrtChannel channel = (VrtChannel) this.neta.connectSync(new VrtSocketAddress(addressId), initializer, config);
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> {
            Object data = payload.getData();
            if (data != null) {
                inbound.offer(data);
            }
        });
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, payload -> {
            Object data = payload.getData();
            if (data == null) {
                return;
            }
            if (data instanceof ByteBuf) {
                outbound.offer(((ByteBuf) data).retain());
            } else {
                outbound.offer(snapshotOutbound(data));
            }
        });
        channel.subscribe(p -> p.isInbound() && !p.isSuccess(), SubscribeMode.SYNC, payload -> inboundErrors.offer(payload.getError()));
        channel.subscribe(p -> p.isOutbound() && !p.isSuccess(), SubscribeMode.SYNC, payload -> outboundErrors.offer(payload.getError()));
        return new VirtualPipe(channel, inbound, outbound, inboundErrors, outboundErrors);
    }

    private static void closePipe(VirtualPipe pipe) {
        if (pipe == null) {
            return;
        }

        pipe.reset();
        if (pipe.channel() != null && !pipe.channel().isClose()) {
            try {
                pipe.channel().close().get();
            } catch (Exception e) {
                throw new IllegalStateException("failed to close virtual websocket benchmark channel", e);
            }
        }
    }

    private static ByteBuf utf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length, bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static ByteBuf binary(byte[] value) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(value.length, value.length);
        buf.writeBytes(value, 0, value.length);
        buf.markWriter();
        return buf;
    }

    private static HttpByteBuf httpByteBuf(byte[] value) {
        return new DefaultHttpByteBuf(ByteBuf.wrap(value));
    }

    private static byte[][] splitToMaskedFrames(byte[] payload, int chunkSize) {
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            int len = Math.min(chunkSize, payload.length - offset);
            boolean fin = offset + len >= payload.length;
            byte[] chunk = new byte[len];
            System.arraycopy(payload, offset, chunk, 0, len);
            int opcode = first ? WebSocketOpcode.TEXT.code() : WebSocketOpcode.CONTINUATION.code();
            frames.add(buildRfc6455Frame(opcode, fin, true, MASK_KEY, chunk));
            offset += len;
            first = false;
        }
        return frames.toArray(new byte[0][]);
    }

    private static byte[] buildRfc6455Frame(int opcode, boolean fin, boolean masked, byte[] maskKey, byte[] payload) {
        int headerSize = 2;
        if (payload.length >= 126 && payload.length <= 65535) {
            headerSize += 2;
        } else if (payload.length > 65535) {
            headerSize += 8;
        }
        if (masked) {
            headerSize += 4;
        }

        byte[] frame = new byte[headerSize + payload.length];
        int index = 0;
        frame[index++] = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));
        if (payload.length < 126) {
            frame[index++] = (byte) ((masked ? 0x80 : 0x00) | payload.length);
        } else if (payload.length <= 65535) {
            frame[index++] = (byte) ((masked ? 0x80 : 0x00) | 126);
            frame[index++] = (byte) ((payload.length >>> 8) & 0xFF);
            frame[index++] = (byte) (payload.length & 0xFF);
        } else {
            frame[index++] = (byte) ((masked ? 0x80 : 0x00) | 127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame[index++] = (byte) ((length >>> shift) & 0xFF);
            }
        }

        if (masked) {
            System.arraycopy(maskKey, 0, frame, index, 4);
            index += 4;
            for (int i = 0; i < payload.length; i++) {
                frame[index++] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        } else {
            System.arraycopy(payload, 0, frame, index, payload.length);
        }
        return frame;
    }

    private static net.hasor.neta.codec.http.DefaultFullHttpResponse newUpgradeResponse(String key) {
        net.hasor.neta.codec.http.DefaultFullHttpResponse response = new net.hasor.neta.codec.http.DefaultFullHttpResponse(net.hasor.neta.codec.http.HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key));
        return response;
    }

    private static String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HandshakeRequestSnapshot captureNettyHandshakeRequest(EmbeddedChannel channel) {
        StringBuilder request = new StringBuilder();
        int outboundBytes = 0;
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof io.netty.buffer.ByteBuf) {
                io.netty.buffer.ByteBuf buffer = (io.netty.buffer.ByteBuf) outbound;
                outboundBytes += buffer.readableBytes();
                request.append(buffer.toString(StandardCharsets.US_ASCII));
            } else {
                throw new IllegalStateException("unexpected netty handshake outbound type: " + outbound.getClass().getName());
            }
            releaseNettyObject(outbound);
        }

        return new HandshakeRequestSnapshot(request.toString(), outboundBytes);
    }

    private static String extractSecWebSocketKey(String request) {
        String prefix = "sec-websocket-key:";
        String lowerRequest = request.toLowerCase();
        int start = lowerRequest.indexOf(prefix);
        if (start < 0) {
            throw new IllegalStateException("missing Sec-WebSocket-Key in netty handshake request");
        }

        start += prefix.length();
        int end = request.indexOf("\r\n", start);
        if (end < 0) {
            throw new IllegalStateException("invalid Sec-WebSocket-Key header in netty handshake request");
        }

        return request.substring(start, end).trim();
    }

    private static void writeFragmentedNettyText(EmbeddedChannel channel, byte[] payload, int chunkSize) {
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            int len = Math.min(chunkSize, payload.length - offset);
            boolean fin = offset + len >= payload.length;
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(payload, offset, len);
            if (first) {
                channel.writeOutbound(new TextWebSocketFrame(fin, 0, buf));
                first = false;
            } else {
                channel.writeOutbound(new ContinuationWebSocketFrame(fin, 0, buf));
            }
            offset += len;
        }
    }

    private static void assertNoErrors(VirtualPipe pipe) {
        Throwable inboundError = pipe.inboundErrors().poll();
        if (inboundError != null) {
            throw new IllegalStateException("virtual inbound failed", inboundError);
        }
        Throwable outboundError = pipe.outboundErrors().poll();
        if (outboundError != null) {
            throw new IllegalStateException("virtual outbound failed", outboundError);
        }
    }

    private static int releaseVirtualOutbound(Queue<Object> outbound) {
        int observed = 0;
        Object item;
        while ((item = outbound.poll()) != null) {
            observed += measureAndRelease(item);
        }
        return observed;
    }

    private static int releaseVirtualInbound(Queue<Object> inbound) {
        int observed = 0;
        Object item;
        while ((item = inbound.poll()) != null) {
            observed += measureAndRelease(item);
        }
        return observed;
    }

    private static int releaseNettyOutbound(EmbeddedChannel channel) {
        int observed = 0;
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                observed += ((io.netty.buffer.ByteBuf) msg).readableBytes();
            } else {
                observed += 1;
            }
            releaseNettyObject(msg);
        }
        return observed;
    }

    private static int releaseNettyInbound(EmbeddedChannel channel) {
        int observed = 0;
        Object msg;
        while ((msg = channel.readInbound()) != null) {
            observed += measureNettyObject(msg);
            releaseNettyObject(msg);
        }
        return observed;
    }

    private static int measureAndRelease(Object item) {
        if (item instanceof ByteBuf) {
            ByteBuf buffer = (ByteBuf) item;
            int readable = buffer.readableBytes();
            if (!buffer.isFree()) {
                buffer.release();
            }
            return readable;
        }
        if (item instanceof WebSocketFrame) {
            WebSocketFrame frame = (WebSocketFrame) item;
            int observed = 1 + frame.content().readableBytes();
            frame.release();
            return observed;
        }
        if (item instanceof WebSocketMessage) {
            WebSocketMessage message = (WebSocketMessage) item;
            int observed = 1 + message.content().readableBytes();
            message.release();
            return observed;
        }
        if (item instanceof HttpObject) {
            int observed = 1;
            if (item instanceof HttpContent) {
                ByteBuf body = ((HttpContent) item).content();
                if (body != null) {
                    observed += body.readableBytes();
                }
            }
            ((HttpObject) item).release();
            return observed;
        }
        return 1;
    }

    private static int measureNettyObject(Object msg) {
        if (msg instanceof io.netty.buffer.ByteBuf) {
            return ((io.netty.buffer.ByteBuf) msg).readableBytes();
        }
        if (msg instanceof io.netty.handler.codec.http.websocketx.WebSocketFrame) {
            io.netty.handler.codec.http.websocketx.WebSocketFrame frame = (io.netty.handler.codec.http.websocketx.WebSocketFrame) msg;
            return 1 + frame.content().readableBytes();
        }
        if (msg instanceof io.netty.handler.codec.http.HttpContent) {
            return 1 + ((io.netty.handler.codec.http.HttpContent) msg).content().readableBytes();
        }
        return 1;
    }

    private static void releaseNettyObject(Object msg) {
        if (msg instanceof ReferenceCounted) {
            ReferenceCounted ref = (ReferenceCounted) msg;
            if (ref.refCnt() > 0) {
                ref.release();
            }
        }
    }

    private static Object snapshotOutbound(Object outbound) {
        if (outbound instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) outbound;
            net.hasor.neta.codec.http.DefaultFullHttpResponse copy = new net.hasor.neta.codec.http.DefaultFullHttpResponse(response.protocolVersion(), response.status(), retainContent(response.content()));
            copy.appendHeaders(response);
            return copy;
        }
        if (outbound instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) outbound;
            net.hasor.neta.codec.http.DefaultFullHttpRequest copy = new net.hasor.neta.codec.http.DefaultFullHttpRequest(request.protocolVersion(), request.method(), request.uri(), retainContent(request.content()));
            copy.appendHeaders(request);
            return copy;
        }
        if (outbound instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) outbound;
            return new DefaultHttpResponse(response.protocolVersion(), response.status());
        }
        if (outbound instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) outbound;
            return new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
        }
        if (outbound instanceof HttpByteBuf) {
            HttpByteBuf body = (HttpByteBuf) outbound;
            return new DefaultHttpByteBuf(retainContent(body.content()), body.streamId());
        }
        if (outbound instanceof HttpContent) {
            HttpContent content = (HttpContent) outbound;
            return new DefaultHttpContent(retainContent(content.content())).streamId(content.streamId());
        }
        if (outbound instanceof HttpHeaders) {
            net.hasor.neta.codec.http.DefaultHttpHeaders copy = new net.hasor.neta.codec.http.DefaultHttpHeaders();
            copy.appendHeaders((HttpHeaders) outbound);
            return copy;
        }
        return outbound;
    }

    private static ByteBuf retainContent(ByteBuf content) {
        return content == null ? ByteBuf.EMPTY : content.retain();
    }

    private static final class ReadyWebSocketBinder implements ProtoDuplex<Object, Object, Object, Object> {
        private final boolean server;

        private ReadyWebSocketBinder(boolean server) {
            this.server = server;
        }

        @Override
        public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
            WebSocketContext wsContext = new WebSocketContextImpl(this.server, null, WebSocketVersion.V13.code(), "/chat", "example.com", "http://example.com", Collections.<String>emptyList());
            context.context(WebSocketContext.class, wsContext);
            context.rootContext(WebSocketContext.class, wsContext);
            WebSocketRegistry.bind(context, WebSocketRegistryKey.connectionScope(), wsContext);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Object> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<Object> sndDown) {
            ProtoRcvQueue<Object> src = isRcv ? rcvUp : sndUp;
            ProtoSndQueue<Object> dst = isRcv ? rcvDown : sndDown;
            while (src != null && src.hasMore()) {
                dst.offerMessage(src.takeMessage());
            }
            return ProtoStatus.Next;
        }

        @Override
        public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
            return ProtoStatus.Next;
        }

        @Override
        public void onClose(ProtoContext context) {
            WebSocketRegistry.remove(context, WebSocketRegistryKey.connectionScope());
            context.context(WebSocketContext.class, null);
            context.rootContext(WebSocketContext.class, null);
        }
    }

    private static final class VirtualPipe {
        private final VrtChannel       channel;
        private final Queue<Object>    inbound;
        private final Queue<Object>    outbound;
        private final Queue<Throwable> inboundErrors;
        private final Queue<Throwable> outboundErrors;

        private VirtualPipe(VrtChannel channel, Queue<Object> inbound, Queue<Object> outbound, Queue<Throwable> inboundErrors, Queue<Throwable> outboundErrors) {
            this.channel = channel;
            this.inbound = inbound;
            this.outbound = outbound;
            this.inboundErrors = inboundErrors;
            this.outboundErrors = outboundErrors;
        }

        private VrtChannel channel() {
            return this.channel;
        }

        private Queue<Object> inbound() {
            return this.inbound;
        }

        private Queue<Object> outbound() {
            return this.outbound;
        }

        private Queue<Throwable> inboundErrors() {
            return this.inboundErrors;
        }

        private Queue<Throwable> outboundErrors() {
            return this.outboundErrors;
        }

        private void reset() {
            clearQueue(this.inbound);
            clearQueue(this.outbound);
            this.inboundErrors.clear();
            this.outboundErrors.clear();
        }

        private static void clearQueue(Queue<Object> queue) {
            Object item;
            while ((item = queue.poll()) != null) {
                measureAndRelease(item);
            }
        }
    }
}