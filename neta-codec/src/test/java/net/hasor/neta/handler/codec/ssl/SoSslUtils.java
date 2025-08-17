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
package net.hasor.neta.handler.codec.ssl;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.handler.ProtoDuplexerHandler;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoHelper;
import net.hasor.neta.handler.codec.LimitFrameHandler;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.KeyStore;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoSslUtils {

    public static SSLContext sslContext(String sslProtocol) throws Exception {
        char[] password = "123456".toCharArray();
        KeyStore jsk = KeyStore.getInstance("JKS");
        SslUtils.loadKeyStore(jsk, ResourcesUtils.getResourceAsStream("ssl/jks/keystore.jks"), password);
        //KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        //kmf.init(jsk,password);
        KeyManagerFactory kmf = SslUtils.buildKeyManagerFactory(jsk, password, null);

        // SSL Server
        SSLContext sslContext = SSLContext.getInstance(sslProtocol);
        sslContext.init(kmf.getKeyManagers(), new TrustManager[] { new MyTrustManager() }, null);

        return sslContext;
    }

    public static SslConfig sslConfig(String[] sslProtocol, SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(sslProtocol);
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    public static SslConfig sslConfig(String sslProtocol, SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(new String[] { sslProtocol });
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    public static SslConfig dtlsConfig(SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(new String[] { SslProtocol.DTLS_v1_2 });
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    public static ProtoInitializer sslSocketProtoStack(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ctx -> ProtoHelper.builder()
                // SSL
                .nextDuplex("SSL", new SslProtoDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    public static ProtoInitializer sslSocketProtoStack(SslConfig sslConf, ProtoHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFrameHandler limitFrame = new LimitFrameHandler(4096);
        return ctx -> ProtoHelper.embedded(ByteBuf.class, ByteBuf.class)
                //
                .nextDuplex("LIMIT", new ProtoDuplexerHandler<>(limitFrame, limitFrame))
                // SSL
                .nextDuplex("SSL", new SslProtoDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }
}