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
import java.io.PrintStream;
import java.net.SocketAddress;
import net.hasor.cobble.StringUtils;

/**
 * Internal utility methods for the Neta channel layer.
 * <p>Currently provides two groups of helpers:
 * <ul>
 *   <li><b>Timeout exception factories</b> – methods such as {@link #newConnectTimeout}
 *       that build {@link SoTimeoutException} instances with descriptive messages encoding
 *       the channel ID, I/O direction (rcv/snd), and remote address, while preserving the
 *       original stack trace from the low-level {@code IOException}.</li>
 *   <li><b>Pipeline debug dump</b> – {@link #printStackTrace} writes a human-readable
 *       summary of a channel’s ID, local/remote addresses, and the full
 *       {@link ProtoStackChain} handler list to a {@link java.io.PrintStream}, useful
 *       for troubleshooting pipeline configuration.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoUtils {

    /** Builds a {@link SoConnectTimeoutException} for the channel's remote address. */
    public static SoConnectTimeoutException newConnectTimeout(boolean isRcv, long channelId, SoContextService context, Throwable e) {
        SocketAddress address = context.getRemoteAddress(channelId);
        String errorMsg = (isRcv ? "rcv(" : "snd(") + channelId + ") Connection timed out: " + address;
        SoConnectTimeoutException cause = new SoConnectTimeoutException(errorMsg);
        cause.setStackTrace(e.getStackTrace());
        return cause;
    }

    /**
     * Prints this {@link ProtoStackChain} status and its backtrace to the specified print stream.
     * @param s {@link PrintStream} to use for output
     */
    public static void printStackTrace(PrintStream s, SoChannel<?> channel, ProtoStackChain protoStack) {
        String body = protoStack == null ? "--- There is no ProtoStackChain ---" : protoStack.toString();
        int len = body.split("\n")[0].length();
        String ctitle = "ChannelId  : " + channel.getChannelId() + ",";
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

    static String generateName(ProtoHandler<?, ?> decoder, ProtoHandler<?, ?> encoder) {
        String decName = Integer.toHexString(System.identityHashCode(decoder));
        String encName = Integer.toHexString(System.identityHashCode(encoder));
        return String.format("%s/%s", decName, encName);
    }

    static String generateName(ProtoDuplexer<?, ?, ?, ?> duplexer) {
        return Integer.toHexString(System.identityHashCode(duplexer));
    }

    static String generateName(ProtoHandler<?, ?> encoder) {
        return Integer.toHexString(System.identityHashCode(encoder));
    }
}