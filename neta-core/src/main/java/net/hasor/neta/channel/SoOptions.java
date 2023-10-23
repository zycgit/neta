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
import jdk.net.ExtendedSocketOptions;

import java.net.SocketOption;
import java.net.StandardSocketOptions;

/**
 * Socket Config
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoOptions {
    public static final SocketOption<Integer> SO_SNDBUF        = StandardSocketOptions.SO_SNDBUF;
    public static final SocketOption<Integer> SO_RCVBUF        = StandardSocketOptions.SO_RCVBUF;
    public static final SocketOption<Boolean> SO_REUSEADDR     = StandardSocketOptions.SO_REUSEADDR;
    public static final SocketOption<Boolean> SO_KEEPALIVE     = StandardSocketOptions.SO_KEEPALIVE;
    public static final SocketOption<Integer> TCP_KEEPIDLE     = ExtendedSocketOptions.TCP_KEEPIDLE;
    public static final SocketOption<Integer> TCP_KEEPINTERVAL = ExtendedSocketOptions.TCP_KEEPINTERVAL;
    public static final SocketOption<Integer> TCP_KEEPCOUNT    = ExtendedSocketOptions.TCP_KEEPCOUNT;
}