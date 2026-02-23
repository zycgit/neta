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
package net.hasor.neta.channel.quic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.InterruptedByTimeoutException;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.WriteRetryTestHelper;
import org.junit.Test;

/**
 * Unit tests for the write-timeout retry parameters added to {@link QuicSoConfig}
 * and the corresponding retry logic in {@link QuicWriteTask}.
 * <p>Strategy: {@link QuicWriteTask#handleException} is private, so this test lives in the same
 * package and accesses it via reflection to keep the production API unmodified.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSndWriteRetryTest {

    // -------------------------------------------------------------------------
    // Section 1 – QuicSoConfig parameter defaults & setters
    // -------------------------------------------------------------------------

    @Test
    public void config_defaultValues() {
        QuicSoConfig cfg = new QuicSoConfig();
        assert cfg.getSndWriteRetryCount() == 0 : "default sndWriteRetryCount should be 0";
        assert cfg.getSndWriteRetryIntervalMs() == 50 : "default sndWriteRetryIntervalMs should be 50";
    }

    @Test
    public void config_setters() {
        QuicSoConfig cfg = new QuicSoConfig();
        cfg.setSndWriteRetryCount(4);
        cfg.setSndWriteRetryIntervalMs(200);
        assert cfg.getSndWriteRetryCount() == 4;
        assert cfg.getSndWriteRetryIntervalMs() == 200;
    }

    @Test
    public void config_setterPreservesOtherFields() {
        QuicSoConfig cfg = new QuicSoConfig();
        cfg.setSndWriteRetryCount(2);
        cfg.setSndWriteRetryIntervalMs(100);
        cfg.setSelectorPollMs(10);
        // other fields must remain unaffected
        assert cfg.getSelectorPollMs() == 10;
        assert cfg.getSndWriteRetryCount() == 2;
        assert cfg.getSndWriteRetryIntervalMs() == 100;
    }

    // -------------------------------------------------------------------------
    // Section 2 – QuicWriteTask retry logic (via reflection)
    // -------------------------------------------------------------------------

    /** Instantiate a {@link QuicWriteTask} using the supplied config. */
    private static QuicWriteTask buildTask(QuicSoConfig cfg) throws Exception {
        SoContextService ctx = WriteRetryTestHelper.createContextService();
        NetChannel channel = WriteRetryTestHelper.createNetChannel(cfg, ctx);
        // QuicChannel and SoSndContext are not accessed by handleException on the
        // InterruptedByTimeoutException path, so null is safe here.
        return new QuicWriteTask(channel, 0L, null, null, ctx);
    }

    /** Use reflection to invoke the private {@code handleException(Throwable, SoSndContext)}. */
    private static boolean invokeHandleTimeout(QuicWriteTask task) throws Exception {
        Method m = QuicWriteTask.class.getDeclaredMethod("handleException", Throwable.class, net.hasor.neta.channel.SoSndContext.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(task, new InterruptedByTimeoutException(), null);
    }

    private static void setSendData(QuicWriteTask task, byte[] data) throws Exception {
        Field f = QuicWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        f.set(task, data);
    }

    private static byte[] getSendData(QuicWriteTask task) throws Exception {
        Field f = QuicWriteTask.class.getDeclaredField("sendData");
        f.setAccessible(true);
        return (byte[]) f.get(task);
    }

    private static int getTimeoutRetryCnt(QuicWriteTask task) throws Exception {
        Field f = QuicWriteTask.class.getDeclaredField("timeoutRetryCnt");
        f.setAccessible(true);
        return (int) f.get(task);
    }

    /**
     * When {@code sndWriteRetryCount == 0} (default), a write timeout must NOT
     * schedule a retry: {@code handleException} should return {@code false} and
     * clear {@code sendData} immediately.
     */
    @Test
    public void writeTask_noRetry_timeoutDiscardsImmediately() throws Exception {
        QuicSoConfig cfg = new QuicSoConfig();
        cfg.setSndWriteRetryCount(0);

        QuicWriteTask task = buildTask(cfg);
        setSendData(task, new byte[] { 1, 2, 3 });

        boolean shouldReturn = invokeHandleTimeout(task);

        assert !shouldReturn : "retryCount=0 should NOT schedule a retry (return false)";
        assert getSendData(task) == null : "sendData must be cleared after discard";
        assert getTimeoutRetryCnt(task) == 0 : "timeoutRetryCnt must be reset to 0";
    }

    /**
     * When {@code sndWriteRetryCount > 0}, each successive timeout must increment the
     * counter and return {@code true} (retry scheduled) until the budget is exhausted,
     * at which point it returns {@code false} and clears {@code sendData}.
     */
    @Test
    public void writeTask_retryThenDiscard() throws Exception {
        QuicSoConfig cfg = new QuicSoConfig();
        cfg.setSndWriteRetryCount(3);
        cfg.setSndWriteRetryIntervalMs(1); // short interval to avoid real delays in the test

        QuicWriteTask task = buildTask(cfg);
        setSendData(task, new byte[] { 1, 2, 3 });

        // 1st timeout → retry
        assert invokeHandleTimeout(task) : "1st timeout should schedule a retry";
        assert getTimeoutRetryCnt(task) == 1;

        // 2nd timeout → retry
        assert invokeHandleTimeout(task) : "2nd timeout should schedule a retry";
        assert getTimeoutRetryCnt(task) == 2;

        // 3rd timeout → retry
        assert invokeHandleTimeout(task) : "3rd timeout should schedule a retry";
        assert getTimeoutRetryCnt(task) == 3;

        // 4th timeout → budget exhausted
        boolean shouldReturn = invokeHandleTimeout(task);
        assert !shouldReturn : "after budget exhausted, should NOT schedule another retry";
        assert getSendData(task) == null : "sendData must be cleared";
        assert getTimeoutRetryCnt(task) == 0 : "timeoutRetryCnt must be reset to 0";
    }

    /**
     * After a successful retry (counter incremented) the counter must be independent
     * per task instance — two separate tasks do not share state.
     */
    @Test
    public void writeTask_retryCounterIsPerInstance() throws Exception {
        QuicSoConfig cfg = new QuicSoConfig();
        cfg.setSndWriteRetryCount(2);

        QuicWriteTask task1 = buildTask(cfg);
        QuicWriteTask task2 = buildTask(cfg);
        setSendData(task1, new byte[] { 1 });
        setSendData(task2, new byte[] { 2 });

        invokeHandleTimeout(task1); // task1 uses 1 retry
        invokeHandleTimeout(task1); // task1 uses 2 retries (budget full)

        // task2 still has full budget
        assert invokeHandleTimeout(task2) : "task2 should still have retry budget";
        assert getTimeoutRetryCnt(task2) == 1;
    }
}
