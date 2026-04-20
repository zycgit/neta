/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.client.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.nhttp.client.Call;
import net.hasor.nhttp.client.HttpClientConfig;
import net.hasor.nhttp.client.Response;
import net.hasor.nhttp.request.Request;

/**
 * Default {@link Call} implementation.
 * @author 赵永春 (zyc@hasor.net)
 */
public class RealCall implements Call {
    private final ClientRuntime                     runtime;
    private final HttpClientConfig                  config;
    private final Request                           request;
    private final AtomicBoolean                     executed      = new AtomicBoolean(false);
    private final AtomicBoolean                     canceled      = new AtomicBoolean(false);
    private final AtomicReference<Future<Response>> runningFuture = new AtomicReference<Future<Response>>();

    public RealCall(ClientRuntime runtime, HttpClientConfig config, Request request) {
        this.runtime = Objects.requireNonNull(runtime, "runtime is null");
        this.config = Objects.requireNonNull(config, "config is null");
        this.request = Objects.requireNonNull(request, "request is null");
    }

    @Override
    public Request request() {
        return this.request;
    }

    @Override
    public Response execute() throws IOException {
        Future<Response> future = this.startOnce();
        try {
            return future.get(this.config.getCallTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw ClientRuntime.asIOException(e);
        }
    }

    @Override
    public Future<Response> executeAsync() {
        return this.startOnce();
    }

    @Override
    public boolean cancel() {
        this.canceled.set(true);
        Future<Response> future = this.runningFuture.get();
        return future == null || future.cancel();
    }

    @Override
    public boolean isExecuted() {
        return this.executed.get();
    }

    @Override
    public boolean isCanceled() {
        return this.canceled.get();
    }

    private Future<Response> startOnce() {
        if (!this.executed.compareAndSet(false, true)) {
            throw new IllegalStateException("call already executed");
        }
        Future<Response> future = this.runtime.executeAsync(this.request);
        this.runningFuture.set(future);
        if (this.canceled.get()) {
            future.cancel();
        }
        return future;
    }
}