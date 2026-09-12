/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;

import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LengthFieldBasedFrameHandlerTest {
    private static void closeTransport(NetManager neta, VrtTransfer transfer) throws java.io.IOException {
        if (transfer != null) {
            transfer.close();
        }
        if (neta != null) {
            neta.shutdown();
        }
    }

    private static void assertNextFrame(Queue<ByteBuf> rcvData, int readableBytes, byte[] expected) {
        ByteBuf buf = rcvData.poll();
        if (buf == null) {
            throw new AssertionError("expected frame but queue is empty");
        }
        try {
            assert buf.readableBytes() == readableBytes;
            assert Objects.deepEquals(buf.asByteArray(), expected);
        } finally {
            buf.free();
        }
    }

    private void coderTest1_case1(Queue<ByteBuf> rcvData) {
        assertNextFrame(rcvData, 6, new byte[] { 0, 4, 1, 2, 3, 4 });
        assertNextFrame(rcvData, 4, new byte[] { 0, 2, 1, 2 });
        assertNextFrame(rcvData, 10, new byte[] { 0, 8, 1, 2, 3, 4, 5, 6, 7, 8 });
        assertNextFrame(rcvData, 2, new byte[] { 0, 0 });
        assertNextFrame(rcvData, 3, new byte[] { 0, 1, 1 });
    }

    private void coderTest1_case2(Queue<ByteBuf> rcvData) {
        assertNextFrame(rcvData, 4, new byte[] { 1, 2, 3, 4 });
        assertNextFrame(rcvData, 2, new byte[] { 1, 2 });
        assertNextFrame(rcvData, 8, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertNextFrame(rcvData, 0, new byte[0]);
        assertNextFrame(rcvData, 1, new byte[] { 1 });
    }

    @Test
    public void coder_1_case1() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 0, 4, 1, 2, 3, 4, 0, 2, 1, 2, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));
            assert rcvData.size() == 5;
            coderTest1_case1(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_1_case2() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 0, 4, 1, 2, 3, 4, 0, 2, 1, 2, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_1_case3() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 1, 0, 4, 1, 2, 3, 4, 2, 0, 2, 1, 2, 3, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 4, 0, 0, 5, 0, 1, 1, 6, 99, 99 }));
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_2_case1() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            byte[] bytes1 = new byte[] { 0, 4, 1, 2, 3, 4, 0, 2, 1, 2, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 };
            for (byte b : bytes1) {
                client.sendData(ByteBuf.wrap(new byte[] { b }));
            }
            assert rcvData.size() == 5;
            coderTest1_case1(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_2_case2() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            byte[] bytes1 = new byte[] { 0, 4, 1, 2, 3, 4, 0, 2, 1, 2, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 };
            for (byte b : bytes1) {
                client.sendData(ByteBuf.wrap(new byte[] { b }));
            }
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_2_case3() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            byte[] bytes1 = new byte[] { 1, 0, 4, 1, 2, 3, 4, 2, 0, 2, 1, 2, 3, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 4, 0, 0, 5, 0, 1, 1, 6, 99, 99 };
            for (byte b : bytes1) {
                client.sendData(ByteBuf.wrap(new byte[] { b }));
            }
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_3_case1() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 1 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 3, 4, 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 1, 2, 0, 8, 1, 2, 3 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));
            assert rcvData.size() == 5;
            coderTest1_case1(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_3_case2() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 1 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 3, 4, 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 1, 2, 0, 8, 1, 2, 3 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 0, 0, 0, 1, 1, 99, 99 }));
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    @Test
    public void coder_3_case3() throws Throwable {
        NetManager neta = new NetManager();
        VrtTransfer transfer = null;
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    1, ByteOrder.BIG_ENDIAN, 2, 3);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        //
        try {
            transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            Queue<ByteBuf> rcvData = new ArrayDeque<>();
            server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

            client.sendData(ByteBuf.wrap(new byte[] { 1, 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 1 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 3, 4, 2, 0 }));
            client.sendData(ByteBuf.wrap(new byte[] { 2, 1, 2, 3, 0, 8, 1, 2, 3 }));
            client.sendData(ByteBuf.wrap(new byte[] { 4, 5, 6, 7, 8, 4, 0, 0, 5, 0, 1, 1 }));
            assert rcvData.size() == 5;
            coderTest1_case2(rcvData);
        } finally {
            closeTransport(neta, transfer);
        }
    }

    // ===========================================
    // LITTLE_ENDIAN byte order
    // ===========================================

    @Test
    public void coder_littleEndian() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.LITTLE_ENDIAN, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // LE: length 4 -> bytes [4, 0]
        client.sendData(ByteBuf.wrap(new byte[] { 4, 0, 1, 2, 3, 4 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 4, new byte[] { 1, 2, 3, 4 });
    }

    // ===========================================
    // Different length field sizes
    // ===========================================

    @Test
    public void coder_lengthField_1byte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 1, 1);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // 1-byte length field: length=3
        client.sendData(ByteBuf.wrap(new byte[] { 3, 10, 20, 30 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 3, new byte[] { 10, 20, 30 });
    }

    @Test
    public void coder_lengthField_3bytes() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 3, 3);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // 3-byte length field: length=2 -> [0, 0, 2]
        client.sendData(ByteBuf.wrap(new byte[] { 0, 0, 2, 10, 20 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 2, new byte[] { 10, 20 });
    }

    @Test
    public void coder_lengthField_4bytes() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 4, 4);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // 4-byte length field: length=3 -> [0, 0, 0, 3]
        client.sendData(ByteBuf.wrap(new byte[] { 0, 0, 0, 3, 10, 20, 30 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 3, new byte[] { 10, 20, 30 });
    }

    // ===========================================
    // lengthAdjustment parameter
    // ===========================================

    @Test
    public void coder_lengthAdjustment_positive() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            // length field value = body length - 2, so lengthAdjustment = +2
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // length field says 2, but actual body is 2+2=4 bytes
        client.sendData(ByteBuf.wrap(new byte[] { 0, 2, 1, 2, 3, 4 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 4, new byte[] { 1, 2, 3, 4 });
    }

    @Test
    public void coder_lengthAdjustment_negative() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            // length field includes header length, so lengthAdjustment = -2
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2, -2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // length field says 6 (includes 2-byte header), actual body = 6-2=4 bytes
        client.sendData(ByteBuf.wrap(new byte[] { 0, 6, 1, 2, 3, 4 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 4, new byte[] { 1, 2, 3, 4 });
    }

    // ===========================================
    // frameMaxSize exceeded
    // ===========================================

    @Test
    public void coder_frameMaxSize_exceeded() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            // frameMaxSize = 5
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 0, 0, 5);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // frame says length=10, exceeds maxSize=5
        // the handler throws TooLongFrameException internally, no data produced
        client.sendData(ByteBuf.wrap(new byte[] { 0, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }));
        assert rcvData.isEmpty();
    }

    // ===========================================
    // Negative frame length
    // ===========================================

    @Test
    public void coder_negativeFrameLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            // lengthAdjustment = -100, making effective length negative
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 0, -100);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // field says length=2, adjusted = 2 + (-100) = -98
        // the handler throws BadFrameException internally, no data produced
        client.sendData(ByteBuf.wrap(new byte[] { 0, 2, 1, 2 }));
        assert rcvData.isEmpty();
    }

    // ===========================================
    // Constructor validation
    // ===========================================

    @Test(expected = NullPointerException.class)
    public void constructor_nullByteOrder() {
        new LengthFieldBasedFrameHandler(0, null, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_negativeLengthFieldOffset() {
        new LengthFieldBasedFrameHandler(-1, ByteOrder.BIG_ENDIAN, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_zeroLengthFieldLength() {
        new LengthFieldBasedFrameHandler(0, ByteOrder.BIG_ENDIAN, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_negativeInitialBytesToStrip() {
        new LengthFieldBasedFrameHandler(0, ByteOrder.BIG_ENDIAN, 2, -1);
    }

    // ===========================================
    // Zero-length body
    // ===========================================

    @Test
    public void coder_zeroLengthBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastEncoder("", handler);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // length=0, strip header -> empty frame
        client.sendData(ByteBuf.wrap(new byte[] { 0, 0 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 0, new byte[0]);
    }

    // ===========================================
    // Decoder direction tests
    // ===========================================

    @Test
    public void decoder_basic() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        client.sendData(ByteBuf.wrap(new byte[] { 0, 4, 1, 2, 3, 4 }));
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 4, new byte[] { 1, 2, 3, 4 });
    }

    @Test
    public void decoder_multipleFrames() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // two frames in one send
        client.sendData(ByteBuf.wrap(new byte[] {//
                0, 3, 10, 20, 30,  //
                0, 2, 40, 50 }));
        assert rcvData.size() == 2;

        assertNextFrame(rcvData, 5, new byte[] { 0, 3, 10, 20, 30 });
        assertNextFrame(rcvData, 4, new byte[] { 0, 2, 40, 50 });
    }

    @Test
    public void decoder_fragmented_byteByByte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), (ctx) -> {
            LengthFieldBasedFrameHandler handler = new LengthFieldBasedFrameHandler(//
                    0, ByteOrder.BIG_ENDIAN, 2, 2);
            ctx.addLastDecoder("", handler);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), (ctx) -> {
            ProtoHelper.standard().config(ctx);
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<ByteBuf> rcvData = new ArrayDeque<>();
        server.subscribe(SubscribeMode.SYNC, d -> rcvData.offer((ByteBuf) d.getData()));

        // send byte by byte
        byte[] data = new byte[] { 0, 3, 10, 20, 30 };
        for (byte b : data) {
            client.sendData(ByteBuf.wrap(new byte[] { b }));
        }
        assert rcvData.size() == 1;

        assertNextFrame(rcvData, 3, new byte[] { 10, 20, 30 });
    }
}
