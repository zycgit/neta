/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCounted;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.openjdk.jmh.annotations.*;
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

    private static abstract class AbstractNetaPipeState {
        private NetManager  neta;
        private VirtualPipe pipe;

        @Setup(Level.Trial)
        public void setupTrial() {
            this.neta = new NetManager();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() throws IOException {
            this.pipe = openVirtualPipe(this.neta, this.initializer(), this.config(), ADDRESS.incrementAndGet());
            this.prepareInvocation();
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            try {
                this.cleanupInvocation();
            } finally {
                closePipe(this.pipe);
                this.pipe = null;
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws IOException {
            if (this.neta != null) {
                this.neta.shutdown();
                this.neta = null;
            }
        }

        protected VirtualPipe pipe() {
            return this.pipe;
        }

        protected VrtSoConfig config() {
            return VrtSoConfig.asServer();
        }

        protected void prepareInvocation() throws IOException {
        }

        protected void cleanupInvocation() {
        }

        protected abstract ProtoInitializer initializer();
    }

    private static abstract class AbstractNettyChannelState {
        private EmbeddedChannel channel;

        @Setup(Level.Invocation)
        public void setupInvocation() {
            this.channel = this.newChannel();
            this.prepareInvocation();
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            try {
                this.cleanupInvocation();
            } finally {
                if (this.channel != null) {
                    this.channel.finishAndReleaseAll();
                    this.channel = null;
                }
            }
        }

        protected EmbeddedChannel channel() {
            return this.channel;
        }

        protected void prepareInvocation() {
        }

        protected void cleanupInvocation() {
        }

        protected abstract EmbeddedChannel newChannel();
    }

    private static abstract class AbstractReusableNetaPipeState {
        private NetManager  neta;
        private VirtualPipe pipe;

        @Setup(Level.Trial)
        public void setupTrial() throws IOException {
            this.neta = new NetManager();
            this.pipe = openVirtualPipe(this.neta, this.initializer(), this.config(), ADDRESS.incrementAndGet());
        }

        @Setup(Level.Invocation)
        public void setupInvocation() throws IOException {
            this.prepareInvocation();
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            this.cleanupInvocation();
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws IOException {
            closePipe(this.pipe);
            this.pipe = null;
            if (this.neta != null) {
                this.neta.shutdown();
                this.neta = null;
            }
        }

        protected VirtualPipe pipe() {
            return this.pipe;
        }

        protected VrtSoConfig config() {
            return VrtSoConfig.asServer();
        }

        protected void prepareInvocation() throws IOException {
        }

        protected void cleanupInvocation() {
        }

        protected abstract ProtoInitializer initializer();
    }

    private static abstract class AbstractReusableNettyChannelState {
        private EmbeddedChannel channel;

        @Setup(Level.Trial)
        public void setupTrial() {
            this.channel = this.newChannel();
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            clearNettyChannel(this.channel);
            this.prepareInvocation();
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            try {
                this.cleanupInvocation();
            } finally {
                clearNettyChannel(this.channel);
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() {
            if (this.channel != null) {
                this.channel.finishAndReleaseAll();
                this.channel = null;
            }
        }

        protected EmbeddedChannel channel() {
            return this.channel;
        }

        protected void prepareInvocation() {
        }

        protected void cleanupInvocation() {
        }

        protected abstract EmbeddedChannel newChannel();
    }

    //

    @State(Scope.Thread)
    public static class NetaServerHandshakeState extends AbstractNetaPipeState {
        private FullHttpRequest request;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13));
        }

        @Override
        protected void prepareInvocation() {
            this.request = newNetaServerHandshakeRequest();
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObject(this.request);
            this.request = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaClientHandshakeState extends AbstractNetaPipeState {
        @Override
        protected ProtoInitializer initializer() {
            return ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13));
        }

        @Override
        protected VrtSoConfig config() {
            return VrtSoConfig.asClient();
        }
    }

    @State(Scope.Thread)
    public static class NettyServerHandshakeState extends AbstractNettyChannelState {
        private DefaultFullHttpRequest    request;
        private WebSocketServerHandshaker handshaker;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder(), new io.netty.handler.codec.http.HttpResponseEncoder());
        }

        @Override
        protected void prepareInvocation() {
            this.request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
            this.request.headers().set(HttpHeaderNames.HOST, "example.com");
            this.request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            this.request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            this.request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(WebSocketVersion.V13.code()));
            this.request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, SERVER_HANDSHAKE_KEY);
            WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(WS_URI, null, true, MAX_MESSAGE_PAYLOAD);
            this.handshaker = factory.newHandshaker(this.request);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.request);
            this.request = null;
            this.handshaker = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyClientHandshakeState extends AbstractNettyChannelState {
        private WebSocketClientHandshaker handshaker;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestEncoder(), new io.netty.handler.codec.http.HttpResponseDecoder());
        }

        @Override
        protected void prepareInvocation() {
            this.handshaker = WebSocketClientHandshakerFactory.newHandshaker(URI.create(WS_URI), io.netty.handler.codec.http.websocketx.WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), MAX_MESSAGE_PAYLOAD);
        }

        @Override
        protected void cleanupInvocation() {
            this.handshaker = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaFrameEncodeState extends AbstractReusableNetaPipeState {
        private WebSocketFrame textFrame;
        private WebSocketFrame binaryFrame;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
            };
        }

        @Override
        public void prepareInvocation() {
            this.textFrame = WebSocketUtils.textFrame(true, false, null, utf8(TEXT_FRAME));
            this.binaryFrame = WebSocketUtils.binaryFrame(true, false, null, binary(BINARY_FRAME_BYTES));
        }

        @Override
        public void cleanupInvocation() {
            releaseNetaObject(this.textFrame);
            releaseNetaObject(this.binaryFrame);
            this.textFrame = null;
            this.binaryFrame = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyFrameEncodeState extends AbstractReusableNettyChannelState {
        private TextWebSocketFrame   textFrame;
        private BinaryWebSocketFrame binaryFrame;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        }

        @Override
        protected void prepareInvocation() {
            this.textFrame = new TextWebSocketFrame(TEXT_FRAME);
            this.binaryFrame = new BinaryWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(BINARY_FRAME_BYTES));
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.textFrame);
            releaseNettyObject(this.binaryFrame);
            this.textFrame = null;
            this.binaryFrame = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaFrameDecodeState extends AbstractReusableNetaPipeState {
        private HttpByteBuf maskedTextFrame;
        private HttpByteBuf maskedBinaryFrame;
        private byte[]      maskedTextBytes;
        private byte[]      maskedBinaryBytes;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
            };
        }

        @Override
        public void prepareInvocation() {
            this.maskedTextBytes = reusableBytes(this.maskedTextBytes, MASKED_TEXT_FRAME);
            this.maskedBinaryBytes = reusableBytes(this.maskedBinaryBytes, MASKED_BINARY_FRAME);
            this.maskedTextFrame = httpByteBuf(this.maskedTextBytes);
            this.maskedBinaryFrame = httpByteBuf(this.maskedBinaryBytes);
        }

        @Override
        public void cleanupInvocation() {
            releaseNetaObject(this.maskedTextFrame);
            releaseNetaObject(this.maskedBinaryFrame);
            this.maskedTextFrame = null;
            this.maskedBinaryFrame = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyFrameDecodeState extends AbstractReusableNettyChannelState {
        private io.netty.buffer.ByteBuf maskedTextFrame;
        private io.netty.buffer.ByteBuf maskedBinaryFrame;
        private byte[]                  maskedTextBytes;
        private byte[]                  maskedBinaryBytes;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD));
        }

        @Override
        protected void prepareInvocation() {
            this.maskedTextBytes = reusableBytes(this.maskedTextBytes, MASKED_TEXT_FRAME);
            this.maskedBinaryBytes = reusableBytes(this.maskedBinaryBytes, MASKED_BINARY_FRAME);
            this.maskedTextFrame = io.netty.buffer.Unpooled.wrappedBuffer(this.maskedTextBytes);
            this.maskedBinaryFrame = io.netty.buffer.Unpooled.wrappedBuffer(this.maskedBinaryBytes);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.maskedTextFrame);
            releaseNettyObject(this.maskedBinaryFrame);
            this.maskedTextFrame = null;
            this.maskedBinaryFrame = null;
        }
    }

    private static abstract class NetaRfc6455DecodeState extends AbstractReusableNetaPipeState {
        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
            };
        }
    }

    private static abstract class NettyRfc6455DecodeState extends AbstractReusableNettyChannelState {
        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD));
        }
    }

    @State(Scope.Thread)
    public static class NetaPartialFrameDecodeState extends NetaRfc6455DecodeState {
        private HttpByteBuf[] chunks;
        private byte[][]      chunkBytes;

        @Override
        protected void prepareInvocation() {
            if (this.chunkBytes == null) {
                this.chunkBytes = new byte[PARTIAL_MASKED_TEXT_FRAME.length][];
            }
            this.chunks = new HttpByteBuf[PARTIAL_MASKED_TEXT_FRAME.length];
            for (int i = 0; i < PARTIAL_MASKED_TEXT_FRAME.length; i++) {
                this.chunkBytes[i] = reusableBytes(this.chunkBytes[i], PARTIAL_MASKED_TEXT_FRAME[i]);
                this.chunks[i] = httpByteBuf(this.chunkBytes[i]);
            }
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObjects(this.chunks);
            this.chunks = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyPartialFrameDecodeState extends NettyRfc6455DecodeState {
        private io.netty.buffer.ByteBuf[] chunks;
        private byte[][]                  chunkBytes;

        @Override
        protected void prepareInvocation() {
            if (this.chunkBytes == null) {
                this.chunkBytes = new byte[PARTIAL_MASKED_TEXT_FRAME.length][];
            }
            this.chunks = new io.netty.buffer.ByteBuf[PARTIAL_MASKED_TEXT_FRAME.length];
            for (int i = 0; i < PARTIAL_MASKED_TEXT_FRAME.length; i++) {
                this.chunkBytes[i] = reusableBytes(this.chunkBytes[i], PARTIAL_MASKED_TEXT_FRAME[i]);
                this.chunks[i] = io.netty.buffer.Unpooled.wrappedBuffer(this.chunkBytes[i]);
            }
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObjects(this.chunks);
            this.chunks = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaStickyFrameDecodeState extends NetaRfc6455DecodeState {
        private HttpByteBuf stickyFrames;
        private byte[]      stickyFrameBytes;

        @Override
        protected void prepareInvocation() {
            this.stickyFrameBytes = reusableBytes(this.stickyFrameBytes, STICKY_MASKED_TEXT_FRAMES);
            this.stickyFrames = httpByteBuf(this.stickyFrameBytes);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObject(this.stickyFrames);
            this.stickyFrames = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyStickyFrameDecodeState extends NettyRfc6455DecodeState {
        private io.netty.buffer.ByteBuf stickyFrames;
        private byte[]                  stickyFrameBytes;

        @Override
        protected void prepareInvocation() {
            this.stickyFrameBytes = reusableBytes(this.stickyFrameBytes, STICKY_MASKED_TEXT_FRAMES);
            this.stickyFrames = io.netty.buffer.Unpooled.wrappedBuffer(this.stickyFrameBytes);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.stickyFrames);
            this.stickyFrames = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaMultiObjectDecodeState extends NetaRfc6455DecodeState {
        private HttpByteBuf[] frames;
        private byte[][]      frameBytes;

        @Override
        protected void prepareInvocation() {
            if (this.frameBytes == null) {
                this.frameBytes = new byte[MULTI_OBJECT_COUNT][];
            }
            this.frames = new HttpByteBuf[MULTI_OBJECT_COUNT];
            for (int i = 0; i < MULTI_OBJECT_COUNT; i++) {
                this.frameBytes[i] = reusableBytes(this.frameBytes[i], MASKED_TEXT_FRAME);
                this.frames[i] = httpByteBuf(this.frameBytes[i]);
            }
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObjects(this.frames);
            this.frames = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyMultiObjectDecodeState extends NettyRfc6455DecodeState {
        private io.netty.buffer.ByteBuf[] frames;
        private byte[][]                  frameBytes;

        @Override
        protected void prepareInvocation() {
            if (this.frameBytes == null) {
                this.frameBytes = new byte[MULTI_OBJECT_COUNT][];
            }
            this.frames = new io.netty.buffer.ByteBuf[MULTI_OBJECT_COUNT];
            for (int i = 0; i < MULTI_OBJECT_COUNT; i++) {
                this.frameBytes[i] = reusableBytes(this.frameBytes[i], MASKED_TEXT_FRAME);
                this.frames[i] = io.netty.buffer.Unpooled.wrappedBuffer(this.frameBytes[i]);
            }
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObjects(this.frames);
            this.frames = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaHixieDecodeState extends AbstractReusableNetaPipeState {
        private HttpByteBuf frame;
        private byte[]      frameBytes;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true, WebSocketVersion.V0));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V0));
            };
        }

        @Override
        protected void prepareInvocation() {
            this.frameBytes = reusableBytes(this.frameBytes, HIXIE_TEXT_FRAME);
            this.frame = httpByteBuf(this.frameBytes);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObject(this.frame);
            this.frame = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyHixieDecodeState extends AbstractReusableNettyChannelState {
        private io.netty.buffer.ByteBuf frame;
        private byte[]                  frameBytes;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket00FrameDecoder(MAX_MESSAGE_PAYLOAD));
        }

        @Override
        protected void prepareInvocation() {
            this.frameBytes = reusableBytes(this.frameBytes, HIXIE_TEXT_FRAME);
            this.frame = io.netty.buffer.Unpooled.wrappedBuffer(this.frameBytes);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.frame);
            this.frame = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaHixieEncodeState extends AbstractReusableNetaPipeState {
        private WebSocketFrame frame;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(false, WebSocketVersion.V0));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V0));
            };
        }

        @Override
        protected void prepareInvocation() {
            this.frame = WebSocketUtils.textFrame(true, false, null, utf8(TEXT_FRAME));
        }

        @Override
        protected void cleanupInvocation() {
            releaseNetaObject(this.frame);
            this.frame = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyHixieEncodeState extends AbstractReusableNettyChannelState {
        private TextWebSocketFrame frame;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket00FrameEncoder());
        }

        @Override
        protected void prepareInvocation() {
            this.frame = new TextWebSocketFrame(TEXT_FRAME);
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObject(this.frame);
            this.frame = null;
        }
    }

    @State(Scope.Thread)
    public static class NetaMessageEncodeState extends AbstractReusableNetaPipeState {
        private TextWebSocketMessage textMessage;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplex(true, MAX_MESSAGE_PAYLOAD, MESSAGE_FRAGMENT_SIZE));
            };
        }

        @Override
        public void prepareInvocation() {
            this.textMessage = WebSocketUtils.textMessage(ByteBuf.wrap(LARGE_TEXT_MESSAGE_BYTES));
        }

        @Override
        public void cleanupInvocation() {
            releaseNetaObject(this.textMessage);
            this.textMessage = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyMessageEncodeState extends AbstractReusableNettyChannelState {
        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        }
    }

    @State(Scope.Thread)
    public static class NetaMessageDecodeState extends AbstractReusableNetaPipeState {
        private HttpByteBuf[] fragmentedFrames;
        private byte[][]      fragmentedFrameBytes;

        @Override
        protected ProtoInitializer initializer() {
            return ctx -> {
                ctx.addLast("ws-ready", new ReadyWebSocketBinder(true));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplex(true, MAX_MESSAGE_PAYLOAD, MESSAGE_FRAGMENT_SIZE));
            };
        }

        @Override
        public void prepareInvocation() {
            if (this.fragmentedFrameBytes == null) {
                this.fragmentedFrameBytes = new byte[MASKED_FRAGMENTED_TEXT_MESSAGE.length][];
            }
            this.fragmentedFrames = new HttpByteBuf[MASKED_FRAGMENTED_TEXT_MESSAGE.length];
            for (int i = 0; i < MASKED_FRAGMENTED_TEXT_MESSAGE.length; i++) {
                this.fragmentedFrameBytes[i] = reusableBytes(this.fragmentedFrameBytes[i], MASKED_FRAGMENTED_TEXT_MESSAGE[i]);
                this.fragmentedFrames[i] = httpByteBuf(this.fragmentedFrameBytes[i]);
            }
        }

        @Override
        public void cleanupInvocation() {
            releaseNetaObjects(this.fragmentedFrames);
            this.fragmentedFrames = null;
        }
    }

    @State(Scope.Thread)
    public static class NettyMessageDecodeState extends AbstractReusableNettyChannelState {
        private io.netty.buffer.ByteBuf[] fragmentedFrames;
        private byte[][]                  fragmentedFrameBytes;

        @Override
        protected EmbeddedChannel newChannel() {
            return new EmbeddedChannel(//
                    new WebSocket13FrameDecoder(true, false, MAX_MESSAGE_PAYLOAD), //
                    new Utf8FrameValidator(), //
                    new WebSocketFrameAggregator(MAX_MESSAGE_PAYLOAD));
        }

        @Override
        protected void prepareInvocation() {
            if (this.fragmentedFrameBytes == null) {
                this.fragmentedFrameBytes = new byte[MASKED_FRAGMENTED_TEXT_MESSAGE.length][];
            }

            this.fragmentedFrames = new io.netty.buffer.ByteBuf[MASKED_FRAGMENTED_TEXT_MESSAGE.length];
            for (int i = 0; i < MASKED_FRAGMENTED_TEXT_MESSAGE.length; i++) {
                this.fragmentedFrameBytes[i] = reusableBytes(this.fragmentedFrameBytes[i], MASKED_FRAGMENTED_TEXT_MESSAGE[i]);
                this.fragmentedFrames[i] = io.netty.buffer.Unpooled.wrappedBuffer(this.fragmentedFrameBytes[i]);
            }
        }

        @Override
        protected void cleanupInvocation() {
            releaseNettyObjects(this.fragmentedFrames);
            this.fragmentedFrames = null;
        }
    }

    private static final AtomicInteger      ADDRESS               = new AtomicInteger(300);
    private static final String             WS_URI                = "ws://example.com/chat";
    private static final String             SERVER_HANDSHAKE_KEY  = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final byte[]             MASK_KEY              = new byte[] { 0x11, 0x22, 0x33, 0x44 };
    private static final int                MAX_MESSAGE_PAYLOAD   = 1024 * 1024;
    private static final int                MESSAGE_FRAGMENT_SIZE = 512;
    private static final int                MULTI_OBJECT_COUNT    = 4;
    private static final String             TEXT_FRAME            = "hello websocket benchmark";
    private static final String             LARGE_TEXT_MESSAGE;
    private static final byte[]             LARGE_TEXT_MESSAGE_BYTES;
    private static final byte[]             TEXT_FRAME_BYTES      = TEXT_FRAME.getBytes(StandardCharsets.UTF_8);
    private static final byte[]             BINARY_FRAME_BYTES    = new byte[4096];
    private static final byte[]             MASKED_TEXT_FRAME;
    private static final byte[]             MASKED_BINARY_FRAME;
    private static final byte[][]           PARTIAL_MASKED_TEXT_FRAME;
    private static final byte[]             STICKY_MASKED_TEXT_FRAMES;
    private static final byte[]             HIXIE_TEXT_FRAME;
    private static final byte[][]           MASKED_FRAGMENTED_TEXT_MESSAGE;
    private              LeakMetricSnapshot before;

    static {
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            textBuilder.append("websocket-message-").append(i).append('-');
        }
        LARGE_TEXT_MESSAGE = textBuilder.toString();
        LARGE_TEXT_MESSAGE_BYTES = LARGE_TEXT_MESSAGE.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < BINARY_FRAME_BYTES.length; i++) {
            BINARY_FRAME_BYTES[i] = (byte) (i & 0xFF);
        }

        MASKED_TEXT_FRAME = buildRfc6455Frame(WebSocketOpcode.TEXT.code(), true, true, MASK_KEY, TEXT_FRAME_BYTES);
        MASKED_BINARY_FRAME = buildRfc6455Frame(WebSocketOpcode.BINARY.code(), true, true, MASK_KEY, BINARY_FRAME_BYTES);
        PARTIAL_MASKED_TEXT_FRAME = splitBytes(MASKED_TEXT_FRAME, 3);
        STICKY_MASKED_TEXT_FRAMES = repeatBytes(MASKED_TEXT_FRAME, MULTI_OBJECT_COUNT);
        HIXIE_TEXT_FRAME = buildHixieTextFrame(TEXT_FRAME_BYTES);

        MASKED_FRAGMENTED_TEXT_MESSAGE = splitToMaskedFrames(LARGE_TEXT_MESSAGE_BYTES, MESSAGE_FRAGMENT_SIZE);
    }

    @Setup(Level.Iteration)
    public void captureBaseline() {
        this.before = LeakMetricSnapshot.capture(ByteBufAllocator.DEFAULT.metric());
    }

    @TearDown(Level.Iteration)
    public void assertNoLeak() {
        this.before.assertRestored(ByteBufAllocator.DEFAULT.metric(), getClass().getSimpleName());
    }

    @Benchmark
    public int neta_handshakeServerRfc6455(NetaServerHandshakeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning(state.request);
        state.request = null;
        int observed = releaseReturned(out);
        if (!WebSocketUtils.isReady(state.pipe().channel())) {
            throw new IllegalStateException("neta websocket server handshake was not marked ready");
        }
        return observed;
    }

    @Benchmark
    public int netty_handshakeServerRfc6455(NettyServerHandshakeState state) {
        ChannelFuture future = state.handshaker.handshake(state.channel(), state.request.retain());
        future.syncUninterruptibly();
        return releaseNettyOutbound(state.channel());
    }

    @Benchmark
    public int neta_handshakeClientRfc6455(NetaClientHandshakeState state) throws Throwable {
        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
        String key = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        int observed = releaseReturned(state.pipe().channel().sendDataAndReturning(request));
        observed += releaseReturned(state.pipe().channel().receiveDataAndReturning(newUpgradeResponse(key)));
        if (!WebSocketUtils.isReady(state.pipe().channel())) {
            throw new IllegalStateException("neta websocket client handshake was not marked ready");
        }
        return observed;
    }

    @Benchmark
    public int netty_handshakeClientRfc6455(NettyClientHandshakeState state) {
        state.handshaker.handshake(state.channel()).syncUninterruptibly();
        HandshakeRequestSnapshot request = captureNettyHandshakeRequest(state.channel());
        try {
            String key = extractSecWebSocketKey(request.requestText);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.UPGRADE, io.netty.handler.codec.http.HttpHeaderValues.WEBSOCKET);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION, io.netty.handler.codec.http.HttpHeaderValues.UPGRADE);
            response.headers().set(io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key));
            try {
                state.handshaker.finishHandshake(state.channel(), response.retain());
            } finally {
                releaseNettyObject(response);
            }
            return request.outboundBytes;
        } finally {
            releaseNettyObject(request);
        }
    }

    @Benchmark
    public int neta_frameEncodeText(NetaFrameEncodeState state) throws Throwable {
        Object[] out = state.pipe().channel().sendDataAndReturning(state.textFrame);
        state.textFrame = null;
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_frameEncodeText(NettyFrameEncodeState state) {
        state.channel().writeOutbound(state.textFrame);
        state.textFrame = null;
        return releaseNettyOutbound(state.channel());
    }

    @Benchmark
    public int neta_frameDecodeMaskedText(NetaFrameDecodeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning(state.maskedTextFrame);
        state.maskedTextFrame = null;
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_frameDecodeMaskedText(NettyFrameDecodeState state) {
        state.channel().writeInbound(state.maskedTextFrame);
        state.maskedTextFrame = null;
        return releaseNettyInbound(state.channel());
    }

    @Benchmark
    public int neta_frameEncodeBinary4k(NetaFrameEncodeState state) throws Throwable {
        Object[] out = state.pipe().channel().sendDataAndReturning(state.binaryFrame);
        state.binaryFrame = null;
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_frameEncodeBinary4k(NettyFrameEncodeState state) {
        state.channel().writeOutbound(state.binaryFrame);
        state.binaryFrame = null;
        return releaseNettyOutbound(state.channel());
    }

    @Benchmark
    public int neta_frameDecodeMaskedBinary4k(NetaFrameDecodeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning(state.maskedBinaryFrame);
        state.maskedBinaryFrame = null;
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_frameDecodeMaskedBinary4k(NettyFrameDecodeState state) {
        state.channel().writeInbound(state.maskedBinaryFrame);
        state.maskedBinaryFrame = null;
        return releaseNettyInbound(state.channel());
    }

    @Benchmark
    public int neta_frameDecodePartialMaskedText(NetaPartialFrameDecodeState state) throws Throwable {
        Object[] out = null;
        for (int i = 0; i < state.chunks.length; i++) {
            out = state.pipe().channel().receiveDataAndReturning(state.chunks[i]);
            state.chunks[i] = null;
        }
        return requireObserved(releaseReturned(out), decodedTextObservation(), "neta partial masked text");
    }

    @Benchmark
    public int netty_frameDecodePartialMaskedText(NettyPartialFrameDecodeState state) {
        for (int i = 0; i < state.chunks.length; i++) {
            state.channel().writeInbound(state.chunks[i]);
            state.chunks[i] = null;
        }
        return requireObserved(releaseNettyInbound(state.channel()), decodedTextObservation(), "netty partial masked text");
    }

    @Benchmark
    public int neta_frameDecodeSticky4MaskedText(NetaStickyFrameDecodeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning(state.stickyFrames);
        state.stickyFrames = null;
        return requireObserved(releaseReturned(out), decodedTextObservation() * MULTI_OBJECT_COUNT, "neta sticky masked text");
    }

    @Benchmark
    public int netty_frameDecodeSticky4MaskedText(NettyStickyFrameDecodeState state) {
        state.channel().writeInbound(state.stickyFrames);
        state.stickyFrames = null;
        return requireObserved(releaseNettyInbound(state.channel()), decodedTextObservation() * MULTI_OBJECT_COUNT, "netty sticky masked text");
    }

    @Benchmark
    public int neta_frameDecodeMultiObject4MaskedText(NetaMultiObjectDecodeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning((Object[]) state.frames);
        state.frames = null;
        return requireObserved(releaseReturned(out), decodedTextObservation() * MULTI_OBJECT_COUNT, "neta multi-object masked text");
    }

    @Benchmark
    public int netty_frameDecodeMultiObject4MaskedText(NettyMultiObjectDecodeState state) {
        state.channel().writeInbound((Object[]) state.frames);
        state.frames = null;
        return requireObserved(releaseNettyInbound(state.channel()), decodedTextObservation() * MULTI_OBJECT_COUNT, "netty multi-object masked text");
    }

    @Benchmark
    public int neta_frameDecodeHixieText(NetaHixieDecodeState state) throws Throwable {
        Object[] out = state.pipe().channel().receiveDataAndReturning(state.frame);
        state.frame = null;
        return requireObserved(releaseReturned(out), decodedTextObservation(), "neta Hixie text decode");
    }

    @Benchmark
    public int netty_frameDecodeHixieText(NettyHixieDecodeState state) {
        state.channel().writeInbound(state.frame);
        state.frame = null;
        return requireObserved(releaseNettyInbound(state.channel()), decodedTextObservation(), "netty Hixie text decode");
    }

    @Benchmark
    public int neta_frameEncodeHixieText(NetaHixieEncodeState state) throws Throwable {
        Object[] out = state.pipe().channel().sendDataAndReturning(state.frame);
        state.frame = null;
        return requirePositive(releaseReturned(out), "neta Hixie text encode");
    }

    @Benchmark
    public int netty_frameEncodeHixieText(NettyHixieEncodeState state) {
        state.channel().writeOutbound(state.frame);
        state.frame = null;
        return requirePositive(releaseNettyOutbound(state.channel()), "netty Hixie text encode");
    }

    @Benchmark
    public int neta_messageEncodeTextAutoFragment(NetaMessageEncodeState state) throws Throwable {
        Object[] out = state.pipe().channel().sendDataAndReturning(state.textMessage);
        state.textMessage = null;
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_messageEncodeTextAutoFragment(NettyMessageEncodeState state) {
        io.netty.handler.codec.http.websocketx.WebSocketFrame[] fragmentedFrames = buildNettyFragmentedTextFrames(LARGE_TEXT_MESSAGE_BYTES, MESSAGE_FRAGMENT_SIZE);
        for (int i = 0; i < fragmentedFrames.length; i++) {
            state.channel().writeOutbound(fragmentedFrames[i]);
            fragmentedFrames[i] = null;
        }
        return releaseNettyOutbound(state.channel());
    }

    @Benchmark
    public int neta_messageDecodeFragmentedText(NetaMessageDecodeState state) throws Throwable {
        Object[] out = null;
        for (int i = 0; i < state.fragmentedFrames.length; i++) {
            out = state.pipe().channel().receiveDataAndReturning(state.fragmentedFrames[i]);
            state.fragmentedFrames[i] = null;
        }
        return releaseReturned(out);
    }

    @Benchmark
    public int netty_messageDecodeFragmentedText(NettyMessageDecodeState state) {
        for (int i = 0; i < state.fragmentedFrames.length; i++) {
            state.channel().writeInbound(state.fragmentedFrames[i]);
            state.fragmentedFrames[i] = null;
        }
        return releaseNettyInbound(state.channel());
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder().include(WebSocketBenchmark.class.getSimpleName()).build();
        new Runner(options).run();
    }

    private static VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer initializer, VrtSoConfig config, int addressId) throws IOException {
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(addressId), initializer, config);
        return new VirtualPipe(channel);
    }

    private static FullHttpRequest newNetaServerHandshakeRequest() {
        net.hasor.neta.codec.http.DefaultFullHttpRequest request = new net.hasor.neta.codec.http.DefaultFullHttpRequest(net.hasor.neta.codec.http.HttpVersion.HTTP_1_1, net.hasor.neta.codec.http.HttpMethod.GET, "/chat");
        request.setHeader(HttpHeaderNames.HOST, "example.com");
        request.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        request.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(WebSocketVersion.V13.code()));
        request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, SERVER_HANDSHAKE_KEY);
        return request;
    }

    private static void closePipe(VirtualPipe pipe) {
        if (pipe == null) {
            return;
        }

        VrtChannel channel = pipe.channel();
        if (channel != null && !channel.isClose()) {
            channel.closeNow();
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
        return DefaultHttpByteBuf.newInstance(ByteBuf.wrap(value), 0);
    }

    private static byte[] reusableBytes(byte[] target, byte[] template) {
        if (template == null) {
            return null;
        }
        byte[] result = target;
        if (result == null || result.length != template.length) {
            result = new byte[template.length];
        }
        System.arraycopy(template, 0, result, 0, template.length);
        return result;
    }

    private static byte[][] splitBytes(byte[] value, int parts) {
        byte[][] result = new byte[parts][];
        int offset = 0;
        for (int i = 0; i < parts; i++) {
            int remaining = value.length - offset;
            int length = (remaining + parts - i - 1) / (parts - i);
            result[i] = new byte[length];
            System.arraycopy(value, offset, result[i], 0, length);
            offset += length;
        }
        return result;
    }

    private static byte[] repeatBytes(byte[] value, int count) {
        byte[] result = new byte[value.length * count];
        for (int i = 0; i < count; i++) {
            System.arraycopy(value, 0, result, i * value.length, value.length);
        }
        return result;
    }

    private static byte[] buildHixieTextFrame(byte[] payload) {
        byte[] result = new byte[payload.length + 2];
        result[0] = 0x00;
        System.arraycopy(payload, 0, result, 1, payload.length);
        result[result.length - 1] = (byte) 0xFF;
        return result;
    }

    private static int decodedTextObservation() {
        return 1 + TEXT_FRAME_BYTES.length;
    }

    private static int requireObserved(int actual, int expected, String scenario) {
        if (actual != expected) {
            throw new IllegalStateException(scenario + " observed " + actual + ", expected " + expected);
        }
        return actual;
    }

    private static int requirePositive(int actual, String scenario) {
        if (actual <= 0) {
            throw new IllegalStateException(scenario + " produced no output");
        }
        return actual;
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

    private static io.netty.handler.codec.http.websocketx.WebSocketFrame[] buildNettyFragmentedTextFrames(byte[] payload, int chunkSize) {
        List<io.netty.handler.codec.http.websocketx.WebSocketFrame> frames = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            int len = Math.min(chunkSize, payload.length - offset);
            boolean fin = offset + len >= payload.length;
            io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(payload, offset, len);
            if (first) {
                frames.add(new TextWebSocketFrame(fin, 0, buf));
                first = false;
            } else {
                frames.add(new ContinuationWebSocketFrame(fin, 0, buf));
            }
            offset += len;
        }
        return frames.toArray(new io.netty.handler.codec.http.websocketx.WebSocketFrame[0]);
    }

    private static void clearNettyChannel(EmbeddedChannel channel) {
        if (channel == null) {
            return;
        }
        Object inbound;
        while ((inbound = channel.readInbound()) != null) {
            releaseNettyObject(inbound);
        }
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            releaseNettyObject(outbound);
        }
        channel.checkException();
    }

    private static void releaseNetaObject(Object obj) {
        if (obj != null) {
            measureAndRelease(obj);
        }
    }

    private static void releaseNetaObjects(Object[] objects) {
        if (objects == null) {
            return;
        }
        for (Object object : objects) {
            releaseNetaObject(object);
        }
    }

    private static void releaseNettyObjects(Object[] objects) {
        if (objects == null) {
            return;
        }
        for (Object object : objects) {
            releaseNettyObject(object);
        }
    }

    private static int releaseReturned(Object[] out) {
        int observed = 0;
        if (out != null) {
            for (Object item : out) {
                observed += measureAndRelease(item);
            }
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
        if (item instanceof Integer) {
            return (Integer) item;
        }
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

    private static final class ReadyWebSocketBinder implements ProtoDuplex<Object, Object, Object, Object> {
        private final boolean          server;
        private final WebSocketVersion version;

        private ReadyWebSocketBinder(boolean server) {
            this(server, WebSocketVersion.V13);
        }

        private ReadyWebSocketBinder(boolean server, WebSocketVersion version) {
            this.server = server;
            this.version = version;
        }

        @Override
        public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
            WebSocketContext wsContext = new WebSocketContextImpl(this.server, null, this.version.code(), "/chat", "example.com", "http://example.com", Collections.<String>emptyList());
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
        private final VrtChannel channel;

        private VirtualPipe(VrtChannel channel) {
            this.channel = channel;
        }

        private VrtChannel channel() {
            return this.channel;
        }
    }
}
