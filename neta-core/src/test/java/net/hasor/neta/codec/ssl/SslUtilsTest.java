/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;

import java.security.cert.X509Certificate;

import org.junit.Test;

import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.codec.MD5;

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
