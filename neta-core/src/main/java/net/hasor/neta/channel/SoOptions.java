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
import net.hasor.cobble.logging.Logger;

import java.net.SocketOption;
import java.net.StandardSocketOptions;

/**
 * Socket Config
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoOptions {
    private static final Logger                logger       = Logger.getLogger(SoOptions.class);
    public static final  SocketOption<Integer> SO_SNDBUF    = StandardSocketOptions.SO_SNDBUF;
    public static final  SocketOption<Integer> SO_RCVBUF    = StandardSocketOptions.SO_RCVBUF;
    public static final  SocketOption<Boolean> SO_REUSEADDR = StandardSocketOptions.SO_REUSEADDR;
    public static final  SocketOption<Boolean> SO_KEEPALIVE = StandardSocketOptions.SO_KEEPALIVE;
    public static final  SocketOption<Integer> TCP_KEEPIDLE;
    public static final  SocketOption<Integer> TCP_KEEPINTERVAL;
    public static final  SocketOption<Integer> TCP_KEEPCOUNT;

    static {
        SocketOption<Integer> tcpKeepIdleTmp;
        try {
            tcpKeepIdleTmp = ExtendedSocketOptions.TCP_KEEPIDLE;
        } catch (Throwable e) {
            logger.warn("your jdk does not support TCP_KEEPIDLE parameter, please upgrade to 1.8.0_333+");
            tcpKeepIdleTmp = null;
        }
        TCP_KEEPIDLE = tcpKeepIdleTmp;

        SocketOption<Integer> tcpKeepIntervalTmp;
        try {
            tcpKeepIntervalTmp = ExtendedSocketOptions.TCP_KEEPINTERVAL;
        } catch (Throwable e) {
            logger.warn("your jdk does not support TCP_KEEPINTERVAL parameter, please upgrade to 1.8.0_333+");
            tcpKeepIntervalTmp = null;
        }
        TCP_KEEPINTERVAL = tcpKeepIntervalTmp;

        SocketOption<Integer> tcpKeepCountTmp;
        try {
            tcpKeepCountTmp = ExtendedSocketOptions.TCP_KEEPCOUNT;
        } catch (Throwable e) {
            logger.warn("your jdk does not support TCP_KEEPCOUNT parameter, please upgrade to 1.8.0_333+");
            tcpKeepCountTmp = null;
        }
        TCP_KEEPCOUNT = tcpKeepCountTmp;
    }
}