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
package net.hasor.neta.channel.quic;

import org.junit.Test;

/**
 * QuicStreamReassembler 单元测试 — RFC 9000 §2.2 流数据乱序重组。
 * <p>
 * 因 {@link QuicStreamReassembler} 是包私有类，测试必须位于同包
 * {@code net.hasor.neta.channel.quic} 下。
 * <p>
 * 测试项（A3-1 ~ A3-5）：
 * <ul>
 *   <li>A3-1: 按序片段 — readContiguous() 一次性返回完整数据</li>
 *   <li>A3-2: 乱序片段 — 先到后半段，再到前半段，重组正确</li>
 *   <li>A3-3: 重复片段 — 相同 offset 不会导致数据重复</li>
 *   <li>A3-4: FIN 标记 — isFinReceived() / isComplete() 正确</li>
 *   <li>A3-5: 缓冲区溢出 — 超过 maxBufferSize 的片段被拒绝</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStreamReassemblerTest {

    /** A3-1: 按序添加 3 个片段后，readContiguous() 一次性返回完整数据。 */
    @Test
    public void testInOrderFragments() {
        QuicStreamReassembler r = new QuicStreamReassembler();
        r.addFragment(0, "Hello".getBytes(), false);
        r.addFragment(5, " ".getBytes(), false);
        r.addFragment(6, "World".getBytes(), false);
        byte[] result = r.readContiguous();
        assert result != null : "readContiguous must return data";
        assert "Hello World".equals(new String(result)) : "Expected 'Hello World', got '" + new String(result) + "'";
    }

    /** A3-2: 先添加 offset=5 片段，再添加 offset=0 片段；readContiguous() 返回正确顺序数据。 */
    @Test
    public void testOutOfOrderReassembly() {
        QuicStreamReassembler r = new QuicStreamReassembler();
        // 先发后半段
        r.addFragment(5, "World".getBytes(), false);
        byte[] partial = r.readContiguous();
        assert partial == null : "Must not deliver with gap at offset 0";
        // 再发前半段
        r.addFragment(0, "Hello".getBytes(), false);
        byte[] result = r.readContiguous();
        assert result != null : "readContiguous must return data after gap filled";
        assert "HelloWorld".equals(new String(result)) : "Expected 'HelloWorld', got '" + new String(result) + "'";
    }

    /** A3-3: 相同 offset 的片段添加两次；readContiguous() 数据不重复。 */
    @Test
    public void testDuplicateFragmentIgnored() {
        QuicStreamReassembler r = new QuicStreamReassembler();
        r.addFragment(0, "ABCD".getBytes(), false);
        r.addFragment(0, "ABCD".getBytes(), false); // duplicate
        byte[] result = r.readContiguous();
        assert result != null : "readContiguous must return data";
        assert result.length == 4 : "Data must not be duplicated, expected 4 bytes, got " + result.length;
        assert "ABCD".equals(new String(result));
    }

    /** A3-4: 添加带 fin=true 的最终片段后，isFinReceived() = true。 */
    @Test
    public void testFinFlagSignalsStreamEnd() {
        QuicStreamReassembler r = new QuicStreamReassembler();
        r.addFragment(0, "data".getBytes(), false);
        assert !r.isFinReceived() : "FIN must not be set yet";
        r.addFragment(4, "end".getBytes(), true);
        assert r.isFinReceived() : "FIN must be set after fin=true fragment";
        byte[] result = r.readContiguous();
        assert result != null;
        assert "dataend".equals(new String(result));
        assert r.isComplete() : "Stream must be complete after FIN delivered";
    }

    /** A3-5: 设置 maxBufferSize=16，超出限制的片段被拒绝。 */
    @Test
    public void testBufferOverflowRejects() {
        QuicStreamReassembler r = new QuicStreamReassembler();
        r.setMaxBufferSize(16);
        // Fill to limit
        boolean ok = r.addFragment(0, new byte[16], false);
        assert ok : "Fragment within limit must be accepted";
        // Exceed limit
        boolean rejected = r.addFragment(16, new byte[1], false);
        assert !rejected : "Fragment exceeding maxBufferSize must be rejected";
    }
}
