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
import net.hasor.cobble.ResourcesUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeInitializer;
import net.hasor.neta.codec.LimitFramePipeHandler;
import net.hasor.neta.handler.PipeDuplexHandler;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeHelper;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.KeyStore;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoSslUtils {

    public static SSLContext sslContext() throws Exception {
        char[] password = "123456".toCharArray();
        KeyStore jsk = KeyStore.getInstance("JKS");
        SslUtils.loadKeyStore(jsk, ResourcesUtils.getResourceAsStream("ssl/jks/keystore.jks"), password);
        //KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        //kmf.init(jsk,password);
        KeyManagerFactory kmf = SslUtils.buildKeyManagerFactory(jsk, password, null);

        // SSL Server
        SSLContext sslContext = SSLContext.getInstance("SSLv3");
        sslContext.init(kmf.getKeyManagers(), new TrustManager[] { new MyTrustManager() }, null);

        return sslContext;
    }

    public static SslConfig sslConfig(SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1, SslProtocol.TLS_v1_2 });
        sslConfig.setSsllog(true);
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    public static PipeInitializer sslSocketPipeline(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ctx -> PipeHelper.builder()
                // SSL
                .nextDuplex("SSL", new SslPipeLayer(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    public static PipeInitializer sslSocketPipeline(SslConfig sslConf, PipeHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFramePipeHandler limitFrame = new LimitFramePipeHandler(2);
        return ctx -> PipeHelper.embedded(ByteBuf.class, ByteBuf.class)
                // limitFrame
                .nextDuplex("LIMIT", new PipeDuplexHandler<>(limitFrame, limitFrame))
                // SSL
                .nextDuplex("SSL", new SslPipeLayer(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }
}