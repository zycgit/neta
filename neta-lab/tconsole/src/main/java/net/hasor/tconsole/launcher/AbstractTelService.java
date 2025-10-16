/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.tconsole.launcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.tconsole.TelContext;
import net.hasor.tconsole.TelExecutor;
import net.hasor.tconsole.TelOptions;
import net.hasor.tconsole.commands.GetSetExecutor;
import net.hasor.tconsole.commands.HelpExecutor;
import net.hasor.tconsole.commands.QuitExecutor;

/**
 * tConsole 服务基类
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public abstract class AbstractTelService extends AttributeObject implements TelContext {
    protected final Logger                                       logger      = LoggerFactory.getLogger(this.getClass());
    private final   Map<String, Supplier<? extends TelExecutor>> executorMap = new ConcurrentHashMap<>();

    public AbstractTelService() {
        this.applyCommand();
    }

    public void silent(boolean silent) {
        this.setAttribute(TelOptions.SILENT, silent);
    }

    public boolean isSilent() {
        return TelUtils.aBoolean(this, TelOptions.SILENT);
    }

    public void encodedOfSilent(String encoded) {
        this.setAttribute(TelOptions.ENDCODE_OF_SILENT, encoded);
    }

    public String encodedOfSilent() {
        return TelUtils.aString(this, TelOptions.ENDCODE_OF_SILENT);
    }

    protected void applyCommand() {
        this.addCommand(new String[] { "get", "set" }, new GetSetExecutor());
        this.addCommand(new String[] { "quit", "exit" }, new QuitExecutor());
        this.addCommand(new String[] { "help" }, new HelpExecutor());
    }

    /** 添加命令 */
    @Override
    public void addCommand(String cmdName, TelExecutor telExecutor) {
        this.addCommand(cmdName, () -> telExecutor);
    }

    /** 添加命令 */
    @Override
    public void addCommand(String cmdName, Supplier<? extends TelExecutor> provider) {
        if (StringUtils.isNotBlank(cmdName)) {
            this.executorMap.put(cmdName, provider);
        }
    }

    private void addCommand(String[] cmdNames, TelExecutor provider) {
        for (String name : cmdNames) {
            this.addCommand(name, provider);
        }
    }

    private void addCommand(String[] cmdNames, Supplier<? extends TelExecutor> provider) {
        for (String name : cmdNames) {
            this.addCommand(name, provider);
        }
    }

    @Override
    public TelExecutor findCommand(String cmdName) {
        Supplier<? extends TelExecutor> supplier = this.executorMap.get(cmdName);
        if (supplier != null) {
            return supplier.get();
        }
        return null;
    }

    @Override
    public List<String> getCommandNames() {
        return new ArrayList<>(this.executorMap.keySet());
    }
}