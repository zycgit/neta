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
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.security.cert.X509Certificate;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslUtilsTest {
    @Test
    public void cerTest() throws Exception {
        X509Certificate[] cer = SslUtils.toX509Certificates(ResourcesUtils.getResourceAsStream("ssl/ca/server.crt"));
        assert MD5.encodeMD5(cer[0].getPublicKey().getEncoded()).equals("e2c2b22bf508fcd051a3895e4d3766a3");
    }
}