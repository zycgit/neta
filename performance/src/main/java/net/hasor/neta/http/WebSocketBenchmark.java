//package net.hasor.neta.http;
//
//import java.nio.charset.StandardCharsets;
//import java.util.concurrent.TimeUnit;
//import io.netty.channel.embedded.EmbeddedChannel;
//import net.hasor.neta.bytebuf.ByteBuf;
//import net.hasor.neta.bytebuf.ByteBufAllocator;
//import net.hasor.neta.channel.data.ProtoQueue;
//import net.hasor.neta.codec.http.websocket.*;
//import org.openjdk.jmh.annotations.*;
//import org.openjdk.jmh.runner.Runner;
//import org.openjdk.jmh.runner.RunnerException;
//import org.openjdk.jmh.runner.options.Options;
//import org.openjdk.jmh.runner.options.OptionsBuilder;
//
///**
// * JMH Benchmark: WebSocket Frame Encoding and Decoding.
// * Compares Neta codec performance against Netty.
// */
//@Fork(1)
//@State(Scope.Thread)
//@OutputTimeUnit(TimeUnit.MICROSECONDS)
//@BenchmarkMode(Mode.Throughput)
//@Warmup(iterations = 3, time = 2)
//@Measurement(iterations = 5, time = 3)
//public class WebSocketBenchmark {
//
//    // ========================= Test Data =========================
//
//    private static final String SHORT_TEXT = "Hello, WebSocket!";
//    private static final String MEDIUM_TEXT;
//    private static final byte[] BINARY_125;   // fits in 7-bit length
//    private static final byte[] BINARY_1K;    // requires 16-bit length
//    private static final byte[] BINARY_64K;   // requires 64-bit length
//    private static final byte[] MASK_KEY   = { 0x37, 0x12, (byte) 0xFA, (byte) 0xC9 };
//
//    static {
//        // Medium text (~500 chars)
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < 50; i++) {
//            sb.append("0123456789");
//        }
//        MEDIUM_TEXT = sb.toString();
//
//        BINARY_125 = new byte[125];
//        BINARY_1K = new byte[1024];
//        BINARY_64K = new byte[65536];
//        for (int i = 0; i < BINARY_125.length; i++)
//            BINARY_125[i] = (byte) (i & 0xFF);
//        for (int i = 0; i < BINARY_1K.length; i++)
//            BINARY_1K[i] = (byte) (i & 0xFF);
//        for (int i = 0; i < BINARY_64K.length; i++)
//            BINARY_64K[i] = (byte) (i & 0xFF);
//    }
//
//    // Pre-encoded frames for decode benchmarks
//    private byte[] encodedTextFrame;
//    private byte[] encodedMaskedTextFrame;
//    private byte[] encodedBinaryFrame1K;
//    private byte[] encodedBinaryFrame64K;
//
//    public static void main(String[] args) throws RunnerException {
//        Options opt = new OptionsBuilder().include(WebSocketBenchmark.class.getSimpleName()).build();
//        new Runner(opt).run();
//    }
//
//    @Setup(Level.Trial)
//    public void setup() throws Throwable {
//        // Encode frames for decode benchmarks using Neta encoder
//        encodedTextFrame = encodeNetaFrame(DefaultWebSocketFrame.text(SHORT_TEXT));
//        encodedMaskedTextFrame = encodeNetaFrame(new DefaultWebSocketFrame(WebSocketOpcode.TEXT, true, true, MASK_KEY, wrapText(SHORT_TEXT)));
//        encodedBinaryFrame1K = encodeNetaFrame(DefaultWebSocketFrame.binary(BINARY_1K));
//        encodedBinaryFrame64K = encodeNetaFrame(DefaultWebSocketFrame.binary(BINARY_64K));
//    }
//
//    private ByteBuf wrapText(String text) {
//        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
//        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length, Integer.MAX_VALUE);
//        buf.writeBytes(bytes, 0, bytes.length);
//        buf.markWriter();
//        return buf;
//    }
//
//    // ========================= Encode: Text Frames =========================
//
//    private byte[] encodeNetaFrame(WebSocketFrame frame) throws Throwable {
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//
//        ByteBuf out = dst.takeMessage();
//        int len = out.readableBytes();
//        byte[] result = new byte[len];
//        out.readBytes(result, 0, len);
//        out.free();
//        dst.rcvSubmit();
//        return result;
//    }
//
//    @Benchmark
//    public void neta_encodeTextFrame() throws Throwable {
//        WebSocketFrame frame = DefaultWebSocketFrame.text(SHORT_TEXT);
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            ByteBuf buf = dst.takeMessage();
//            buf.free();
//        }
//        frame.content().free();
//        dst.rcvSubmit();
//    }
//
//    @Benchmark
//    public void netty_encodeTextFrame() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder(false));
//        channel.writeOutbound(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(SHORT_TEXT));
//        io.netty.buffer.ByteBuf out = channel.readOutbound();
//        if (out != null)
//            out.release();
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_encodeMediumTextFrame() throws Throwable {
//        WebSocketFrame frame = DefaultWebSocketFrame.text(MEDIUM_TEXT);
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            ByteBuf buf = dst.takeMessage();
//            buf.free();
//        }
//        frame.content().free();
//        dst.rcvSubmit();
//    }
//
//    // ========================= Encode: Binary Frames =========================
//
//    @Benchmark
//    public void netty_encodeMediumTextFrame() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder(false));
//        channel.writeOutbound(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(MEDIUM_TEXT));
//        io.netty.buffer.ByteBuf out = channel.readOutbound();
//        if (out != null)
//            out.release();
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_encodeBinaryFrame1K() throws Throwable {
//        WebSocketFrame frame = new DefaultWebSocketFrame(WebSocketOpcode.BINARY, true, false, null, ByteBuf.wrap(BINARY_1K));
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            ByteBuf buf = dst.takeMessage();
//            buf.free();
//        }
//        frame.content().free();
//        dst.rcvSubmit();
//    }
//
//    @Benchmark
//    public void netty_encodeBinaryFrame1K() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder(false));
//        channel.writeOutbound(new io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(BINARY_1K)));
//        io.netty.buffer.ByteBuf out = channel.readOutbound();
//        if (out != null)
//            out.release();
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_encodeBinaryFrame64K() throws Throwable {
//        WebSocketFrame frame = new DefaultWebSocketFrame(WebSocketOpcode.BINARY, true, false, null, ByteBuf.wrap(BINARY_64K));
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            ByteBuf buf = dst.takeMessage();
//            buf.free();
//        }
//        frame.content().free();
//        dst.rcvSubmit();
//    }
//
//    // ========================= Decode: Frames =========================
//
//    @Benchmark
//    public void netty_encodeBinaryFrame64K() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder(false));
//        channel.writeOutbound(new io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(BINARY_64K)));
//        io.netty.buffer.ByteBuf out = channel.readOutbound();
//        if (out != null)
//            out.release();
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_decodeTextFrame() throws Throwable {
//        ByteBuf input = ByteBuf.wrap(encodedTextFrame);
//
//        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder();
//        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
//        ProtoQueue<WebSocketFrame> dst = new ProtoQueue<>(-1);
//        src.offerMessage(input);
//        src.sndSubmit();
//        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            WebSocketFrame frame = dst.takeMessage();
//            frame.content().free();
//        }
//        dst.rcvSubmit();
//        decoder.onClose(StubProtoContext.INSTANCE);
//        input.free();
//    }
//
//    @Benchmark
//    public void netty_decodeTextFrame() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder(false, false, 65536));
//        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(encodedTextFrame));
//        Object msg;
//        while ((msg = channel.readInbound()) != null) {
//            if (msg instanceof io.netty.util.ReferenceCounted) {
//                ((io.netty.util.ReferenceCounted) msg).release();
//            }
//        }
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_decodeBinaryFrame1K() throws Throwable {
//        ByteBuf input = ByteBuf.wrap(encodedBinaryFrame1K);
//
//        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder();
//        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
//        ProtoQueue<WebSocketFrame> dst = new ProtoQueue<>(-1);
//        src.offerMessage(input);
//        src.sndSubmit();
//        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            WebSocketFrame frame = dst.takeMessage();
//            frame.content().free();
//        }
//        dst.rcvSubmit();
//        decoder.onClose(StubProtoContext.INSTANCE);
//        input.free();
//    }
//
//    @Benchmark
//    public void netty_decodeBinaryFrame1K() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder(false, false, 65536));
//        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(encodedBinaryFrame1K));
//        Object msg;
//        while ((msg = channel.readInbound()) != null) {
//            if (msg instanceof io.netty.util.ReferenceCounted) {
//                ((io.netty.util.ReferenceCounted) msg).release();
//            }
//        }
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_decodeBinaryFrame64K() throws Throwable {
//        ByteBuf input = ByteBuf.wrap(encodedBinaryFrame64K);
//
//        WebSocketFrameDecoder decoder = new WebSocketFrameDecoder();
//        ProtoQueue<ByteBuf> src = new ProtoQueue<>(-1);
//        ProtoQueue<WebSocketFrame> dst = new ProtoQueue<>(-1);
//        src.offerMessage(input);
//        src.sndSubmit();
//        decoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            WebSocketFrame frame = dst.takeMessage();
//            frame.content().free();
//        }
//        dst.rcvSubmit();
//        decoder.onClose(StubProtoContext.INSTANCE);
//        input.free();
//    }
//
//    // ========================= Encode: Control Frames =========================
//
//    @Benchmark
//    public void netty_decodeBinaryFrame64K() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder(false, false, 131072));
//        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(encodedBinaryFrame64K));
//        Object msg;
//        while ((msg = channel.readInbound()) != null) {
//            if (msg instanceof io.netty.util.ReferenceCounted) {
//                ((io.netty.util.ReferenceCounted) msg).release();
//            }
//        }
//        channel.finishAndReleaseAll();
//    }
//
//    @Benchmark
//    public void neta_encodePingFrame() throws Throwable {
//        WebSocketFrame frame = DefaultWebSocketFrame.ping();
//        WebSocketFrameEncoder encoder = new WebSocketFrameEncoder();
//        ProtoQueue<WebSocketFrame> src = new ProtoQueue<>(-1);
//        ProtoQueue<ByteBuf> dst = new ProtoQueue<>(-1);
//        src.offerMessage(frame);
//        src.sndSubmit();
//        encoder.onMessage(StubProtoContext.INSTANCE, src, dst);
//        dst.sndSubmit();
//        while (dst.hasMore()) {
//            ByteBuf buf = dst.takeMessage();
//            buf.free();
//        }
//        dst.rcvSubmit();
//    }
//
//    // ========================= Main =========================
//
//    @Benchmark
//    public void netty_encodePingFrame() {
//        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder(false));
//        channel.writeOutbound(new io.netty.handler.codec.http.websocketx.PingWebSocketFrame());
//        io.netty.buffer.ByteBuf out = channel.readOutbound();
//        if (out != null)
//            out.release();
//        channel.finishAndReleaseAll();
//    }
//}
