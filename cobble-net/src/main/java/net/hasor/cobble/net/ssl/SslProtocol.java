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
 * 支持的 SSL 协议
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public enum SslProtocol {
    /**
     * SSL v2 Hello
     * @deprecated SSLv2Hello is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated SSL_v2Hello("SSLv2Hello"),
    /**
     * SSL v2
     * @deprecated SSLv2 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated SSL_v2("SSLv2"),
    /**
     * SSLv3
     * @deprecated SSLv3 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated SSL_v3("SSLv3"),
    /**
     * TLS v1
     * @deprecated TLSv1 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated TLS_v1("TLSv1"),
    /**
     * TLS v1.1
     * @deprecated TLSv1.1 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated TLS_v1_1("TLSv1.1"),
    /**
     * TLS v1.2
     */
    TLS_v1_2("TLSv1.2"),
    /**
     * TLS v1.3
     */
    TLS_v1_3("TLSv1.3"),
    ;

    private final String protocol;

    SslProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getProtocol() {
        return this.protocol;
    }
}
