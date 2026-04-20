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
package net.hasor.nhttp.client;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.nhttp.request.Request;

/**
 * One executable HTTP call built from a {@link Request}.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Call {
    Request request();

    Response execute() throws IOException;

    Future<Response> executeAsync();

    default void enqueue(Callback callback) {
        Future<Response> future = this.executeAsync();
        future.onCompleted(done -> {
            if (callback != null) {
                callback.onResponse(this, done.getResult());
            }
        }).onFailed(done -> {
            if (callback != null) {
                callback.onFailure(this, done.getCause());
            }
        }).onCancel(done -> {
            if (callback != null) {
                callback.onFailure(this, new CancellationException("call cancelled"));
            }
        });
    }

    boolean cancel();

    boolean isExecuted();

    boolean isCanceled();
}