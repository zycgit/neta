/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.launcher;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.tconsole.TelContext;
import net.hasor.tconsole.TelExecutor;
import net.hasor.tconsole.TelPhase;
import net.hasor.tconsole.TelSession;
import static net.hasor.tconsole.TelOptions.*;
import static net.hasor.tconsole.launcher.TelUtils.aBoolean;
import static net.hasor.tconsole.launcher.TelUtils.aInteger;

/**
 * TelSession 接口实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public abstract class TelSessionObject extends AttributeObject implements TelSession {
    private static final Logger             logger = LoggerFactory.getLogger(TelSessionObject.class);
    private final        String             sessionID;      //
    private final        ByteBuf            dataReader;     // 输入流
    private final        Writer             dataWriter;     // 输出流
    private final        AbstractTelService telContext;     //
    private              TelCommandObject   currentCommand; // 当前命令
    private final        AtomicInteger      atomicInteger;  // 指令计数器

    public TelSessionObject(AbstractTelService telContext, ByteBuf dataReader, Writer dataWriter) {
        this.sessionID = UUID.randomUUID().toString().replace("-", "");
        this.telContext = telContext;
        this.dataReader = dataReader;
        this.dataWriter = dataWriter;
        this.atomicInteger = new AtomicInteger(0);
    }

    @Override
    public String getSessionID() {
        return this.sessionID;
    }

    @Override
    public int currentCounter() {
        return this.atomicInteger.get();
    }

    @Override
    public TelContext getTelContext() {
        return this.telContext;
    }

    @Override
    public void writeMessage(String message) {
        if (message == null) {
            return;
        }
        if (!this.isClose()) {
            try {
                this.dataWriter.write(message);
                this.dataWriter.flush();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void printCmd() {
        boolean silent = aBoolean(this, SILENT);    // 静默
        if (!silent) {
            writeMessage(TelUtils.CMD);
        }
    }

    public boolean tryReceiveEvent() throws IOException {
        // .创造命令
        if (this.currentCommand == null) {
            boolean blankLine = this.dataReader.hasLine();
            if (blankLine) {
                try {
                    String readData = this.dataReader.readLine(StandardCharsets.UTF_8).trim();
                    this.dataReader.flush();
                    if (StringUtils.isBlank(readData)) {
                        this.printCmd();
                        return true;
                    }

                    this.currentCommand = this.createTelCommand(readData);
                    this.currentCommand.currentPhase(TelPhase.Prepare);
                } catch (Exception e) {
                    this.dataReader.clear(); // 清掉缓冲区，重新接收
                    writeMessageLine(e.getMessage());
                    this.printCmd();
                    return true;
                }
            } else {
                return false;
            }
        }

        // .命令如果还未结束那么继续等待输入
        int cmdBodyLen = this.currentCommand.commandBodyLength(this.dataReader, StandardCharsets.UTF_8);
        if (cmdBodyLen == -1) {
            return true;
        }

        // .设置Body
        String readData = this.dataReader.readString(cmdBodyLen, StandardCharsets.UTF_8);
        this.dataReader.flush();
        this.currentCommand.setCommandBody(readData);
        this.currentCommand.currentPhase(TelPhase.StandBy);

        // .执行命令
        this.execCommand(this.currentCommand);
        this.currentCommand = null;
        return true;
    }

    private void execCommand(TelCommandObject command) {
        logger.info("tConsole -> exec " + command.getCommandName() + " ,counter =" + this.currentCounter());
        long doStartTime = System.currentTimeMillis();
        String result;
        try {
            command.currentPhase(TelPhase.Running);
            result = command.doCommand();
        } catch (Throwable e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            result = sw.toString();
        }

        // .输出成本
        boolean silent = aBoolean(this, SILENT);    // 静默
        boolean cost = aBoolean(this, COST);        // 成本
        if (!silent && cost) {
            result = result + "\r\n--------------\r\n";
            result = result + ("cost time: " + (System.currentTimeMillis() - doStartTime) + "ms.");
        }

        if (StringUtils.isNotBlank(result)) {
            writeMessageLine(result);
        }
        boolean doClose = aBoolean(this, CLOSE_SESSION);
        if (doClose) {
            if (!silent) {
                writeMessageLine("bye.");
            }
            IOUtils.closeQuietly(this.dataWriter);
        } else {
            this.printCmd();
        }
        command.currentPhase(TelPhase.Complete);
        this.atomicInteger.incrementAndGet(); // 计数器 ++
        //
        // .达到最大命令执行数，自动关闭session
        int executorNum = aInteger(this, MAX_EXECUTOR_NUM);
        if (executorNum > 0 && this.atomicInteger.get() >= executorNum) {
            this.close();
        }
    }

    private TelCommandObject createTelCommand(String inputString) {
        String requestCMD = inputString;
        String requestArgs = "";
        int cmdIndex = inputString.indexOf(" ");
        if (inputString.indexOf(" ") > 0) {
            requestCMD = inputString.substring(0, cmdIndex);
            requestArgs = inputString.substring(cmdIndex + 1);
        }
        TelExecutor executor = this.telContext.findCommand(requestCMD);
        if (executor == null) {
            throw new UnsupportedOperationException("'" + requestCMD + "' is bad command.");
        }
        if (StringUtils.isBlank(requestArgs)) {
            return new TelCommandObject(this, executor, requestCMD, new String[0]);
        } else {
            return new TelCommandObject(this, executor, requestCMD, requestArgs.split(" "));
        }
    }

    @Override
    public void close(int afterSeconds, boolean countdown) {
        // .设置关闭状态
        this.setAttribute(CLOSE_SESSION, "true");
        // .倒计时
        if (afterSeconds > 0) {
            try {
                for (int i = afterSeconds; i > 0; i--) {
                    if (countdown) {
                        this.writeMessageLine("exit after " + i + " seconds.");
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { /**/ }
        }
    }
}
