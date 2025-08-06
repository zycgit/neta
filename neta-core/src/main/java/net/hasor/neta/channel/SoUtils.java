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
import net.hasor.cobble.StringUtils;

import java.io.PrintStream;
import java.net.SocketAddress;

/**
 * Socket Utils.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoUtils {

    public static SoConnectTimeoutException newTimeout(boolean isRcv, long channelID, SoContextService context, Throwable e) {
        SocketAddress address = context.getRemoteAddress(channelID);
        String errorMsg = (isRcv ? "rcv(" : "snd(") + channelID + ") Connection timed out: " + address;
        SoConnectTimeoutException cause = new SoConnectTimeoutException(errorMsg);
        cause.setStackTrace(e.getStackTrace());
        return cause;
    }

    /**
     * Prints this {@link ProtoStack} status and its backtrace to the specified print stream.
     * @param s {@link PrintStream} to use for output
     */
    public static void printStackTrace(PrintStream s, SoChannel<?> channel, ProtoStack<?> protoStack) {
        String body = protoStack == null ? "--- There is no ProtoStack ---" : protoStack.toString();
        int len = body.split("\n")[0].length();
        String ctitle = "ChannelID  : " + channel.getChannelID() + ",";
        String status = channel.isClient() ? "Client" : "Server";
        status = status + (channel.isClose() ? "(Closed)" : "(Active)");
        s.println(StringUtils.rightPad(ctitle, len - status.length(), " ") + status);

        String laddrTitle = "Local Addr :";
        String laddrValue = channel.getLocalAddr().toString();
        s.println(StringUtils.rightPad(laddrTitle, len - laddrValue.length(), " ") + laddrValue);

        String raddrTitle = "Remote Addr:";
        String raddrValue = channel.getRemoteAddr().toString();
        s.println(StringUtils.rightPad(raddrTitle, len - raddrValue.length(), " ") + raddrValue);

        s.println(body);
    }
}