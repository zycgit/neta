/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.launcher;
import java.nio.charset.Charset;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.tconsole.*;

/**
 * 准备要执行的命令
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
final class TelCommandObject extends AttributeObject implements TelCommand, TelCommandOption {
    private final TelSession  parentSession;
    private final TelExecutor executor;
    private       TelPhase    telPhase;
    private final String      requestCommand;
    private final String[]    requestArgs;
    private       String      requestBody;
    private       boolean     cancel;

    TelCommandObject(TelSession parentSession, TelExecutor executor, String requestCommand, String[] requestArgs) {
        this.parentSession = parentSession;
        this.executor = executor;
        this.telPhase = TelPhase.Prepare;
        this.requestCommand = requestCommand;
        this.requestArgs = requestArgs;
        this.requestBody = null;
        this.cancel = false;
    }

    @Override
    public TelSession getSession() {
        return this.parentSession;
    }

    @Override
    public String getCommandName() {
        return this.requestCommand;
    }

    @Override
    public String[] getCommandArgs() {
        return this.requestArgs;
    }

    @Override
    public String getCommandBody() {
        return this.requestBody;
    }

    /** 命令状态 */
    public TelPhase currentPhase() {
        return this.telPhase;
    }

    void currentPhase(TelPhase telPhase) {
        this.telPhase = telPhase;
    }

    int commandBodyLength(ByteBuf input, Charset charset) {
        return this.executor.readCommandBodyLength(this, input, charset);
    }

    String doCommand() throws Throwable {
        if (this.cancel) {
            return "";
        }
        return this.executor.doCommand(this);
    }

    void setCommandBody(String commandBody) {
        this.requestBody = commandBody;
    }

    @Override
    public boolean isCancel() {
        return this.cancel;
    }

    @Override
    public void cancel() {
        this.cancel = true;
    }
}
