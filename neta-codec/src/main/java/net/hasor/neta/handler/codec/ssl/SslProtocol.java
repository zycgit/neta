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
/**
 * Supported SSL protocols
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface SslProtocol {
    /**
     * SSLv3，Supports SSL version 3; may support other versions
     * @deprecated jvm is disabled by default, SSLv3 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated
    String SSL_v3 = "SSLv3";

    /**
     * TLS v1，Supports RFC 2246: TLS version 1.0 ; may support other versions
     * @deprecated jvm is disabled by default, TLSv1 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated
    String TLS_v1 = "TLSv1";

    /**
     * TLS v1.1，Supports RFC 4346: TLS version 1.1 ; may support other versions
     * @deprecated jvm is disabled by default, TLSv1.1 is no longer secure. Consider using {@link #TLS_v1_2} or {@link #TLS_v1_3}
     */
    @Deprecated
    String TLS_v1_1 = "TLSv1.1";

    /**
     * TLS v1.2，Supports RFC 5246: TLS version 1.2 ; may support other versions
     */
    String TLS_v1_2 = "TLSv1.2";

    /**
     * TLS v1.3
     */
    String TLS_v1_3 = "TLSv1.3";

    /**
     * DTLS v1.0
     * @deprecated jvm is disabled by default, DTLSv1.0 is no longer secure. Consider using {@link #DTLS_v1_2}
     */
    @Deprecated
    String DTLS_v1_0 = "DTLSv1.0";

    /**
     * DTLS v1.2
     */
    String DTLS_v1_2 = "DTLSv1.2";
}
