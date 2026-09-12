/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.LimitFrameHandler;

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

    public static SslConfig sslConfig(String[] sslProtocol) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(sslProtocol);
        return sslConfig;
    }

    public static SslConfig sslConfig(String sslProtocol) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(new String[] { sslProtocol });
        return sslConfig;
    }

    public static ProtoInitializer udpSslSocketProtoStack(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ProtoHelper.standard()
                // SSL
                .nextDuplex("SSL", new SslDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    public static ProtoInitializer udpSslSocketProtoStack(SslConfig sslConf, ProtoHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return ProtoHelper.typed(ByteBuf.class, ByteBuf.class)
                // SSL
                .nextDuplex("SSL", new SslDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }

    public static ProtoInitializer tcpSslSocketProtoStack(SslConfig sslConf, ProtoHandler<String, String> last) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        LimitFrameHandler limitFrame = new LimitFrameHandler(2);
        return ProtoHelper.typed(ByteBuf.class, ByteBuf.class)
                // limit package
                .nextDuplex("LIMIT", limitFrame, limitFrame)
                // SSL
                .nextDuplex("SSL", new SslDuplex(sslConf))
                // bytes <-> String
                .nextDuplex("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .nextDecoder(last).build();
    }

}
