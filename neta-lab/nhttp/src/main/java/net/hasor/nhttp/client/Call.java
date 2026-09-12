/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
