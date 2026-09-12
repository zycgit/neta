/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.commands;
import net.hasor.tconsole.TelCommand;
import net.hasor.tconsole.TelExecutorVoid;
import net.hasor.tconsole.TelOptions;

/**
 * 关闭 tConsole session。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2019年10月30日
 */
public class QuitExecutor implements TelExecutorVoid {
    @Override
    public String helpInfo() {
        return "out of console.\r\n"//
                + " -t <n> (when n second after to close telnet.)\r\n"//
                + " -n <n> (when n commands after to close telnet.)\r\n"//
                + " -next  (when next commands after to close telnet.)\r\n"//
                + "     Tips: If you set -n -t at the same time, then -t failure.";
    }

    @Override
    public void voidCommand(TelCommand telCommand) throws Throwable {
        String[] args = telCommand.getCommandArgs();
        int parseInt = 0;
        int nextCommand = 0;
        for (String arg : args) {
            if (arg.startsWith("-next")) {
                nextCommand = telCommand.getSession().currentCounter() + 2;
                continue;
            }
            if (arg.startsWith("-t")) {
                parseInt = Integer.parseInt(arg.substring(2).trim());
                continue;
            }
            if (arg.startsWith("-n")) {
                int nextInt = Integer.parseInt(arg.substring(2).trim());
                if (nextInt > 0) {
                    nextCommand = telCommand.getSession().currentCounter() + nextInt + 1;
                }
                continue;
            }
        }

        if (nextCommand > 0) {
            telCommand.getSession().setAttribute(TelOptions.MAX_EXECUTOR_NUM, nextCommand);
            return;
        }

        telCommand.getSession().close(parseInt);
    }
}
