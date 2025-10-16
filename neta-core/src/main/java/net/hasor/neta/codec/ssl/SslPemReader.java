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
package net.hasor.neta.codec.ssl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hasor.cobble.codec.Base64;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Reads a PEM file and converts it into a list of DERs so that they are imported into a {@link KeyStore} easily.
 * Netty's ByteBuf is replaced with cobble.bytebuf
 * @author 赵永春 (zyc@hasor.net)
 * @author netty
 * @version : 2023-09-24
 */
class SslPemReader {
    private static final Pattern CERT_HEADER = Pattern.compile("-+BEGIN\\s[^-\\r\\n]*CERTIFICATE[^-\\r\\n]*-+(?:\\s|\\r|\\n)+");
    private static final Pattern CERT_FOOTER = Pattern.compile("-+END\\s[^-\\r\\n]*CERTIFICATE[^-\\r\\n]*-+(?:\\s|\\r|\\n)*");
    private static final Pattern KEY_HEADER  = Pattern.compile("-+BEGIN\\s[^-\\r\\n]*PRIVATE\\s+KEY[^-\\r\\n]*-+(?:\\s|\\r|\\n)+");
    private static final Pattern KEY_FOOTER  = Pattern.compile("-+END\\s[^-\\r\\n]*PRIVATE\\s+KEY[^-\\r\\n]*-+(?:\\s|\\r|\\n)*");
    private static final Pattern BODY        = Pattern.compile("[a-z0-9+/=][a-z0-9+/=\\r\\n]*", Pattern.CASE_INSENSITIVE);

    public static ByteBuf[] readCertificates(InputStream in) throws CertificateException {
        String content;
        try {
            content = readContent(in);
        } catch (IOException e) {
            throw new CertificateException("failed to read certificate input stream", e);
        }

        List<ByteBuf> certs = new ArrayList<>();
        Matcher m = CERT_HEADER.matcher(content);
        int start = 0;
        for (; ; ) {
            if (!m.find(start)) {
                break;
            }
            m.usePattern(BODY);
            if (!m.find()) {
                break;
            }

            String base64 = m.group(0);
            base64 = base64.replace("\r", "").replace("\n", "").trim();

            m.usePattern(CERT_FOOTER);
            if (!m.find()) {
                // Certificate is incomplete.
                break;
            }

            certs.add(ByteBuf.wrap(Base64.base64DecodeToArray(base64)));

            start = m.end();
            m.usePattern(CERT_HEADER);
        }

        if (certs.isEmpty()) {
            throw new CertificateException("found no certificates in input stream");
        }

        return certs.toArray(new ByteBuf[0]);
    }

    public static ByteBuf readPrivateKey(InputStream in) throws KeyException {
        String content;
        try {
            content = readContent(in);
        } catch (IOException e) {
            throw new KeyException("failed to read key input stream", e);
        }

        Matcher m = KEY_HEADER.matcher(content);
        if (!m.find()) {
            throw keyNotFoundException();
        }
        m.usePattern(BODY);
        if (!m.find()) {
            throw keyNotFoundException();
        }

        String base64 = m.group(0);
        base64 = base64.replace("\r", "").replace("\n", "").trim();

        m.usePattern(KEY_FOOTER);
        if (!m.find()) {
            throw keyNotFoundException();// Key is incomplete.
        }

        return ByteBuf.wrap(Base64.base64DecodeToArray(base64));
    }

    private static KeyException keyNotFoundException() {
        return new KeyException("could not find a PKCS #8 private key in input stream (reference https://netty.io/wiki/sslcontextbuilder-and-private-key.html for more information)");
    }

    private static String readContent(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            for (; ; ) {
                int ret = in.read(buf);
                if (ret < 0) {
                    break;
                }
                out.write(buf, 0, ret);
            }
            return out.toString(StandardCharsets.US_ASCII.name());
        } finally {
            IOUtils.closeQuietly(out);
        }
    }
}