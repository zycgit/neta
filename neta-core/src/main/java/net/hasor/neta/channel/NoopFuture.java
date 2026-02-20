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
package net.hasor.neta.channel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;

/**
 * A pre-completed no-op Future that avoids BasicFuture allocation overhead
 * for internal sends where the caller never reads the result.
 * <p>
 * completed()/failed() are no-ops. isDone() always returns true.
 * No synchronization, no notifyAll(), no listener lists.
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-02-20
 */
final class NoopFuture implements Future<Object> {
    static final NoopFuture INSTANCE = new NoopFuture();

    private NoopFuture() {}

    @Override
    public boolean completed(Object result) { return false; }

    @Override
    public boolean failed(Throwable exception) { return false; }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) { return false; }

    @Override
    public boolean cancel() { return false; }

    @Override
    public boolean isCancelled() { return false; }

    @Override
    public boolean isDone() { return true; }

    @Override
    public Object getResult() { return null; }

    @Override
    public Throwable getCause() { return null; }

    @Override
    public Object get() { return null; }

    @Override
    public Object get(long timeout, TimeUnit unit) { return null; }

    @Override
    public Future<Object> onCompleted(FutureListener<Future<Object>> listener) { return this; }

    @Override
    public Future<Object> onFailed(FutureListener<Future<Object>> listener) { return this; }

    @Override
    public Future<Object> onCancel(FutureListener<Future<Object>> listener) { return this; }

    @Override
    public Future<Object> onFinal(FutureListener<Future<Object>> listener) { return this; }
}
