/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.launcher;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.convert.ConverterUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.tconsole.TelAttribute;

/**
 * 工具集
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public class TelUtils {
    public static final String CMD = "tConsole>";

    public static boolean aBoolean(TelAttribute telAttribute, String key) {
        Object aboolean = telAttribute.getAttribute(key);
        if (aboolean == null) {
            aboolean = false;
        }
        return (Boolean) ConverterUtils.convert(Boolean.TYPE, aboolean);
    }

    public static int aInteger(TelAttribute telAttribute, String key) {
        Object aInteger = telAttribute.getAttribute(key);
        if (aInteger == null) {
            aInteger = 0;
        }
        return (Integer) ConverterUtils.convert(Integer.TYPE, aInteger);
    }

    public static String aString(TelAttribute telAttribute, String key) {
        Object aInteger = telAttribute.getAttribute(key);
        if (aInteger == null) {
            aInteger = "";
        }
        return aInteger.toString();
    }

    // 滑动窗口的机制
    public static int waitString(ByteBuf byteBuf, String waitString) {
        int waitLength = waitString.length();
        if (byteBuf.readableBytes() >= waitLength) {
            int loopCount = byteBuf.readableBytes() - waitLength;
            for (int i = 0; i <= loopCount; i++) {
                String dat = byteBuf.getString(i, waitLength, StandardCharsets.UTF_8);
                if (dat.equals(waitString)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
