/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole;
import java.util.List;
import java.util.function.Supplier;

/**
 * tConsol 为您提供 telnet 下和应用程序交互的能力。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public interface TelContext {

    /** 添加命令 */
    void addCommand(String cmdName, TelExecutor telExecutor);

    /** 添加命令 */
    void addCommand(String cmdName, Supplier<? extends TelExecutor> provider);

    /** 查找命令 */
    TelExecutor findCommand(String cmdName);

    /** 获取所有命令 */
    List<String> getCommandNames();
}
