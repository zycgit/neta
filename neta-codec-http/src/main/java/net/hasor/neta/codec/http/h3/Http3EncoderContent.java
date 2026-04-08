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
package net.hasor.neta.codec.http.h3;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Http3HttpToFrameEncoder} 使用的连接级状态容器。
 * <p>
 * 相关操作被划分为四类：
 * <ul>
 *   <li><b>init</b>：构造方法，所有状态会在构造时完成初始化。</li>
 *   <li><b>append</b>：由编码器调用，用于构建出站 frame，包括 QPACK 编码、请求 stream ID 分配和当前 stream 绑定。</li>
 *   <li><b>inject</b>：由 Duplexe 调用，在服务端模式下于执行编码前设置当前响应的 stream ID。</li>
 *   <li><b>release</b>：无额外资源需要释放，QPACK 编码器可由 GC 回收。</li>
 * </ul>
 * 所有字段均为私有，调用方不得直接访问内部子对象。
 */
class Http3EncoderContent {
    private final QpackEncoder qpackEncoder;
    private final AtomicLong   nextStreamId;
    private       long         currentStreamId;
    private       long         responseStreamId;
    private       boolean      settingsSent;
    private       boolean      pendingRequest;
    private       String       pendingMethod;
    private       String       pendingPath;
    private       String       pendingScheme;
    private       boolean      pendingResponse;
    private       int          pendingStatus;

    Http3EncoderContent(boolean serverMode, int maxTableSize) {
        this.qpackEncoder = new QpackEncoder(maxTableSize, false);
        this.nextStreamId = new AtomicLong(serverMode ? 1 : 0);
        this.currentStreamId = 0;
        this.responseStreamId = -1;
        this.settingsSent = false;
        this.pendingRequest = false;
        this.pendingMethod = null;
        this.pendingPath = null;
        this.pendingScheme = null;
        this.pendingResponse = false;
        this.pendingStatus = 0;
    }

    // ─── preface / settings state ─────────────────────────────────────────────

    /**
     * 当初始 SETTINGS 已发送时返回 {@code true}。
     */
    boolean isSettingsSent() {
        return settingsSent;
    }

    /**
     * 标记初始 SETTINGS 已发送。
     */
    void markSettingsSent() {
        this.settingsSent = true;
    }

    // ─── stream ID management ─────────────────────────────────────────────────

    /**
     * 返回当前出站消息应使用的 stream ID。
     */
    long currentStreamId() {
        return currentStreamId;
    }

    /**
     * 设置下一个出站响应要使用的 stream ID，主要用于服务端模式下由 Duplexe 注入。
     * 必须在 Duplexe 于 SND 路径调用编码器之前完成设置。
     */
    void setCurrentStreamId(long streamId) {
        this.currentStreamId = streamId;
    }

    /**
     * 为新的出站请求分配并返回下一个 stream ID，仅用于客户端模式。
     * 客户端发起的双向 stream 使用 0、4、8…… 这类 ID，按 4 递增。
     */
    long allocateNextStreamId() {
        long id = nextStreamId.getAndAdd(4);
        this.currentStreamId = id;
        return id;
    }

    /**
     * 返回待处理的响应 stream ID；如果不存在则返回 -1。
     */
    long responseStreamId() {
        return responseStreamId;
    }

    /**
     * 设置响应 stream ID，由 Duplexe 在发送服务端响应前注入。
     */
    void setResponseStreamId(long streamId) {
        this.responseStreamId = streamId;
    }

    /**
     * 消费并返回待处理的响应 stream ID。
     * 消费后内部值会重置为 -1。
     * @return 响应 stream ID；如果没有则返回 -1
     */
    long consumeResponseStreamId() {
        long id = this.responseStreamId;
        if (id >= 0) {
            this.responseStreamId = -1;
            this.currentStreamId = id;
        }
        return id;
    }

    void beginRequest(long streamId, String method, String path, String scheme) {
        this.currentStreamId = streamId;
        this.pendingRequest = true;
        this.pendingMethod = method;
        this.pendingPath = path;
        this.pendingScheme = scheme;
        this.pendingResponse = false;
        this.pendingStatus = 0;
    }

    void beginResponse(long streamId, int statusCode) {
        this.currentStreamId = streamId;
        this.pendingRequest = false;
        this.pendingMethod = null;
        this.pendingPath = null;
        this.pendingScheme = null;
        this.pendingResponse = true;
        this.pendingStatus = statusCode;
    }

    boolean hasPendingRequest() {
        return this.pendingRequest;
    }

    String pendingMethod() {
        return this.pendingMethod;
    }

    String pendingPath() {
        return this.pendingPath;
    }

    String pendingScheme() {
        return this.pendingScheme;
    }

    boolean hasPendingResponse() {
        return this.pendingResponse;
    }

    int pendingStatus() {
        return this.pendingStatus;
    }

    void clearPendingHeaders() {
        this.pendingRequest = false;
        this.pendingMethod = null;
        this.pendingPath = null;
        this.pendingScheme = null;
        this.pendingResponse = false;
        this.pendingStatus = 0;
    }

    // ─── QPACK header encoding ────────────────────────────────────────────────

    /**
     * 开始一次新的 QPACK header-block 编码会话。
     */
    void beginHeaderEncode() {
        qpackEncoder.beginEncode();
    }

    /**
     * 在当前会话中编码单个头字段。
     */
    void encodeHeader(String name, String value) {
        qpackEncoder.encodeHeaderDirect(name, value);
    }

    /**
     * 返回当前会话中已编码的字节数。
     */
    int headerEncodedLength() {
        return qpackEncoder.encodedLength();
    }

    /**
     * 返回内部编码缓冲区的引用。
     * 有效范围为 0 到 {@link #headerEncodedLength()} - 1。
     */
    byte[] headerEncodedBuffer() {
        return qpackEncoder.encodedBuffer();
    }

    /**
     * 完成编码并返回完整 QPACK 压缩 header block 的副本。
     * 必须在 {@link #beginHeaderEncode()} 以及全部 {@link #encodeHeader} 调用之后执行。
     */
    byte[] finishHeaderEncode() {
        int len = qpackEncoder.encodedLength();
        byte[] block = new byte[len];
        System.arraycopy(qpackEncoder.encodedBuffer(), 0, block, 0, len);
        return block;
    }
}
