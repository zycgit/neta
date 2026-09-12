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
    requires transitive net.hasor.neta;

    exports net.hasor.neta.codec.net.ntp;
}
