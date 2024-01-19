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
package net.hasor.neta.handler.ssl;
import net.hasor.cobble.function.ESupplier;
import net.hasor.cobble.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encapsulate SSLEngine.
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
class SslEngineWrap {
    private static final Logger                            logger = Logger.getLogger(SslHandle.class);
    private final        long                              channelID;
    private final        SslConfig                         sslConfig;
    private final        ESupplier<SSLEngine, IOException> sslEngineFactory;
    private              SSLEngine                         sslEngine;
    private              SSLSession                        sslSession;

    public SslEngineWrap(long channelID, SslConfig sslConfig, ESupplier<SSLEngine, IOException> factory) {
        this.channelID = channelID;
        this.sslConfig = sslConfig;
        this.sslEngineFactory = factory;
    }

    public SslConfig getConfig() {
        return this.sslConfig;
    }

    public HandshakeStatus getHandshakeStatus() {
        return this.sslEngine.getHandshakeStatus();
    }

    public int getPacketBufferSize() {
        return this.sslSession.getPacketBufferSize();
    }

    public int getApplicationBufferSize() {
        return this.sslSession.getApplicationBufferSize();
    }

    public String getPeerHost() {
        return this.sslEngine.getPeerHost();
    }

    public int getPeerPort() {
        return this.sslEngine.getPeerPort();
    }

    public SSLEngine unwrap() {
        return this.sslEngine;
    }

    public boolean isOutboundDone() {
        return this.sslEngine.isOutboundDone();
    }

    public void closeOutbound() {
        this.sslEngine.closeOutbound();
    }

    public void closeInbound() {
        try {
            this.sslEngine.closeInbound();
        } catch (SSLException e) {
            if (logger.isDebugEnabled()) {
                // only log in debug mode as it most likely harmless and latest chrome still trigger this all the time.
                //  See https://github.com/netty/netty/issues/1340
                String msg = e.getMessage();
                if (msg == null || !(msg.contains("possible truncation attack") || msg.contains("closing inbound before receiving peer's close_notify"))) {
                    logger.error(this.channelID + " SSLEngine.closeInbound() raised an exception.", e);
                }
            }
        }
    }

    public SSLEngineResult unwrap(ByteBuffer inNetData, ByteBuffer inAppData) throws SSLException {
        return this.sslEngine.unwrap(inNetData, inAppData);
    }

    public SSLEngineResult wrap(ByteBuffer outAppData, ByteBuffer outNetData) throws SSLException {
        return this.sslEngine.wrap(outAppData, outNetData);
    }

    public void beginHandshake() throws IOException {
        if (this.sslEngine != null) {
            this.closeOutbound();
            this.closeInbound();
        }

        this.sslEngine = this.sslEngineFactory.eGet();
        this.sslEngine.beginHandshake();

        this.sslSession = this.sslEngine.getSession();

    }

    public Runnable getTask() {
        return () -> {
            if (this.sslEngine == null) {
                return;
            }
            Runnable runnable;
            while ((runnable = this.sslEngine.getDelegatedTask()) != null) {
                runnable.run();
            }
        };
    }
}