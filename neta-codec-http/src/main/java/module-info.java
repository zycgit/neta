/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
module net.hasor.neta.handler.codec {
    requires transitive jdk.net;
    requires transitive jdk.unsupported;
    requires transitive net.hasor.cobble;
    requires transitive net.hasor.neta;
    requires static jzlib;

    exports net.hasor.neta.codec.http;
    exports net.hasor.neta.codec.http.cookie;
    exports net.hasor.neta.codec.http.cors;
    exports net.hasor.neta.codec.http.h2;
    exports net.hasor.neta.codec.http.h3;
    exports net.hasor.neta.codec.http.multipart;
    exports net.hasor.neta.codec.http.routing;
    exports net.hasor.neta.codec.http.websocket;
}
