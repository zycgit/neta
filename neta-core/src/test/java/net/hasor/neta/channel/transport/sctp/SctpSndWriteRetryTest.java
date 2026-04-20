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
package net.hasor.neta.channel.transport.sctp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;

import org.junit.Test;

import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoSndContext;
import net.hasor.neta.channel.WriteRetryTestHelper;

/**
 * Unit tests for the write-timeout retry parameters added to {@link SctpSoConfig}
 * and the corresponding retry logic in {@link SctpWriteTask}.
 * <p>The config-parameter tests are always executed. The write-task behavioral tests
 * require {@code com.sun.nio.sctp.SctpChannel} to be loadable (i.e., on Linux with
 * lksctp-tools installed). They are silently skipped on platforms without SCTP support
 * (e.g., stock macOS), consistent with the pattern used in other SCTP tests.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
public class SctpSndWriteRetryTest {

    // -------------------------------------------------------------------------
    // Section 1 – SctpSoConfig parameter defaults & setters (no SCTP needed)
    // -------------------------------------------------------------------------

    private static boolean isSctpAvailable() {
        try {
            Class.forName("com.sun.nio.sctp.SctpChannel");
            com.sun.nio.sctp.SctpChannel ch = com.sun.nio.sctp.SctpChannel.open();
            ch.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SctpMessage makeSctpMessage() {
        return SctpMessage.of(com.sun.nio.sctp.MessageInfo.createOutgoing(null, 0), net.hasor.neta.bytebuf.ByteBuf.wrap(new byte[] { 1, 2, 3 }));
    }

    private static SctpWriteTask buildTask(SctpSoConfig cfg) throws Exception {
        SoContextService ctx = WriteRetryTestHelper.createContextService();
        NetChannel channel = WriteRetryTestHelper.createNetChannel(cfg, ctx);
        // SctpChannel and SoSndContext are not accessed by handleException on the
        // InterruptedByTimeoutException path, so null is safe here.
        return new SctpWriteTask(channel, null, null, ctx);
    }

    // -------------------------------------------------------------------------
    // Section 2 – SctpWriteTask retry logic (via reflection, SCTP platform required)
    // -------------------------------------------------------------------------

    private static boolean invokeHandleTimeout(SctpWriteTask task) throws Exception {
        Method m = SctpWriteTask.class.getDeclaredMethod("handleException", Throwable.class, SoSndContext.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(task, new InterruptedByTimeoutException(), null);
    }

    private static void setSendData(SctpWriteTask task, SctpMessage msg) throws Exception {
        Field f = SctpWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        f.set(task, msg);
    }

    private static SctpMessage getSendData(SctpWriteTask task) throws Exception {
        Field f = SctpWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        return (SctpMessage) f.get(task);
    }

    private static int getTimeoutRetryCnt(SctpWriteTask task) throws Exception {
        Field f = SctpWriteTask.class.getDeclaredField("timeoutRetryCnt");
        f.setAccessible(true);
        return (int) f.get(task);
    }

    private static ByteBuffer getSndSwapBuf(SctpWriteTask task) throws Exception {
        Field f = SctpWriteTask.class.getDeclaredField("sndSwapBuf");
        f.setAccessible(true);
        return (ByteBuffer) f.get(task);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Test
    public void config_defaultValues() {
        SctpSoConfig cfg = new SctpSoConfig();
        assert cfg.getSndWriteRetryCount() == 0 : "default sndWriteRetryCount should be 0";
        assert cfg.getSndWriteRetryIntervalMs() == 50 : "default sndWriteRetryIntervalMs should be 50";
    }

    @Test
    public void config_setters() {
        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSndWriteRetryCount(4);
        cfg.setSndWriteRetryIntervalMs(120);
        assert cfg.getSndWriteRetryCount() == 4;
        assert cfg.getSndWriteRetryIntervalMs() == 120;
    }

    @Test
    public void config_setterPreservesOtherFields() {
        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSwapRcvBuf(32768);
        cfg.setSwapSndBuf(16384);
        cfg.setSndWriteRetryCount(2);
        cfg.setSndWriteRetryIntervalMs(75);
        assert cfg.getSwapRcvBuf() == 32768;
        assert cfg.getSwapSndBuf() == 16384;
        assert cfg.getSndWriteRetryCount() == 2;
        assert cfg.getSndWriteRetryIntervalMs() == 75;
    }

    @Test
    public void writeTask_usesConfiguredSwapSndBuf() throws Exception {
        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSwapSndBuf(8192);

        SctpWriteTask task = buildTask(cfg);
        assert getSndSwapBuf(task).capacity() == 8192 : "sndSwapBuf should honor swapSndBuf";
    }

    /**
     * When {@code sndWriteRetryCount == 0} (default), a write timeout must NOT
     * schedule a retry: {@code handleException} returns {@code false} immediately
     * and sets {@code sendData} to {@code null}.
     */
    @Test
    public void writeTask_noRetry_timeoutDiscardsImmediately() throws Exception {
        if (!isSctpAvailable()) {
            System.out.println("[SKIP] SCTP not available on this platform – skipping writeTask test");
            return;
        }

        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSndWriteRetryCount(0);

        SctpWriteTask task = buildTask(cfg);
        setSendData(task, makeSctpMessage());

        boolean keepRetrying = invokeHandleTimeout(task);

        assert !keepRetrying : "retryCount=0 → should NOT retry (return false)";
        assert getSendData(task) == null : "retryCount=0 → sendData must be null (discarded)";
        assert getTimeoutRetryCnt(task) == 0 : "retryCount=0 → counter stays at 0";
        assert task.getDelayTime() == 0 : "retryCount=0 → no delay should be scheduled";
    }

    /**
     * When {@code sndWriteRetryCount == N}, the first N timeouts schedule retries
     * and the (N+1)-th discards.
     */
    @Test
    public void writeTask_retryCount3_retriesNTimesThenDiscards() throws Exception {
        if (!isSctpAvailable()) {
            System.out.println("[SKIP] SCTP not available on this platform – skipping writeTask test");
            return;
        }

        int maxRetry = 3;
        int intervalMs = 20;

        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSndWriteRetryCount(maxRetry);
        cfg.setSndWriteRetryIntervalMs(intervalMs);

        SctpWriteTask task = buildTask(cfg);
        SctpMessage payload = makeSctpMessage();
        setSendData(task, payload);

        for (int i = 1; i <= maxRetry; i++) {
            boolean keepRetrying = invokeHandleTimeout(task);
            assert keepRetrying : "attempt " + i + "/" + maxRetry + " → should retry (return true)";
            assert getSendData(task) == payload : "packet must NOT be discarded during retry";
            assert task.getDelayTime() == intervalMs : "delay should equal sndWriteRetryIntervalMs";
            assert getTimeoutRetryCnt(task) == i : "counter should be " + i + " after attempt " + i;
        }

        boolean keepRetrying = invokeHandleTimeout(task);
        assert !keepRetrying : "after retries exhausted → must NOT retry (return false)";
        assert getSendData(task) == null : "after exhaustion → sendData must be null (discarded)";
        assert getTimeoutRetryCnt(task) == 0 : "after exhaustion → counter must reset to 0";
    }

    /**
     * Configured retry interval appears as the task delay value during retries.
     */
    @Test
    public void writeTask_retryIntervalIsRespected() throws Exception {
        if (!isSctpAvailable()) {
            System.out.println("[SKIP] SCTP not available on this platform – skipping writeTask test");
            return;
        }

        int[] intervals = { 10, 50, 150, 300 };

        for (int intervalMs : intervals) {
            SctpSoConfig cfg = new SctpSoConfig();
            cfg.setSndWriteRetryCount(1);
            cfg.setSndWriteRetryIntervalMs(intervalMs);

            SctpWriteTask task = buildTask(cfg);
            setSendData(task, makeSctpMessage());

            boolean keepRetrying = invokeHandleTimeout(task);
            assert keepRetrying : "should retry with intervalMs=" + intervalMs;
            assert task.getDelayTime() == intervalMs : "delayTime must equal intervalMs=" + intervalMs + ", got " + task.getDelayTime();
        }
    }

    /**
     * After exhaustion the counter resets, so the next packet starts fresh.
     */
    @Test
    public void writeTask_counterResetsForNextPacket() throws Exception {
        if (!isSctpAvailable()) {
            System.out.println("[SKIP] SCTP not available on this platform – skipping writeTask test");
            return;
        }

        SctpSoConfig cfg = new SctpSoConfig();
        cfg.setSndWriteRetryCount(1);
        cfg.setSndWriteRetryIntervalMs(10);

        SctpWriteTask task = buildTask(cfg);

        // First packet
        setSendData(task, makeSctpMessage());
        boolean r1 = invokeHandleTimeout(task); // retry
        assert r1 && getTimeoutRetryCnt(task) == 1;
        boolean r2 = invokeHandleTimeout(task); // exhausted
        assert !r2 && getTimeoutRetryCnt(task) == 0 && getSendData(task) == null;

        // Second packet – counter must have reset
        setSendData(task, makeSctpMessage());
        boolean r3 = invokeHandleTimeout(task);
        assert r3 : "counter must have reset; first timeout of new packet should retry";
        assert getTimeoutRetryCnt(task) == 1;
        boolean r4 = invokeHandleTimeout(task);
        assert !r4 && getSendData(task) == null;
    }
}
