/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoContextTest {
    @Test
    public void submitSoTask_Test() throws ExecutionException, InterruptedException {
        NetConfig config = new NetConfig();
        config.setPrintLog(true);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(2);
        config.setTaskThreads(2);
        SoContextService context = new SoContextService(config, null);

        int round = 5000;
        boolean result = true;

        while (round > 0) {
            round--;
            AtomicInteger cnt = new AtomicInteger(0);

            Future<SoContextTest> future = context.submitSoTask(new SoDelayTask(0), this).onCompleted(f -> {
                cnt.incrementAndGet();
            }).onFailed(f -> {
                //
            });

            future.get();
            int i = cnt.get();
            if (i != 1) {
                result = false;
                break;
            }
        }

        assert result;
    }
}
