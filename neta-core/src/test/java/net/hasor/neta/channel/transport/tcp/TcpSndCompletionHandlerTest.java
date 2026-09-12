/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.tcp;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import net.hasor.neta.channel.NetMonitor;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoSndContext;
import net.hasor.neta.channel.WriteRetryTestHelper;

/**
 * Regression tests for {@link TcpSndCompletionHandler}.
 */
public class TcpSndCompletionHandlerTest {
    @Test
    public void completed_ignoresLateCallbackAfterQueuePurged() throws Exception {
        SoContextService context = WriteRetryTestHelper.createContextService();
        AsynchronousSocketChannel socketChannel = AsynchronousSocketChannel.open();
        try {
            TcpAsyncChannel asyncChannel = new TcpAsyncChannel(11L, socketChannel, context, null, TcpSoConfig.TCP());
            TcpSndCompletionHandler handler = new TcpSndCompletionHandler(asyncChannel, context, new NetMonitor());

            ByteBuffer swapBuffer = getField(handler, "sndSwapBuf", ByteBuffer.class);
            ((Buffer) swapBuffer).clear();
            ((Buffer) swapBuffer).flip();

            AtomicBoolean writing = getField(handler, "writing", AtomicBoolean.class);
            writing.set(true);

            handler.completed(0, new SoSndContext());

            assert !writing.get() : "late callback on empty queue should release writer flag";
        } finally {
            socketChannel.close();
            context.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
