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
package net.hasor.cobble.net.ssl;
/**
 * SSL 配置
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslConfig {
    private boolean       enable       = false;
    private SslProtocol   protocol     = SslProtocol.TLS_v1_2;  // default is TLS_v1_2
    private SslProvider   provider     = SslProvider.JDK;       // default is JDK
    private SslClientAuth clientAuth   = SslClientAuth.NONE;    //
    private String        pemCertChain = null;                  // X.509 certificate chain in PEM format.
    private String        pemPrivate   = null;                  // PKCS#8 private key in PEM format.
    private String        keyPassword  = null;

    public boolean isEnable() {
        return this.enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public SslProtocol getProtocol() {
        return this.protocol;
    }

    public void setProtocol(SslProtocol protocol) {
        this.protocol = protocol;
    }

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
}
