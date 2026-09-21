/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpRequestDecoder;
import net.hasor.neta.leak.LeakMetricSnapshot;
import org.openjdk.jmh.annotations.*;

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
public class HttpHeaderAccessBenchmark {
    @Param({ "4", "12", "32", "64" })
    public int    headerCount;
    @Param({ "decode", "lookup", "first", "repeat" })
    public String access;

    private final byte[][]           packets  = new byte[16][];
    private final String[][]         queries  = new String[16][];
    private final int[]              expected = new int[16];
    private       int                cursor;
    private       NetManager         manager;
    private       VrtChannel         channel;
    private       EmbeddedChannel    netty;
    private       LeakMetricSnapshot before;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        for (int variant = 0; variant < this.packets.length; variant++) {
            String[] names = new String[this.headerCount];
            String[] values = new String[this.headerCount];
            StringBuilder wire = new StringBuilder("GET /headers/" + variant + " HTTP/1.1\r\n");
            for (int i = 0; i < this.headerCount; i++) {
                names[i] = "X-" + variant + '-' + i + "-a".repeat(1 + (i + variant) % 7);
                values[i] = "value-" + variant + '-' + i;
                if (i == 1) {
                    names[i] = "Host";
                    values[i] = "headers-" + variant + ".example";
                }
                if (i == this.headerCount - 2) {
                    names[i] = names[0].toLowerCase(Locale.ROOT);
                }
                wire.append(names[i]).append(": ").append(values[i]).append("\r\n");
            }
            this.packets[variant] = wire.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
            this.queries[variant] = new String[] { names[0].toUpperCase(Locale.ROOT), names[this.headerCount / 2 - 1], names[this.headerCount - 1], "X-absent-" + variant };
            int value = this.headerCount;
            if ("lookup".equals(this.access)) {
                value += 3;
            } else if (!"decode".equals(this.access)) {
                int passes = "repeat".equals(this.access) ? 4 : 1;
                value += passes * (values[0].hashCode() + values[this.headerCount / 2 - 1].hashCode() + values[this.headerCount - 1].hashCode());
            }
            this.expected[variant] = value;
        }
        this.manager = new NetManager();
        this.channel = (VrtChannel) this.manager.connectSync(new VrtSocketAddress(183), ctx -> ctx.addLastDecoder("decoder", new HttpRequestDecoder()), VrtSoConfig.asServer());
        this.netty = new EmbeddedChannel(new io.netty.handler.codec.http.HttpRequestDecoder());
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        try {
            this.netty.finishAndReleaseAll();
        } finally {
            this.manager.shutdown();
        }
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
    public int neta_decodeAndAccess() throws Throwable {
        int index = this.cursor++ & 15;
        Object[] out = this.channel.receiveDataAndReturning(ByteBuf.wrap(this.packets[index]));
        int observed = 0;
        try {
            for (Object item : out) {
                if (item instanceof HttpHeaders headers) {
                    observed += headers.headerSize();
                    if ("lookup".equals(this.access)) {
                        for (String name : this.queries[index]) {
                            observed += headers.containsHeader(name) ? 1 : 0;
                        }
                    } else if (!"decode".equals(this.access)) {
                        for (int pass = 0; pass < ("repeat".equals(this.access) ? 4 : 1); pass++) {
                            for (String name : this.queries[index]) {
                                String value = headers.getString(name);
                                observed += value != null ? value.hashCode() : 0;
                            }
                        }
                    }
                }
            }
            return observed;
        } finally {
            for (Object item : out) {
                if (item instanceof HttpObject object) {
                    object.release();
                } else if (item instanceof ByteBuf bytes) {
                    bytes.release();
                }
            }
        }
    }

    @Benchmark
    public int netty_decodeAndAccess() {
        int index = this.cursor++ & 15;
        this.netty.writeInbound(Unpooled.wrappedBuffer(this.packets[index]));
        int observed = 0;
        Object item;
        while ((item = this.netty.readInbound()) != null) {
            try {
                if (item instanceof io.netty.handler.codec.http.HttpMessage message) {
                    io.netty.handler.codec.http.HttpHeaders headers = message.headers();
                    observed += headers.size();
                    if ("lookup".equals(this.access)) {
                        for (String name : this.queries[index]) {
                            observed += headers.contains(name) ? 1 : 0;
                        }
                    } else if (!"decode".equals(this.access)) {
                        for (int pass = 0; pass < ("repeat".equals(this.access) ? 4 : 1); pass++) {
                            for (String name : this.queries[index]) {
                                String value = headers.get(name);
                                observed += value != null ? value.hashCode() : 0;
                            }
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(item);
            }
        }
        return observed;
    }

    int expectedNext() {
        return this.expected[this.cursor & 15];
    }
}
