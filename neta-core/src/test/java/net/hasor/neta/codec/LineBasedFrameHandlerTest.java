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
package net.hasor.neta.codec;
import java.util.ArrayDeque;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LineBasedFrameHandlerTest {

    // ===========================================
    // stripDelimiter=true (default): delimiter should be STRIPPED
    // ===========================================

    @Test
    public void lineBasedFrame_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc".getBytes()));
        assert rcvData.isEmpty();
        channel.onReceive(ByteBuf.wrap("\r\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc");
    }

    @Test
    public void lineBasedFrame_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc\r\n123".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc");
    }

    @Test
    public void lineBasedFrame_3() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        // transfer channel
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        //
        channel.onReceive(ByteBuf.wrap("abc\r\n123".getBytes()));
        channel.onReceive(ByteBuf.wrap("\r\n".getBytes()));
        assert rcvData.size() == 2;
        assert new String(rcvData.poll().asByteArray()).equals("abc");
        assert new String(rcvData.poll().asByteArray()).equals("123");
    }

    // ===========================================
    // stripDelimiter=false: delimiter should be KEPT
    // ===========================================

    @Test
    public void lineBasedFrame_noStrip_1() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(Integer.MAX_VALUE, false));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("abc\r\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc\r\n");
    }

    @Test
    public void lineBasedFrame_noStrip_2() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(Integer.MAX_VALUE, false));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("abc\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc\n");
    }

    @Test
    public void lineBasedFrame_noStrip_multiLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(Integer.MAX_VALUE, false));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("abc\r\n123\n".getBytes()));
        assert rcvData.size() == 2;
        assert new String(rcvData.poll().asByteArray()).equals("abc\r\n");
        assert new String(rcvData.poll().asByteArray()).equals("123\n");
    }

    // ===========================================
    // \n only delimiter (no \r)
    // ===========================================

    @Test
    public void lineBasedFrame_lf_only() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("abc\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("abc");
    }

    @Test
    public void lineBasedFrame_lf_only_multiLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("abc\n123\n".getBytes()));
        assert rcvData.size() == 2;
        assert new String(rcvData.poll().asByteArray()).equals("abc");
        assert new String(rcvData.poll().asByteArray()).equals("123");
    }

    // ===========================================
    // mixed \n and \r\n delimiters
    // ===========================================

    @Test
    public void lineBasedFrame_mixedDelimiters() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("aaa\r\nbbb\nccc\r\n".getBytes()));
        assert rcvData.size() == 3;
        assert new String(rcvData.poll().asByteArray()).equals("aaa");
        assert new String(rcvData.poll().asByteArray()).equals("bbb");
        assert new String(rcvData.poll().asByteArray()).equals("ccc");
    }

    // ===========================================
    // empty line
    // ===========================================

    @Test
    public void lineBasedFrame_emptyLine_crlf() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("\r\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("");
    }

    @Test
    public void lineBasedFrame_emptyLine_lf() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("");
    }

    // ===========================================
    // maxLength exceeded
    // ===========================================

    @Test
    public void lineBasedFrame_maxLength_exceeded_incomplete() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(5));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // send 10 bytes without line ending, exceeds maxLength=5
        // the handler throws TooLongFrameException internally, no data produced
        channel.onReceive(ByteBuf.wrap("1234567890".getBytes()));
        assert rcvData.isEmpty();
    }

    @Test
    public void lineBasedFrame_maxLength_exceeded_complete() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(5));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // send a complete line that exceeds maxLength=5
        // the handler throws TooLongFrameException internally, no data produced
        channel.onReceive(ByteBuf.wrap("1234567890\n".getBytes()));
        assert rcvData.isEmpty();
    }

    @Test
    public void lineBasedFrame_maxLength_withinLimit() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(5));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // send a line within maxLength=5
        channel.onReceive(ByteBuf.wrap("12345\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("12345");
    }

    // ===========================================
    // fragmented data across multiple receives
    // ===========================================

    @Test
    public void lineBasedFrame_fragmented_byteByByte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // send byte by byte
        for (byte b : "hello\n".getBytes()) {
            channel.onReceive(ByteBuf.wrap(new byte[] { b }));
        }
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("hello");
    }

    @Test
    public void lineBasedFrame_multipleLines_singleBuffer() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("line1\nline2\nline3\n".getBytes()));
        assert rcvData.size() == 3;
        assert new String(rcvData.poll().asByteArray()).equals("line1");
        assert new String(rcvData.poll().asByteArray()).equals("line2");
        assert new String(rcvData.poll().asByteArray()).equals("line3");
    }

    @Test
    public void lineBasedFrame_noLineEnding() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // no line ending, so no output
        channel.onReceive(ByteBuf.wrap("hello".getBytes()));
        assert rcvData.isEmpty();
    }

    @Test
    public void lineBasedFrame_maxLength_constructor() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(10));
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        channel.onReceive(ByteBuf.wrap("1234567890\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("1234567890");
    }

    // Regression test: when multiple buffers are queued and the first buffer is fully consumed
    // by line extraction, the second buffer's data must not be lost.
    // This verifies the fix for the peekArray reuse bug where stale buffer references
    // caused incorrect skipMessage calls leading to data loss.
    @Test
    public void lineBasedFrame_multiBuffer_firstFullyConsumed_noDataLoss() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // buf0 = "aaa\n" (fully consumed after first line extraction)
        // buf1 = "bbb\nccc\n" (contains two more lines)
        // All 3 lines must be extracted without data loss
        channel.onReceive(ByteBuf.wrap("aaa\n".getBytes()), ByteBuf.wrap("bbb\nccc\n".getBytes()));
        assert rcvData.size() == 3 : "expected 3 lines but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("aaa");
        assert new String(rcvData.poll().asByteArray()).equals("bbb");
        assert new String(rcvData.poll().asByteArray()).equals("ccc");
    }

    // Regression test: multiple buffers where each buffer contains exactly one complete line.
    // After consuming buffer 0, the handler must correctly re-peek and process buffer 1.
    @Test
    public void lineBasedFrame_multiBuffer_eachOneCompleteLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // Each buffer is exactly one line; the first buffer is fully consumed each iteration
        channel.onReceive(ByteBuf.wrap("first\n".getBytes()), ByteBuf.wrap("second\n".getBytes()), ByteBuf.wrap("third\n".getBytes()));
        assert rcvData.size() == 3 : "expected 3 lines but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("first");
        assert new String(rcvData.poll().asByteArray()).equals("second");
        assert new String(rcvData.poll().asByteArray()).equals("third");
    }

    // ====================================================
    // Cross-buffer \r\n: \r at end of one buffer, \n at start of next
    // ====================================================

    @Test
    public void lineBasedFrame_crossBuffer_crLf_stripDelimiter() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler()); // stripDelimiter=true
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // \r at end of buf0, \n at start of buf1
        channel.onReceive(ByteBuf.wrap("hello\r".getBytes()), ByteBuf.wrap("\nworld\n".getBytes()));
        assert rcvData.size() == 2 : "expected 2 but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("hello");
        assert new String(rcvData.poll().asByteArray()).equals("world");
    }

    @Test
    public void lineBasedFrame_crossBuffer_crLf_keepDelimiter() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler(Integer.MAX_VALUE, false)); // stripDelimiter=false
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // \r at end of buf0, \n at start of buf1 - delimiter should be preserved
        channel.onReceive(ByteBuf.wrap("hello\r".getBytes()), ByteBuf.wrap("\nworld\n".getBytes()));
        assert rcvData.size() == 2 : "expected 2 but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("hello\r\n");
        assert new String(rcvData.poll().asByteArray()).equals("world\n");
    }

    @Test
    public void lineBasedFrame_crossBuffer_crLf_emptyLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // Empty line with \r\n split across buffers
        channel.onReceive(ByteBuf.wrap("\r".getBytes()), ByteBuf.wrap("\n".getBytes()));
        assert rcvData.size() == 1 : "expected 1 but got " + rcvData.size();
        assert rcvData.poll().asByteArray().length == 0; // empty line
    }

    @Test
    public void lineBasedFrame_crossBuffer_crLf_threeBuffers() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // Content split across 3 buffers: "hel" + "lo\r" + "\nworld\n"
        channel.onReceive(ByteBuf.wrap("hel".getBytes()), ByteBuf.wrap("lo\r".getBytes()), ByteBuf.wrap("\nworld\n".getBytes()));
        assert rcvData.size() == 2 : "expected 2 but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("hello");
        assert new String(rcvData.poll().asByteArray()).equals("world");
    }

    @Test
    public void lineBasedFrame_crossBuffer_crLf_multipleLines() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // Two lines, both with cross-buffer \r\n
        channel.onReceive(ByteBuf.wrap("aa\r".getBytes()), ByteBuf.wrap("\nbb\r".getBytes()), ByteBuf.wrap("\n".getBytes()));
        assert rcvData.size() == 2 : "expected 2 but got " + rcvData.size();
        assert new String(rcvData.poll().asByteArray()).equals("aa");
        assert new String(rcvData.poll().asByteArray()).equals("bb");
    }

    @Test
    public void lineBasedFrame_noCrossBuffer_bareCarriageReturn() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ctx.addLastDecoder(new LineBasedFrameHandler());
        }, VrtSoConfig.asDefault());

        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        channel.subscribe(d -> rcvData.offer((ByteBuf) d.getData()));

        // Bare \r is NOT a line ending; it should be treated as content
        channel.onReceive(ByteBuf.wrap("hello\rworld\n".getBytes()));
        assert rcvData.size() == 1;
        assert new String(rcvData.poll().asByteArray()).equals("hello\rworld");
    }
}