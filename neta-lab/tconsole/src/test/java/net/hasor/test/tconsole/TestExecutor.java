/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.test.tconsole;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.tconsole.TelCommand;
import net.hasor.tconsole.TelExecutor;

/**
 * Hello Word
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年4月3日
 */
public class TestExecutor implements TelExecutor {
    private boolean doCommand;

    public boolean isDoCommand() {
        return doCommand;
    }

    public void setDoCommand(boolean doCommand) {
        this.doCommand = doCommand;
    }

    @Override
    public String helpInfo() {
        return "hello help.";
    }

    @Override
    public String doCommand(TelCommand telCommand) throws Throwable {
        this.doCommand = true;
        return new ObjectMapper().writeValueAsString(new HashMap<String, String>() {{
            put("name", telCommand.getCommandName());
            put("args", StringUtils.join(telCommand.getCommandArgs(), ","));
            put("body", telCommand.getCommandBody());
        }});
    }
}
