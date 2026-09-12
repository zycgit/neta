/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import org.junit.Test;

/**
 * Tests for {@link NetMonitor} counter updates and timestamp tracking.
 * @author test
 */
public class NetMonitorTest {

    @Test
    public void initialState() {
        NetMonitor m = new NetMonitor();
        assert m.getCreatedTime() > 0;
        assert m.getRcvCounterBytes() == 0;
        assert m.getSndCounterBytes() == 0;
        assert m.getLastRcvTime() == 0;
        assert m.getLastSndTime() == 0;
        assert m.getLastActiveTime() == 0;
    }

    @Test
    public void updateRcvCounter() throws InterruptedException {
        NetMonitor m = new NetMonitor();

        long before = System.currentTimeMillis();
        Thread.sleep(5);
        m.updateRcvCounter(100);

        assert m.getRcvCounterBytes() == 100;
        assert m.getLastRcvTime() >= before;
        assert m.getSndCounterBytes() == 0;
    }

    @Test
    public void updateSndCounter() throws InterruptedException {
        NetMonitor m = new NetMonitor();

        long before = System.currentTimeMillis();
        Thread.sleep(5);
        m.updateSndCounter(200);

        assert m.getSndCounterBytes() == 200;
        assert m.getLastSndTime() >= before;
        assert m.getRcvCounterBytes() == 0;
    }

    @Test
    public void accumulateCounters() {
        NetMonitor m = new NetMonitor();
        m.updateRcvCounter(10);
        m.updateRcvCounter(20);
        m.updateRcvCounter(30);
        assert m.getRcvCounterBytes() == 60;

        m.updateSndCounter(5);
        m.updateSndCounter(15);
        assert m.getSndCounterBytes() == 20;
    }

    @Test
    public void lastActiveTime_isMax() throws InterruptedException {
        NetMonitor m = new NetMonitor();

        m.updateRcvCounter(1);
        long rcvTime = m.getLastRcvTime();

        Thread.sleep(10);

        m.updateSndCounter(1);
        long sndTime = m.getLastSndTime();

        assert sndTime >= rcvTime;
        assert m.getLastActiveTime() == Math.max(rcvTime, sndTime);
        assert m.getLastActiveTime() == sndTime;
    }

    @Test
    public void createdTime_doesNotChange() throws InterruptedException {
        NetMonitor m = new NetMonitor();
        long t1 = m.getCreatedTime();
        Thread.sleep(10);
        m.updateRcvCounter(1);
        m.updateSndCounter(1);
        assert m.getCreatedTime() == t1;
    }
}
