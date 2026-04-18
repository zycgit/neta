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
package net.hasor.neta.channel.transport.quic;

import java.util.Arrays;
import org.junit.Test;

/**
 * RFC 9001 §5.8 / RFC 9369 §3.3.3 — Retry Integrity Tag。
 * <p>覆盖：
 * <ul>
 *   <li>RFC 9001 Appendix A.4 官方测试向量（QUIC v1）</li>
 *   <li>tag 篡改 → 校验失败</li>
 *   <li>ODCID 不匹配 → 校验失败</li>
 *   <li>QUIC v1 / v2 key+nonce 产出不同 tag</li>
 *   <li>计算 / 校验 round-trip</li>
 * </ul>
 * <p>{@link QuicCrypto} 是 package-private，因此本文件位于
 * {@code net.hasor.neta.channel.transport.quic} 同包，而不是 {@code rfc/} 子包。
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicRFCRetryTagTest {

    /**
     * RFC 9001 Appendix A.4 Retry 测试向量：
     * <pre>
     *   ODCID = 8394c8f03e515708
     *   Retry packet (wire) =
     *     ff000000010008f067a5502a4262b574 6f6b656e04a265ba2eff4d829058fb3f 0f2496ba
     *                                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ tag
     *   Expected integrity tag = 04a265ba2eff4d829058fb3f0f2496ba
     * </pre>
     */
    @Test
    public void testRetryIntegrityTagV1_RFC9001_AppendixA4() throws Exception {
        byte[] odcid = QuicCrypto.hexToBytes("8394c8f03e515708");
        // Retry bytes without the trailing 16-byte tag.
        byte[] retryWithoutTag = QuicCrypto.hexToBytes(//
                "ff00000001"                   // first byte + version
                        + "00"                 // DCID length = 0
                        + "08f067a5502a4262b5" // SCID length + SCID
                        + "746f6b656e"         // Retry Token = "token"
        );
        byte[] expectedTag = QuicCrypto.hexToBytes("04a265ba2eff4d829058fb3f0f2496ba");

        byte[] tag = QuicCrypto.computeRetryIntegrityTag(odcid, retryWithoutTag, QuicVersion.V1);
        assert Arrays.equals(expectedTag, tag) : "v1 Retry tag mismatch: expected=" //
                + QuicCrypto.bytesToHex(expectedTag) + " got=" + QuicCrypto.bytesToHex(tag);
    }

    /** verifyRetryIntegrityTag 应能确认 RFC 9001 Appendix A.4 的完整 Retry 包。 */
    @Test
    public void testVerifyRetryIntegrityTagV1_RFC9001_AppendixA4() throws Exception {
        byte[] odcid = QuicCrypto.hexToBytes("8394c8f03e515708");
        byte[] retryFull = QuicCrypto.hexToBytes(//
                "ff00000001"                              //
                        + "00"                            //
                        + "08f067a5502a4262b5"            //
                        + "746f6b656e"                    //
                        + "04a265ba2eff4d829058fb3f0f2496ba" // tag
        );
        assert QuicCrypto.verifyRetryIntegrityTag(odcid, retryFull, QuicVersion.V1) //
                : "v1 Retry tag verification must succeed with RFC 9001 A.4 vector";
    }

    /** 篡改 tag 后 verifyRetryIntegrityTag 必须返回 false。 */
    @Test
    public void testVerifyRetryIntegrityTagV1_TamperedRejected() throws Exception {
        byte[] odcid = QuicCrypto.hexToBytes("8394c8f03e515708");
        byte[] retryFull = QuicCrypto.hexToBytes(//
                "ff00000001"                              //
                        + "00"                            //
                        + "08f067a5502a4262b5"            //
                        + "746f6b656e"                    //
                        + "04a265ba2eff4d829058fb3f0f2496ba");
        // Flip a single bit in the tag.
        retryFull[retryFull.length - 1] ^= 0x01;
        assert !QuicCrypto.verifyRetryIntegrityTag(odcid, retryFull, QuicVersion.V1) //
                : "tampered v1 Retry tag must fail verification";
    }

    /** 篡改 ODCID 后 verifyRetryIntegrityTag 必须返回 false (AAD 变化)。 */
    @Test
    public void testVerifyRetryIntegrityTagV1_WrongOdcidRejected() throws Exception {
        byte[] wrongOdcid = QuicCrypto.hexToBytes("8394c8f03e515707"); // last byte changed
        byte[] retryFull = QuicCrypto.hexToBytes(//
                "ff00000001"                              //
                        + "00"                            //
                        + "08f067a5502a4262b5"            //
                        + "746f6b656e"                    //
                        + "04a265ba2eff4d829058fb3f0f2496ba");
        assert !QuicCrypto.verifyRetryIntegrityTag(wrongOdcid, retryFull, QuicVersion.V1) //
                : "Retry tag verification must fail when ODCID does not match";
    }

    /** v2 Retry tag 应与 v1 不同 (密钥和 nonce 不同)。 */
    @Test
    public void testRetryIntegrityTagV2DiffersFromV1() throws Exception {
        byte[] odcid = QuicCrypto.hexToBytes("8394c8f03e515708");
        byte[] retryWithoutTag = QuicCrypto.hexToBytes(//
                "cf6b3343cf"                   // v2 first byte + version
                        + "00"                 //
                        + "08f067a5502a4262b5" //
                        + "746f6b656e"         //
        );
        byte[] tagV1 = QuicCrypto.computeRetryIntegrityTag(odcid, retryWithoutTag, QuicVersion.V1);
        byte[] tagV2 = QuicCrypto.computeRetryIntegrityTag(odcid, retryWithoutTag, QuicVersion.V2);
        assert tagV1.length == 16 && tagV2.length == 16 : "tag length must be 16";
        assert !Arrays.equals(tagV1, tagV2) : "v1 and v2 Retry tags must differ (different key/nonce)";
    }

    /** Round-trip: 计算出的 tag 拼接后必须通过校验。 */
    @Test
    public void testRetryIntegrityTagRoundTrip() throws Exception {
        byte[] odcid = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
        byte[] retryWithoutTag = QuicCrypto.hexToBytes(//
                "f000000001"                         // long header + fixed + type=11 + unused, v1
                        + "04" + "aabbccdd"          // DCID len + DCID
                        + "05" + "1122334455"        // SCID len + SCID
                        + "deadbeef"                 // token
        );
        byte[] tag = QuicCrypto.computeRetryIntegrityTag(odcid, retryWithoutTag, QuicVersion.V1);
        byte[] full = new byte[retryWithoutTag.length + tag.length];
        System.arraycopy(retryWithoutTag, 0, full, 0, retryWithoutTag.length);
        System.arraycopy(tag, 0, full, retryWithoutTag.length, tag.length);
        assert QuicCrypto.verifyRetryIntegrityTag(odcid, full, QuicVersion.V1) //
                : "round-trip v1 Retry tag verification must succeed";
    }
}
