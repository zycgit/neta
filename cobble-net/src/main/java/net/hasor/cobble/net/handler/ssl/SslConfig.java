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
package net.hasor.cobble.net.handler.ssl;
import net.hasor.cobble.net.handler.PipeConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * SSL 配置
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslConfig extends PipeConfig {
    private SslProvider            provider              = SslProvider.JDK;      // default is JDK
    private SslClientAuth          clientAuth            = SslClientAuth.NONE;   //
    private String[]               appProtocol           = null;                 // TLS 扩展，NPN/ALPN（应用层协议协商）
    private String[]               ciphers               = null;                 // JSSE Cipher Suite Names 使用的密钥套件
    private String[]               protocols             = null;                 // The TLS protocol versions to enable.
    private int                    maxResizingNetBufSize = 128 * 1024;           // 当 SSL 在 WRAP/UNWRAP 期间遇到 BUFFER_OVERFLOW 后最大允许的扩容容量，默认 128KB
    private int                    maxResizingAppBufSize = 128 * 1024;           // 当 SSL 在 WRAP/UNWRAP 期间遇到 BUFFER_OVERFLOW 后最大允许的扩容容量，默认 128KB
    //
    private SslAuthKeyType         authType              = null;
    private String                 jksResource           = null;                 // JKS File
    private String                 pemCertChain          = null;                 // X.509 certificate chain in PEM format.
    private String                 pemPrivate            = null;                 // PKCS#8 private key in PEM format.
    private String                 keyPassword           = null;
    //
    private KeyStore               keyStore              = null;
    private KeyManagerFactory      keyManagerFactory     = null;
    private TrustManagerFactory    trustManagerFactory   = null;
    private SslAppProtocolSelector appProtocolSelector   = null;

    public SslProvider getProvider() {
        return this.provider;
    }

    public void setProvider(SslProvider provider) {
        this.provider = provider;
    }

    public SslClientAuth getClientAuth() {
        return this.clientAuth;
    }

    public void setClientAuth(SslClientAuth clientAuth) {
        this.clientAuth = clientAuth;
    }

    public String[] getAppProtocol() {
        return this.appProtocol;
    }

    public void setAppProtocol(String[] appProtocol) {
        this.appProtocol = appProtocol;
    }

    public String[] getCiphers() {
        return this.ciphers;
    }

    public void setCiphers(String[] ciphers) {
        this.ciphers = ciphers;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public int getMaxResizingNetBufSize() {
        return this.maxResizingNetBufSize;
    }

    public void setMaxResizingNetBufSize(int maxResizingNetBufSize) {
        this.maxResizingNetBufSize = maxResizingNetBufSize;
    }

    public int getMaxResizingAppBufSize() {
        return this.maxResizingAppBufSize;
    }

    public void setMaxResizingAppBufSize(int maxResizingAppBufSize) {
        this.maxResizingAppBufSize = maxResizingAppBufSize;
    }

    public SslAuthKeyType getAuthType() {
        return this.authType;
    }

    public void setAuthType(SslAuthKeyType authType) {
        this.authType = authType;
    }

    public String getJksResource() {
        return this.jksResource;
    }

    public void setJksResource(String jksResource) {
        this.jksResource = jksResource;
    }

    public String getPemCertChain() {
        return this.pemCertChain;
    }

    public void setPemCertChain(String pemCertChain) {
        this.pemCertChain = pemCertChain;
    }

    public String getPemPrivate() {
        return this.pemPrivate;
    }

    public void setPemPrivate(String pemPrivate) {
        this.pemPrivate = pemPrivate;
    }

    public String getKeyPassword() {
        return this.keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public KeyStore getKeyStore() {
        return this.keyStore;
    }

    public void setKeyStore(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public KeyManagerFactory getKeyManagerFactory() {
        return this.keyManagerFactory;
    }

    public void setKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keyManagerFactory = keyManagerFactory;
    }

    public TrustManagerFactory getTrustManagerFactory() {
        return this.trustManagerFactory;
    }

    public void setTrustManagerFactory(TrustManagerFactory trustManagerFactory) {
        this.trustManagerFactory = trustManagerFactory;
    }

    public SslAppProtocolSelector getAppProtocolSelector() {
        return this.appProtocolSelector;
    }

    public void setAppProtocolSelector(SslAppProtocolSelector appProtocolSelector) {
        this.appProtocolSelector = appProtocolSelector;
    }
}