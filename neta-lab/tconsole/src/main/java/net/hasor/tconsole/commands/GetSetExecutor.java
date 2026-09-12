/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.commands;
import net.hasor.cobble.StringUtils;
import net.hasor.tconsole.TelCommand;
import net.hasor.tconsole.TelExecutorVoid;

/**
 * 在本次 tConsole 会话中获取/设置 Session 变量。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年4月12日
 */
public class GetSetExecutor implements TelExecutorVoid {
    @Override
    public String helpInfo() {
        return "set/get environment variables of console.\r\n"//
                + " - get variableName                (returns variable Value.)\r\n"//
                + " - set variableName variableValue  (set new values to variable.)";//
    }

    @Override
    public void voidCommand(TelCommand telCommand) throws Throwable {
        String[] args = telCommand.getCommandArgs();
        String argsJoin = StringUtils.join(args, "");
        argsJoin = argsJoin.replace("\\s+", " ");
        args = argsJoin.split("=");

        if (args.length > 0) {
            String cmd = telCommand.getCommandName();
            String varName = args[0].trim();
            if (StringUtils.isBlank(varName)) {
                throw new Exception("var name undefined.");
            }

            if ("set".equalsIgnoreCase(cmd)) {
                if (args.length > 1) {
                    String varValue = args[1].trim();
                    telCommand.getSession().setAttribute(varName, varValue);
                    return;
                } else {
                    throw new Exception("args count error.");
                }
            }
            if ("get".equalsIgnoreCase(cmd)) {
                Object obj = telCommand.getSession().getAttribute(varName);
                if (obj == null) {
                    telCommand.writeMessageLine("");
                    return;
                } else {
                    telCommand.writeMessageLine(obj.toString());//TODO may be is object
                    return;
                }
            }

            throw new Exception("does not support command '" + telCommand.getCommandName() + "'.");
        } else {
            throw new Exception("args count error.");
        }
    }
}
