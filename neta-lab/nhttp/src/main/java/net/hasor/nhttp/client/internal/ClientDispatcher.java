/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client.internal;

import java.util.*;

import net.hasor.cobble.concurrent.future.BasicFuture;

/**
 * Dispatcher that limits total and per-host concurrent client work.
 * @author 赵永春 (zyc@hasor.net)
 */
public class ClientDispatcher {
    private final int                  maxConcurrentCalls;
    private final int                  maxConcurrentCallsPerHost;
    private final Deque<Pending>       readyQueue   = new ArrayDeque<Pending>();
    private final Set<Pending>         runningQueue = new LinkedHashSet<Pending>();
    private final Map<String, Integer> hostRunning  = new HashMap<String, Integer>();

    public ClientDispatcher(int maxConcurrentCalls, int maxConcurrentCallsPerHost) {
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxConcurrentCallsPerHost = maxConcurrentCallsPerHost;
    }

    public void enqueue(String hostKey, BasicFuture<?> result, Runnable starter) {
        final Pending pending = new Pending(hostKey, result, starter);
        result.onFinal(done -> this.finish(pending));

        synchronized (this) {
            this.readyQueue.addLast(pending);
        }
        this.promote();
    }

    private void finish(Pending pending) {
        boolean changed = false;
        synchronized (this) {
            if (this.readyQueue.remove(pending)) {
                changed = true;
            }
            if (this.runningQueue.remove(pending)) {
                this.decrementHost(pending.hostKey);
                changed = true;
            }
        }
        if (changed) {
            this.promote();
        }
    }

    private void promote() {
        List<Pending> startList = new ArrayList<Pending>();
        synchronized (this) {
            Iterator<Pending> iterator = this.readyQueue.iterator();
            while (iterator.hasNext() && this.runningQueue.size() < this.maxConcurrentCalls) {
                Pending pending = iterator.next();
                if (pending.result.isDone()) {
                    iterator.remove();
                    continue;
                }

                int perHostRunning = this.hostRunningCount(pending.hostKey);
                if (perHostRunning >= this.maxConcurrentCallsPerHost) {
                    continue;
                }

                iterator.remove();
                this.runningQueue.add(pending);
                this.hostRunning.put(pending.hostKey, perHostRunning + 1);
                startList.add(pending);
            }
        }

        for (Pending pending : startList) {
            if (pending.result.isDone()) {
                this.finish(pending);
                continue;
            }
            try {
                pending.starter.run();
            } catch (Throwable e) {
                pending.result.failed(e);
            }
        }
    }

    private int hostRunningCount(String hostKey) {
        Integer value = this.hostRunning.get(hostKey);
        return value == null ? 0 : value.intValue();
    }

    private void decrementHost(String hostKey) {
        Integer value = this.hostRunning.get(hostKey);
        if (value == null) {
            return;
        }
        int newValue = value.intValue() - 1;
        if (newValue <= 0) {
            this.hostRunning.remove(hostKey);
        } else {
            this.hostRunning.put(hostKey, newValue);
        }
    }

    private static class Pending {
        private final String         hostKey;
        private final BasicFuture<?> result;
        private final Runnable       starter;

        private Pending(String hostKey, BasicFuture<?> result, Runnable starter) {
            this.hostKey = hostKey;
            this.result = result;
            this.starter = starter;
        }
    }
}
