/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client;

/**
 * Convenience callback for asynchronous {@link Call} execution.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Callback {
    void onFailure(Call call, Throwable error);

    void onResponse(Call call, Response response);
}
