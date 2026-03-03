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
package net.hasor.neta.channel.udp;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.InterruptedByTimeoutException;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoSndContext;
import net.hasor.neta.channel.WriteRetryTestHelper;
import org.junit.Test;

/**
 * Unit tests for the write-timeout retry parameters added to {@link UdpSoConfig}
 * and the corresponding retry logic in {@link UdpWriteTask}.
 * <p>Strategy: {@link UdpWriteTask#handleException} is package-private in the
 * {@code net.hasor.neta.channel.udp} package, so this test lives in the same
 * package and accesses it via reflection to keep the production API unmodified.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
public class UdpSndWriteRetryTest {

    // -------------------------------------------------------------------------
    // Section 1 – UdpSoConfig parameter defaults & setters
    // -------------------------------------------------------------------------

    /** Instantiate a {@link UdpWriteTask} using the supplied config. */
    private static UdpWriteTask buildTask(UdpSoConfig cfg) throws Exception {
        SoContextService ctx = WriteRetryTestHelper.createContextService();
        NetChannel channel = WriteRetryTestHelper.createNetChannel(cfg, ctx);
        // DatagramChannel and SoSndContext are not accessed by handleException on the
        // InterruptedByTimeoutException path, so null is safe here.
        return new UdpWriteTask(channel, null, null, ctx);
    }

    /** Invoke the private {@code handleException(Throwable, SoSndContext)} with a timeout exception. */
    private static boolean invokeHandleTimeout(UdpWriteTask task) throws Exception {
        Method m = AbstractUdpWriteTask.class.getDeclaredMethod("handleException", Throwable.class, SoSndContext.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(task, new InterruptedByTimeoutException(), null);
    }

    private static void setSendData(UdpWriteTask task, byte[] data) throws Exception {
        Field f = AbstractUdpWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        f.set(task, data);
    }

    // -------------------------------------------------------------------------
    // Section 2 – UdpWriteTask retry logic (via reflection)
    // -------------------------------------------------------------------------

    private static byte[] getSendData(UdpWriteTask task) throws Exception {
        Field f = AbstractUdpWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        return (byte[]) f.get(task);
    }

    private static int getTimeoutRetryCnt(UdpWriteTask task) throws Exception {
        Field f = AbstractUdpWriteTask.class.getDeclaredField("timeoutRetryCnt");
        f.setAccessible(true);
        return (int) f.get(task);
    }

    @Test
    public void config_defaultValues() {
        UdpSoConfig cfg = new UdpSoConfig();
        assert cfg.getSndWriteRetryCount() == 0 : "default sndWriteRetryCount should be 0";
        assert cfg.getSndWriteRetryIntervalMs() == 50 : "default sndWriteRetryIntervalMs should be 50";
    }

    @Test
    public void config_setters() {
        UdpSoConfig cfg = new UdpSoConfig();
        cfg.setSndWriteRetryCount(5);
        cfg.setSndWriteRetryIntervalMs(200);
        assert cfg.getSndWriteRetryCount() == 5;
        assert cfg.getSndWriteRetryIntervalMs() == 200;
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    @Test
    public void config_setterPreservesOtherFields() {
        UdpSoConfig cfg = new UdpSoConfig();
        cfg.setRcvPacketSize(1024);
        cfg.setSndWriteRetryCount(3);
        cfg.setSndWriteRetryIntervalMs(100);
        // Other fields must remain unaffected
        assert cfg.getRcvPacketSize() == 1024;
        assert cfg.getSndWriteRetryCount() == 3;
        assert cfg.getSndWriteRetryIntervalMs() == 100;
    }

    /**
     * When {@code sndWriteRetryCount == 0} (default), a write timeout must NOT
     * schedule a retry: {@code handleException} should return {@code false} and
     * clear {@code sendData} immediately.
     */
    @Test
    public void writeTask_noRetry_timeoutDiscardsImmediately() throws Exception {
        UdpSoConfig cfg = new UdpSoConfig();
        cfg.setSndWriteRetryCount(0);

        UdpWriteTask task = buildTask(cfg);
        setSendData(task, new byte[] { 1, 2, 3 });

        // First (and only) timeout: should discard
        boolean keepRetrying = invokeHandleTimeout(task);

        assert !keepRetrying : "retryCount=0 → should NOT retry (return false)";
        assert getSendData(task) == null : "retryCount=0 → sendData must be null (discarded)";
        assert getTimeoutRetryCnt(task) == 0 : "retryCount=0 → counter stays at 0";
        assert task.getDelayTime() == 0 : "retryCount=0 → no delay should be scheduled";
    }

    /**
     * When {@code sndWriteRetryCount == N}, the first N timeouts must schedule a
     * retry (return {@code true}) and the (N+1)-th must discard (return {@code false}).
     * The retry counter must reset to 0 after exhaustion.
     */
    @Test
    public void writeTask_retryCount3_retriesNTimesThenDiscards() throws Exception {
        int maxRetry = 3;
        int intervalMs = 15;

        UdpSoConfig cfg = new UdpSoConfig();
        cfg.setSndWriteRetryCount(maxRetry);
        cfg.setSndWriteRetryIntervalMs(intervalMs);

        UdpWriteTask task = buildTask(cfg);
        byte[] payload = { 10, 20, 30 };
        setSendData(task, payload);

        // Iterations 1..N must all return true (retry scheduled)
        for (int i = 1; i <= maxRetry; i++) {
            boolean keepRetrying = invokeHandleTimeout(task);
            assert keepRetrying : "attempt " + i + "/" + maxRetry + " → should retry (return true)";
            assert getSendData(task) == payload : "packet must NOT be discarded during retry";
            assert task.getDelayTime() == intervalMs : "delay should equal sndWriteRetryIntervalMs";
            assert getTimeoutRetryCnt(task) == i : "counter should be " + i + " after attempt " + i;
        }

        // Iteration N+1 → exhausted: must discard and reset
        boolean keepRetrying = invokeHandleTimeout(task);
        assert !keepRetrying : "after retries exhausted → must NOT retry (return false)";
        assert getSendData(task) == null : "after exhaustion → sendData must be null (discarded)";
        assert getTimeoutRetryCnt(task) == 0 : "after exhaustion → counter must reset to 0";
    }

    /**
     * The retry interval configured via {@code sndWriteRetryIntervalMs} must be
     * reflected in the task's scheduled delay.
     */
    @Test
    public void writeTask_retryIntervalIsRespected() throws Exception {
        int[] intervals = { 10, 50, 150, 300 };

        for (int intervalMs : intervals) {
            UdpSoConfig cfg = new UdpSoConfig();
            cfg.setSndWriteRetryCount(1);
            cfg.setSndWriteRetryIntervalMs(intervalMs);

            UdpWriteTask task = buildTask(cfg);
            setSendData(task, new byte[] { 7 });

            boolean keepRetrying = invokeHandleTimeout(task);
            assert keepRetrying : "should retry with intervalMs=" + intervalMs;
            assert task.getDelayTime() == intervalMs : "delayTime must equal intervalMs=" + intervalMs + ", got " + task.getDelayTime();
        }
    }

    /**
     * After a retry counter reset (exhaustion on the previous packet), the task
     * must behave consistently for a second packet. This validates that state is
     * properly reset between consecutive timeouts on different packets.
     */
    @Test
    public void writeTask_counterResetsForNextPacket() throws Exception {
        UdpSoConfig cfg = new UdpSoConfig();
        cfg.setSndWriteRetryCount(1);
        cfg.setSndWriteRetryIntervalMs(10);

        UdpWriteTask task = buildTask(cfg);

        // --- First packet ---
        setSendData(task, new byte[] { 1 });
        boolean r1 = invokeHandleTimeout(task); // attempt 1 → retry
        assert r1 && getTimeoutRetryCnt(task) == 1;
        boolean r2 = invokeHandleTimeout(task); // attempt 2 → exhausted, discard
        assert !r2 && getTimeoutRetryCnt(task) == 0 && getSendData(task) == null;

        // --- Second packet (counter should have reset) ---
        setSendData(task, new byte[] { 2 });
        boolean r3 = invokeHandleTimeout(task); // attempt 1 again → retry
        assert r3 : "counter must have reset; first timeout of new packet should retry";
        assert getTimeoutRetryCnt(task) == 1;
        boolean r4 = invokeHandleTimeout(task); // attempt 2 → discard again
        assert !r4 && getSendData(task) == null;
    }
}
