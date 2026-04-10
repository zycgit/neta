# QUIC 特性实现与测试追踪

> 覆盖范围：RFC 9000（QUIC 传输）、RFC 9001（TLS 集成）、RFC 9002（丢包检测与拥塞控制）、RFC 9221（DATAGRAM 扩展）。
> 图例：`[x]` 已验证 / `[ ]` 待测试 / `[~]` 部分实现 / `[-]` 不适用/暂不支持

## 测试环境准备

- **JDK**: Java 8+
- **构建**: `cd neta && ./mvnw clean install`
- **测试文件位置**: `neta-core/src/test/java/net/hasor/neta/channel/quic/`

---

## 一、握手与连接建立（RFC 9000 §7 / RFC 9001）

### 1.1 QUIC 握手流程

- **文件**: `QuicAsyncChannelHandshake.java`, `QuicAsyncServerChannel.java`, `QuicAsyncClientChannel.java`
- **RFC**: RFC 9000 §7, RFC 9001 §4

| 测试点                                         | 状态    |
|---------------------------------------------|-------|
| Client 发送 Initial 包（Long Header, Type=0x00） | `[x]` |
| Server 回复 Initial + Handshake 包             | `[x]` |
| Client 发送 Handshake 完成 TLS 握手               | `[x]` |
| 握手完成后双方具备 1-RTT 密钥                          | `[x]` |
| `isEstablished()` 在握手完成后返回 `true`           | `[x]` |
| `HANDSHAKE_DONE` 帧由 Server 发送，Client 接收     | `[x]` |
| TLS 关闭时（`isSslEnabled=false`）纯文本短包头握手       | `[x]` |
| 握手失败时连接正确关闭并通知上层                            | `[x]` |
| 客户端识别 VN 包后 Future 失败（非降级）                  | `[x]` |
| 握手超时（连接不可达端口）                               | `[x]` |
| 服务端处理损坏 CRYPTO 帧不崩溃                         | `[x]` |
| 服务端静默丢弃垃圾 UDP 数据                            | `[x]` |
| 无效 Token → 服务端发送 Retry 包                    | `[x]` |

### 1.2 0-RTT 早期数据（Early Data）

- **文件**: `QuicAsyncChannelHandshake.java`, `QuicAsyncServerChannel.java#process0RttPacket()`
- **RFC**: RFC 9001 §4.9.1

| 测试点                                         | 状态    |
|---------------------------------------------|-------|
| Server 正确缓冲 0-RTT 包（Long Header, Type=0x01） | `[x]` |
| 缓冲上限 64 个包，超限后静默丢弃                          | `[x]` |
| `drain0RttData()` 在握手完成后按序返回缓冲数据            | `[x]` |
| 无活跃握手时静默丢弃 0-RTT 包                          | `[x]` |
| TLS 未启用时忽略 0-RTT 包                          | `[x]` |
| Anti-replay：相同 0-RTT 数据不重复处理                | `[x]` |

### 1.3 传输参数（Transport Parameters）

- **文件**: `QuicSoConfig.java`, `QuicAsyncChannelHandshake.java`
- **RFC**: RFC 9000 §7.4, §18

| 测试点                                                 | 状态    |
|-----------------------------------------------------|-------|
| `max_idle_timeout` (0x01) 参数正确协商                    | `[ ]` |
| `initial_max_data` (0x04) 参数正确协商                    | `[ ]` |
| `initial_max_stream_data_bidi_local` (0x05) 参数正确协商  | `[ ]` |
| `initial_max_stream_data_bidi_remote` (0x06) 参数正确协商 | `[ ]` |
| `initial_max_stream_data_uni` (0x07) 参数正确协商         | `[ ]` |
| `initial_max_streams_bidi` (0x08) 参数正确协商            | `[ ]` |
| `initial_max_streams_uni` (0x09) 参数正确协商             | `[ ]` |
| `max_udp_payload_size` (0x03) 参数正确协商                | `[ ]` |
| `active_connection_id_limit` (0x0e) 参数正确协商          | `[ ]` |
| `ack_delay_exponent` (0x0a) 参数正确协商                  | `[ ]` |
| `max_ack_delay` (0x0b) 参数正确协商                       | `[ ]` |
| `disable_active_migration` (0x0c) 参数正确处理            | `[ ]` |
| `stateless_reset_token` (0x02) 参数正确携带               | `[ ]` |
| `max_datagram_frame_size` (0x20) RFC 9221 参数协商      | `[ ]` |
| 接收到非法传输参数时发送 `TRANSPORT_PARAMETER_ERROR`            | `[ ]` |

---

## 二、数据包与帧格式（RFC 9000 §12, §17, §19）

### 2.1 包头格式

- **文件**: `QuicPacket.java`, `QuicAsyncChannelHandshake.java`
- **RFC**: RFC 9000 §17

| 测试点                                                  | 状态    |
|------------------------------------------------------|-------|
| Long Header 格式解析（Version, DCID, SCID, Token, Length） | `[x]` |
| Short Header 格式解析（DCID, Packet Number）               | `[~]` |
| 包号保护（Header Protection）加密/解密正确                       | `[x]` |
| 包号压缩（最小必要字节数发送）                                      | `[ ]` |
| 包号解压缩（基于 largest acknowledged PN 推算完整 PN）            | `[ ]` |
| Version 字段正确设置（0x00000001 for QUIC v1）               | `[x]` |
| Long/Short Header 最高位标识正确                            | `[x]` |

### 2.2 帧类型总览

- **文件**: `QuicFrameType.java`, `QuicVarInt.java`, `QuicChannelAsync.java`
- **RFC**: RFC 9000 §19

| 帧类型                            | 值         | RFC 章节      | 测试状态  |
|--------------------------------|-----------|-------------|-------|
| PADDING                        | 0x00      | §19.1       | `[ ]` |
| PING                           | 0x01      | §19.2       | `[ ]` |
| ACK                            | 0x02      | §19.3       | `[ ]` |
| ACK_ECN                        | 0x03      | §19.3       | `[ ]` |
| RESET_STREAM                   | 0x04      | §19.4       | `[ ]` |
| STOP_SENDING                   | 0x05      | §19.5       | `[ ]` |
| CRYPTO                         | 0x06      | §19.6       | `[ ]` |
| NEW_TOKEN                      | 0x07      | §19.7       | `[ ]` |
| STREAM (8 变体)                  | 0x08–0x0f | §19.8       | `[~]` |
| MAX_DATA                       | 0x10      | §19.9       | `[ ]` |
| MAX_STREAM_DATA                | 0x11      | §19.10      | `[ ]` |
| MAX_STREAMS bidi               | 0x12      | §19.11      | `[ ]` |
| MAX_STREAMS uni                | 0x13      | §19.11      | `[ ]` |
| DATA_BLOCKED                   | 0x14      | §19.12      | `[ ]` |
| STREAM_DATA_BLOCKED            | 0x15      | §19.13      | `[ ]` |
| STREAMS_BLOCKED bidi           | 0x16      | §19.14      | `[ ]` |
| STREAMS_BLOCKED uni            | 0x17      | §19.14      | `[ ]` |
| NEW_CONNECTION_ID              | 0x18      | §19.15      | `[ ]` |
| RETIRE_CONNECTION_ID           | 0x19      | §19.16      | `[ ]` |
| PATH_CHALLENGE                 | 0x1a      | §19.17      | `[ ]` |
| PATH_RESPONSE                  | 0x1b      | §19.18      | `[ ]` |
| CONNECTION_CLOSE (transport)   | 0x1c      | §19.19      | `[~]` |
| CONNECTION_CLOSE (application) | 0x1d      | §19.19      | `[ ]` |
| HANDSHAKE_DONE                 | 0x1e      | §19.20      | `[x]` |
| DATAGRAM (no length)           | 0x30      | RFC 9221 §4 | `[ ]` |
| DATAGRAM (with length)         | 0x31      | RFC 9221 §4 | `[ ]` |

### 2.3 可变长整数（Variable-Length Integer）

- **文件**: `QuicVarInt.java`
- **RFC**: RFC 9000 §16

| 测试点                                   | 状态    |
|---------------------------------------|-------|
| 1 字节编码（0x00–0x3f）                     | `[x]` |
| 2 字节编码（0x40–0x3fff）                   | `[x]` |
| 4 字节编码（0x4000–0x3fffffff）             | `[x]` |
| 8 字节编码（0x40000000–0x3fffffffffffffff） | `[x]` |
| 边界值：0, 63, 64, 16383, 16384           | `[x]` |
| `decode()` / `encode()` 互为逆操作         | `[x]` |

---

## 三、流管理（RFC 9000 §2, §3）

### 3.1 流创建与标识

- **文件**: `QuicStreamChannel.java`, `QuicChannelAsync.java`
- **RFC**: RFC 9000 §2.1

| 测试点                                              | 状态    |
|--------------------------------------------------|-------|
| Client 发起双向流：Stream ID = 4n（0, 4, 8 …）           | `[x]` |
| Server 发起双向流：Stream ID = 4n+1（1, 5, 9 …）         | `[x]` |
| Client 发起单向流：Stream ID = 4n+2（2, 6, 10 …）        | `[x]` |
| Server 发起单向流：Stream ID = 4n+3（3, 7, 11 …）        | `[x]` |
| 超过 `max_streams_bidi` 限制时发送 `STREAM_LIMIT_ERROR` | `[ ]` |
| 超过 `max_streams_uni` 限制时发送 `STREAM_LIMIT_ERROR`  | `[ ]` |
| 对端发送 STREAMS_BLOCKED 时自动扩展限制并回复 MAX_STREAMS      | `[ ]` |

### 3.2 流状态机

- **文件**: `QuicStreamChannel.java`
- **RFC**: RFC 9000 §3

| 测试点                                           | 状态    |
|-----------------------------------------------|-------|
| 发送端：Ready → Send → Data Sent → Data Rcvd      | `[ ]` |
| 发送端：Reset Sent → Reset Rcvd（via RESET_STREAM） | `[ ]` |
| 接收端：Recv → Size Known → Data Rcvd → Data Read | `[ ]` |
| 接收端：Reset Rcvd → Reset Read（via RESET_STREAM） | `[ ]` |
| FIN 位正确标记流结束，接收端检测到完整流                        | `[ ]` |
| `STOP_SENDING` 帧正确触发对端发送 `RESET_STREAM`       | `[ ]` |
| `RESET_STREAM` 帧正确携带错误码和最终大小                  | `[ ]` |
| 半关闭（只关闭写端 / 只关闭读端）正确处理                        | `[ ]` |

### 3.3 乱序流重组（Out-of-order Reassembly）

- **文件**: `QuicStreamReassembler.java`, `QuicChannelAsync.java#handleStreamFrame()`
- **RFC**: RFC 9000 §2.2

| 测试点                                    | 状态    |
|----------------------------------------|-------|
| 顺序到达的片段直接交付（零重组开销路径）                   | `[x]` |
| 乱序片段正确缓冲，gap 填补后按序交付                   | `[x]` |
| 重叠片段去重——保留旧数据，丢弃重复                     | `[x]` |
| FIN 不放最后字节时检测 `FINAL_SIZE_ERROR`       | `[ ]` |
| 同一流多个 FIN 且大小不一致时检测 `FINAL_SIZE_ERROR` | `[ ]` |
| 缓冲区超过 4MB 上限时触发 `FLOW_CONTROL_ERROR`   | `[ ]` |
| 流完成后自动从 `streamReassemblers` map 中移除   | `[ ]` |

---

## 四、流量控制（RFC 9000 §4）

### 4.1 连接级流量控制

- **文件**: `QuicFlowControl.java`, `QuicChannelAsync.java`
- **RFC**: RFC 9000 §4.1, §4.2

| 测试点                                      | 状态    |
|------------------------------------------|-------|
| 接收数据累计不超过协商的 `initial_max_data`          | `[x]` |
| 超过 MAX_DATA 限制时发送 `FLOW_CONTROL_ERROR`   | `[x]` |
| 消耗超过 50% 时自动发送 MAX_DATA 扩展窗口             | `[x]` |
| DATA_BLOCKED 帧触发立即发送 MAX_DATA            | `[ ]` |
| 窗口扩展策略：max(请求值×2, 当前×2)                  | `[x]` |
| `getConnectionBytesConsumed()` 与实际接收字节一致 | `[x]` |

### 4.2 流级流量控制

- **文件**: `QuicFlowControl.java`
- **RFC**: RFC 9000 §4.1, §4.2

| 测试点                                             | 状态    |
|-------------------------------------------------|-------|
| 每条流接收数据不超过协商的 `initial_max_stream_data_*`       | `[x]` |
| 超过单流 MAX_STREAM_DATA 限制时发送 `FLOW_CONTROL_ERROR` | `[x]` |
| 流消耗超过 50% 时自动发送 MAX_STREAM_DATA                 | `[x]` |
| STREAM_DATA_BLOCKED 帧触发立即发送 MAX_STREAM_DATA     | `[ ]` |
| 流关闭后不再跟踪该流的流量                                   | `[ ]` |

---

## 五、ACK 机制（RFC 9000 §13 / RFC 9002 §6）

### 5.1 ACK 生成

- **文件**: `QuicAckTracker.java`
- **RFC**: RFC 9000 §13.2, §19.3

| 测试点                                          | 状态    |
|----------------------------------------------|-------|
| 收到 2 个 ack-eliciting 包时立即发送 ACK              | `[x]` |
| 收到 non-ack-eliciting 包（ACK/PADDING）不延迟触发 ACK | `[ ]` |
| 检测到包号 gap（乱序到达）时立即发送 ACK                     | `[x]` |
| `maxAckDelay`（25ms）超时强制发送 ACK                | `[ ]` |
| ACK frame 中 Largest Acknowledged 正确          | `[ ]` |
| ACK Delay 字段正确计算（最大 25ms）                    | `[ ]` |
| ACK Ranges 正确合并并以 First+Gaps 格式编码            | `[x]` |
| ECN 计数（ECT0/ECT1/CE）添加到 ACK_ECN 帧（0x03）      | `[ ]` |

### 5.2 ACK 处理与丢包检测

- **文件**: `QuicSentPacketTracker.java`
- **RFC**: RFC 9002 §6

| 测试点                                           | 状态    |
|-----------------------------------------------|-------|
| `onAckReceived()` 正确解析 ACK ranges             | `[x]` |
| 新确认的包正确从 `sentPackets` 中移除                    | `[x]` |
| RTT 样本：`latestRtt = now − sentTime`           | `[x]` |
| `smoothedRtt` 更新：7/8×old + 1/8×new            | `[x]` |
| `rttVar` 更新：3/4×old + 1/4×\|smoothed−latest\| | `[x]` |
| `minRtt` 仅在 latestRtt < minRtt 时更新            | `[x]` |
| PACKET_THRESHOLD=3：PN 差 ≥3 的已发包标记丢失           | `[x]` |
| TIME_THRESHOLD=9/8×srtt：超时的已发包标记丢失            | `[ ]` |
| 丢失包的 payload 触发重传（`sendDataFrame`）            | `[ ]` |
| ACK-only 包不计入 RTT 样本和拥塞控制                     | `[x]` |

### 5.3 PTO（Probe Timeout）

- **文件**: `QuicSentPacketTracker.java`
- **RFC**: RFC 9002 §6.2

| 测试点                                                  | 状态    |
|------------------------------------------------------|-------|
| PTO = smoothedRtt + max(4×rttVar, 1ms) + maxAckDelay | `[ ]` |
| PTO 到期时发送 PING 探测帧                                   | `[ ]` |
| 收到 ACK 后重置 PTO 计时器                                   | `[x]` |
| 连续超时后指数退避（每次 PTO 翻倍）                                 | `[ ]` |
| 无未确认包时停止 PTO 计时                                      | `[x]` |

---

## 六、拥塞控制（RFC 9002 §7）

### 6.1 NewReno 状态机

- **文件**: `QuicCongestionControl.java`
- **RFC**: RFC 9002 §7

| 测试点                                                         | 状态    |
|-------------------------------------------------------------|-------|
| 初始拥塞窗口：INITIAL_WINDOW = 14720（10×max_datagram_size）         | `[x]` |
| 慢启动：每个 ACK 使 cwnd += acknowledged_bytes                     | `[x]` |
| 超过 ssthresh 后进入拥塞避免                                         | `[x]` |
| 拥塞避免：cwnd += max_datagram_size × acked / cwnd               | `[x]` |
| 检测到丢包：ssthresh = cwnd/2，cwnd = ssthresh                     | `[x]` |
| 持续拥塞（多次丢包）：cwnd = MINIMUM_WINDOW（2944）                      | `[x]` |
| ECN-CE 信号触发拥塞恢复（同丢包处理）                                      | `[x]` |
| `canSend(bytes)` 在 bytes_in_flight + bytes > cwnd 时返回 false | `[x]` |
| `onPacketAcked()` 正确减少 `bytesInFlight`                      | `[ ]` |
| `reset()` 在连接迁移后重置所有状态                                      | `[x]` |

---

## 七、路径验证与连接迁移（RFC 9000 §8, §9）

### 7.1 地址验证（Address Validation / Retry）

- **文件**: `QuicAsyncServerChannel.java#validateToken()`, `#sendRetryPacket()`
- **RFC**: RFC 9000 §8

| 测试点                                                     | 状态    |
|---------------------------------------------------------|-------|
| 首次 Initial 无 token 时发送 Retry 包                          | `[ ]` |
| Retry 包格式正确（Long Header, Type=Retry, Integrity Tag=16B） | `[ ]` |
| Token 格式：[4B 时间戳][4B 地址哈希][原始 DCID]                     | `[ ]` |
| Token 60 秒内有效，超时拒绝                                      | `[ ]` |
| Token 地址哈希与实际来源地址匹配                                     | `[ ]` |
| 无效 Token（篡改/过期）时发送 `INVALID_TOKEN` 错误并关闭                | `[ ]` |
| `NEW_TOKEN` 帧预发送 token 给客户端                             | `[-]` |

### 7.2 PATH_CHALLENGE / PATH_RESPONSE

- **文件**: `QuicPathValidator.java`
- **RFC**: RFC 9000 §8.2

| 测试点                                                             | 状态    |
|-----------------------------------------------------------------|-------|
| `initiateChallenge()` 生成 8 字节随机挑战数据                             | `[ ]` |
| PATH_CHALLENGE 帧格式正确（type=0x1a + 8B data）                       | `[ ]` |
| 收到 PATH_CHALLENGE 后立即回复匹配 PATH_RESPONSE                         | `[ ]` |
| PATH_RESPONSE 帧格式正确（type=0x1b + 8B data）                        | `[ ]` |
| `onPathResponse()` 验证 8 字节数据与挑战匹配                               | `[ ]` |
| 不匹配的 PATH_RESPONSE 静默丢弃                                         | `[ ]` |
| 挑战 3 秒超时后从 `pendingChallenges` 中移除                              | `[ ]` |
| `checkTimeouts()` 超时时 fail `validationFuture`（TimeoutException） | `[ ]` |
| `checkTimeouts()` 超时时调用 `onTimeoutCallback`                     | `[ ]` |
| 路径验证通过后 `currentPathValidated = true`                           | `[ ]` |
| `initiateChallenge(BasicFuture)` 重载：验证通过后携带 RTT 完成 future       | `[ ]` |

### 7.3 Anti-Amplification（防放大攻击）

- **文件**: `QuicPathValidator.java`, `QuicChannelAsync.java#sendDataFrame()`
- **RFC**: RFC 9000 §8.1, §9.3.1

| 测试点                                                     | 状态    |
|---------------------------------------------------------|-------|
| 迁移触发后 `antAmpActive = true`                             | `[ ]` |
| `recordIncoming(bytes)` 正确累计入站字节                        | `[ ]` |
| `canSendBytes(n)`：bytesIn×3 < bytesSent+n 时返回 false     | `[ ]` |
| `recordOutgoing(bytes)` 正确累计出站字节                        | `[ ]` |
| 超限时 `sendDataFrame` 直接丢弃并返回 0                           | `[ ]` |
| 路径验证通过后 `antAmpActive = false`，限制取消                     | `[ ]` |
| `dispatchAppData` 每次调用 `recordIncoming(rawData.length)` | `[ ]` |

### 7.4 连接迁移（Connection Migration）

- **文件**: `QuicAsyncServerChannel.java#handleConnectionMigration()`, `QuicChannelAsync.java#migrate()`, `QuicChannel.java#migrate()`
- **RFC**: RFC 9000 §9

| 测试点                                                       | 状态    |
|-----------------------------------------------------------|-------|
| 检测到不同来源地址的包时触发 `handleConnectionMigration`                | `[ ]` |
| `updateRemoteAddress()` 正确更新连接远端地址（volatile 可见性）          | `[ ]` |
| 迁移后调用 `onMigrationStart(closeCallback)` 激活 anti-amp       | `[ ]` |
| 迁移后发送 NEW_CONNECTION_ID 帧提供新 CID                          | `[ ]` |
| 所有 active local CIDs 通过 `putIfAbsent` 注册进 `connectionMap` | `[ ]` |
| 迁移后重置拥塞控制（`congestionControl.reset()`）                    | `[ ]` |
| 迁移后发送 PATH_CHALLENGE 验证新路径                                | `[ ]` |
| PATH_CHALLENGE 超时触发 `closeCallback`（错误码 NO_VIABLE_PATH）   | `[ ]` |
| `QuicChannel.migrate()` 可被客户端主动调用                         | `[ ]` |
| `migrate()` 返回 `Future<Long>`，PATH_RESPONSE 收到后携带 RTT 完成  | `[ ]` |
| `disable_active_migration` 参数禁止客户端主动迁移                    | `[-]` |
| 服务端发起迁移（preferred_address 参数）                             | `[-]` |

---

## 八、连接 ID 管理（RFC 9000 §5）

### 8.1 CID 生命周期

- **文件**: `QuicConnectionIdManager.java`
- **RFC**: RFC 9000 §5.1

| 测试点                                                            | 状态    |
|----------------------------------------------------------------|-------|
| `issueNewConnectionId()` 生成新 CID + Stateless Reset Token       | `[ ]` |
| NEW_CONNECTION_ID 帧格式正确（Sequence No、Retire Prior To、CID、Token） | `[ ]` |
| 活跃 CID 数量不超过 `active_connection_id_limit`                      | `[ ]` |
| 超出限制时 `issueNewConnectionId()` 返回 null                         | `[ ]` |
| `onNewConnectionId()` 存储对端新 CID，根据 `retire_prior_to` 退役旧 CID   | `[ ]` |
| `onRetireConnectionId()` 退役本地指定 CID，并发送替换 CID                  | `[ ]` |
| `getActiveRemoteCid()` 返回当前在用对端 CID                            | `[ ]` |
| `rotateRemoteCid()` 切换到下一个可用对端 CID                             | `[ ]` |
| `getActiveLocalCids()` 返回所有活跃本地 CID 列表                         | `[ ]` |
| 收到 RETIRE_CONNECTION_ID 后自动补发新 CID                             | `[ ]` |
| `isStatelessReset(token)` 检测已知 reset token                     | `[ ]` |

---

## 九、连接终止（RFC 9000 §10）

### 9.1 正常关闭（Immediate Close）

- **文件**: `QuicChannel.java#close()`, `QuicChannelAsync.java#closeWithError()`
- **RFC**: RFC 9000 §10.2

| 测试点                                                       | 状态    |
|-----------------------------------------------------------|-------|
| `close()` 发送 CONNECTION_CLOSE 帧（type=0x1c，error=NO_ERROR） | `[x]` |
| `closeWithError(code, reason)` 发送携带错误码的 CONNECTION_CLOSE  | `[x]` |
| Reason Phrase 长度不超过 `CLOSE_MAX_REASON_LEN`（1200 字节）       | `[ ]` |
| 关闭后到达的非关闭帧静默丢弃                                            | `[x]` |
| 关闭后上层 Channel 收到 `SoCloseException`                       | `[ ]` |
| 所有子流在连接关闭时同步关闭并通知异常                                       | `[ ]` |
| 收到对端的 CONNECTION_CLOSE 帧时正确关闭本地连接                         | `[x]` |

### 9.2 空闲超时（Idle Timeout）

- **文件**: `QuicChannelAsync.java#checkIdleTimeouts()`
- **RFC**: RFC 9000 §10.1

| 测试点                                                   | 状态    |
|-------------------------------------------------------|-------|
| 双方协商的 `max_idle_timeout` 取较小值                         | `[ ]` |
| 连接空闲超过超时值时静默关闭（不发 CONNECTION_CLOSE）                   | `[ ]` |
| 关闭时通知上层 `QuicIdleTimeoutException`（isConnection=true） | `[ ]` |
| `lastActivityTime` 在每次收发包时更新                          | `[ ]` |
| 流级空闲超时（`streamIdleTimeoutMs`）独立触发                     | `[ ]` |
| 流级超时通知 `QuicIdleTimeoutException`（isConnection=false） | `[ ]` |
| `max_idle_timeout=0` 时禁用超时                            | `[ ]` |

### 9.3 无状态重置（Stateless Reset）

- **文件**: `QuicAsyncServerChannel.java#sendStatelessReset()`, `#computeStatelessResetToken()`
- **RFC**: RFC 9000 §10.3

| 测试点                                              | 状态    |
|--------------------------------------------------|-------|
| 未知 DCID 的短包头包触发无状态重置                             | `[ ]` |
| Reset token = HMAC-SHA256(TOKEN_KEY, dcid)[0:16] | `[ ]` |
| 重置包总长 < 触发包长度（防放大攻击）                             | `[ ]` |
| 重置包最小长度 21 字节（16B token + 5B 最小头）                | `[ ]` |
| 首字节：bit7=0（Short Header），bit6=1（Fixed Bit）       | `[ ]` |
| 前缀随机字节填充（非确定性）                                   | `[ ]` |
| 触发包 < 21 字节时不发送重置包                               | `[ ]` |
| 客户端收到 Stateless Reset 后正确关闭连接                    | `[ ]` |

---

## 十、密钥与加密（RFC 9001）

### 10.1 TLS 1.3 密钥分层

- **文件**: `QuicAsyncChannelHandshake.java`, `QuicTlsEngine.java`
- **RFC**: RFC 9001 §4, §5

| 测试点                                             | 状态    |
|-------------------------------------------------|-------|
| Initial 密鑰推导（HKDF + `client initial secret` 盐値） | `[x]` |
| Handshake 密鑰推导（从 TLS Handshake 阶段密鑰）            | `[x]` |
| 1-RTT 应用层密鑰推导（从 TLS Application 阶段密鑰）           | `[x]` |
| Header Protection 密鑰推导（`quic hp` 标签）            | `[x]` |
| AEAD-AES-128-GCM 套件正确加解密                        | `[x]` |
| AEAD-AES-256-GCM 套件正确加解密                        | `[ ]` |
| 每个加密级别有独立的读/写密鑰对                                | `[x]` |
| ChaCha20-Poly1305 套件                            | `[-]` |

### 10.2 密钥更新（Key Update）

- **文件**: `QuicAsyncChannelHandshake.java#rotateReadKeys/rotateWriteKeys`, `QuicChannelAsync.java#handleKeyUpdate()`
- **RFC**: RFC 9001 §6, RFC 8446 §4.6.3

| 测试点                                       | 状态    |
|-------------------------------------------|-------|
| 收到 KeyUpdate(request_update=0) 时轮转读取密钥    | `[ ]` |
| 收到 KeyUpdate(request_update=1) 时轮转读写密钥并响应 | `[ ]` |
| HKDF-Expand-Label `quic ku` 推导新写入密钥       | `[ ]` |
| HKDF-Expand-Label `quic iv` 推导新 IV        | `[ ]` |
| Header Protection 密钥在 KeyUpdate 后保持不变     | `[ ]` |
| `keyUpdateGeneration` 计数器正确递增             | `[ ]` |
| 旧密钥在若干包确认后才丢弃                             | `[ ]` |
| 握手完成前不允许执行 KeyUpdate                      | `[ ]` |

---

## 十一、DATAGRAM 扩展（RFC 9221）

### 11.1 DATAGRAM 帧

- **文件**: `QuicDatagramChannel.java`, `QuicChannelAsync.java`
- **RFC**: RFC 9221 §4

| 测试点                                         | 状态    |
|---------------------------------------------|-------|
| `max_datagram_frame_size` 传输参数协商            | `[ ]` |
| DATAGRAM(0x30) 无 Length 字段（占满 Packet 剩余空间）  | `[ ]` |
| DATAGRAM(0x31) 有 Length 字段                  | `[ ]` |
| DATAGRAM 帧发送（`QuicDatagramChannel.write()`） | `[ ]` |
| DATAGRAM 帧接收并分发给 datagramChannel            | `[ ]` |
| DATAGRAM 帧不受 Flow Control 限制                | `[ ]` |
| 丢失的 DATAGRAM 帧不重传                           | `[ ]` |
| 帧大小超过 MTU 时报错                               | `[ ]` |
| 对端未协商 DATAGRAM 支持时拒绝发送                      | `[ ]` |

---

## 十二、PING 与存活探测（RFC 9000 §19.2）

### 12.1 PING RTT 测量

- **文件**: `QuicChannelAsync.java#sendPingRtt()`, `QuicChannel.java#ping()`

| 测试点                                                  | 状态    |
|------------------------------------------------------|-------|
| `ping()` 发送 PING 帧并立即返回 `Future<Long>`               | `[x]` |
| PING 帧被 ACK 后 Future 完成，值为往返时间（ms）                   | `[x]` |
| `ping(timeoutMs)` 超时后 Future 以 `TimeoutException` 失败 | `[x]` |
| 多个并发 PING 各自对应正确的 Future                             | `[ ]` |
| PING 帧 ACK 的 PN 正确匹配 `pendingPings` map              | `[ ]` |

---

## 十三、版本协商（RFC 9000 §6）

| 测试点                                                                | 状态    |
|--------------------------------------------------------------------|-------|
| 收到不支持版本的 Initial 包时发送 Version Negotiation 包                        | `[x]` |
| Version Negotiation 包格式（Version=0, DCID, SCID, Supported Versions） | `[x]` |
| 客户端收到 VN 包后降级重连                                                    | `[-]` |
| 客户端收到 VN 包后 Future 以异常完成                                           | `[x]` |

---

## 十四、包大小与 MTU（RFC 9000 §14）

| 测试点                                      | 状态    |
|------------------------------------------|-------|
| Client Initial 包使用 PADDING 填充至最小 1200 字节 | `[ ]` |
| `max_udp_payload_size` 正确限制出包大小          | `[ ]` |
| 多个 QUIC 包合并进单个 UDP 数据报（Coalescing）       | `[-]` |
| Path MTU Discovery（PMTUD）                | `[-]` |

---

## 十五、错误码体系（RFC 9000 §20.1）

| 错误码                       | 值    | 触发场景                              | 状态    |
|---------------------------|------|-----------------------------------|-------|
| NO_ERROR                  | 0x00 | 正常关闭                              | `[ ]` |
| INTERNAL_ERROR            | 0x01 | 内部异常                              | `[ ]` |
| CONNECTION_REFUSED        | 0x02 | 握手阶段服务端拒绝                         | `[ ]` |
| FLOW_CONTROL_ERROR        | 0x03 | 超过 MAX_DATA / MAX_STREAM_DATA     | `[ ]` |
| STREAM_LIMIT_ERROR        | 0x04 | 超过 MAX_STREAMS                    | `[ ]` |
| STREAM_STATE_ERROR        | 0x05 | 帧到达时流状态不允许                        | `[x]` |
| FINAL_SIZE_ERROR          | 0x06 | FIN 后收到超出最终大小的数据                  | `[ ]` |
| FRAME_ENCODING_ERROR      | 0x07 | 无法解析的帧格式                          | `[ ]` |
| TRANSPORT_PARAMETER_ERROR | 0x08 | 非法传输参数                            | `[ ]` |
| CONNECTION_ID_LIMIT_ERROR | 0x09 | CID 超出 active_connection_id_limit | `[ ]` |
| PROTOCOL_VIOLATION        | 0x0a | 协议违规（通用）                          | `[ ]` |
| INVALID_TOKEN             | 0x0b | Retry Token 无效                    | `[x]` |
| APPLICATION_ERROR         | 0x0c | 应用层关闭                             | `[ ]` |
| CRYPTO_BUFFER_EXCEEDED    | 0x0d | CRYPTO 数据速率过高                     | `[ ]` |
| KEY_UPDATE_ERROR          | 0x0e | 密钥更新失败                            | `[ ]` |
| AEAD_LIMIT_REACHED        | 0x0f | AEAD 完整性限制达到                      | `[ ]` |
| NO_VIABLE_PATH            | 0x10 | PATH_CHALLENGE 超时，路径不可用           | `[ ]` |

---

## 十六、实现文件清单

### 新增工具类

| 类名                        | 主要职责                               | RFC                 |
|---------------------------|------------------------------------|---------------------|
| `QuicAckTracker`          | ACK 生成、接收包跟踪、ACK ranges 合并         | RFC 9000 §13        |
| `QuicSentPacketTracker`   | 丢包检测、RTT 估算、PTO 计时、重传触发            | RFC 9002 §6         |
| `QuicCongestionControl`   | NewReno 拥塞控制（慢启动/拥塞避免/恢复）          | RFC 9002 §7         |
| `QuicFlowControl`         | 连接级 + 流级入站流量控制                     | RFC 9000 §4         |
| `QuicStreamReassembler`   | 乱序 STREAM/CRYPTO 数据重组              | RFC 9000 §2.2       |
| `QuicConnectionIdManager` | CID 发行、轮转、退役、Stateless Reset Token | RFC 9000 §5.1       |
| `QuicPathValidator`       | 路径验证、anti-amp 限制、迁移 future         | RFC 9000 §8.2, §9.3 |

### 核心类变更摘要

| 类名                          | 主要变更                                                                                                                                              |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `QuicChannelAsync`          | 集成全部 7 个工具类；`sendDataFrame()` 增加 anti-amp 守卫 + 拥塞控制 + ACK piggyback；`dispatchReceivedFrames()` 完整 21 种帧分发；`migrate()` 主动迁移；`sendPingRtt()` RTT 测量 |
| `QuicAsyncChannelHandshake` | Key Update 密钥轮转；0-RTT 数据缓冲与排出；TLS 握手生命周期管理                                                                                                        |
| `QuicAsyncServerChannel`    | Stateless Reset（HMAC 生成）；完整迁移处理（anti-amp + CID 注册 + 超时关闭）；Token/Retry 验证；0-RTT 处理；`dispatchAppData` 入站计量                                          |
| `QuicChannel`               | `ping()` / `ping(timeoutMs)` → `Future<Long>`；`migrate()` 主动迁移公开 API                                                                              |
| `QuicSoConfig`              | QUIC 传输参数全覆盖（RFC 0x01–0x09, 0x0e, 0x20），含 RFC 参数 ID 注释                                                                                            |

---

## 十七、测试建议

### 测试分层

| 层次                | 范围                         | 示例                                            |
|-------------------|----------------------------|-----------------------------------------------|
| **单元测试**          | 单个工具类独立验证                  | `QuicVarIntTest`, `QuicStreamReassemblerTest` |
| **协议单元**          | 帧编解码往返                     | 构建帧 → 序列化 → 解析 → 断言字段                         |
| **集成（plaintext）** | Client+Server 环回，TLS=false | 流传输、DATAGRAM、迁移                               |
| **集成（TLS）**       | 全加密路径                      | 握手、Key Update、0-RTT                           |
| **故障注入**          | 模拟丢包、乱序、延迟                 | 重传验证、PTO 触发                                   |
| **压力测试**          | 高并发多流                      | 流量控制稳定性、拥塞窗口                                  |

### 建议测试文件结构

```
neta-core/src/test/java/net/hasor/neta/channel/quic/
├── QuicVarIntTest.java
├── QuicStreamReassemblerTest.java
├── QuicAckTrackerTest.java
├── QuicSentPacketTrackerTest.java
├── QuicCongestionControlTest.java
├── QuicFlowControlTest.java
├── QuicConnectionIdManagerTest.java
├── QuicPathValidatorTest.java
└── integration/
    ├── QuicPlaintextLoopbackTest.java      # TLS=false 环回基础
    ├── QuicTlsHandshakeTest.java           # TLS=true 握手全流程
    ├── QuicStreamMultiplexTest.java        # 多流并发读写
    ├── QuicFlowControlIntegrationTest.java # 流量控制
    ├── QuicConnectionMigrationTest.java    # 连接迁移（anti-amp, CID 轮转, 超时）
    ├── QuicKeyUpdateTest.java              # 密钥更新
    └── QuicDatagramTest.java              # DATAGRAM 扩展
```

### 重要边界条件

- 零长度 STREAM 帧（仅 FIN 位）
- PADDING-only 包（不触发 ACK，不更新 RTT）
- 超大包（> max_udp_payload_size）
- 包号接近 2⁶²（序号回绕）
- 双方同时发送 CONNECTION_CLOSE
- 握手期间连接迁移（应拒绝）
- Stream ID 恰好达到 max_streams 上限
- 收到重复 ACK ranges（应幂等处理）

---

*最后更新: 2026-03-01（补充 §10.1 TLS 1.3 密钥推导状态：Initial/Handshake/1-RTT/HeaderProtection/AEAD-128-GCM/每级独立密钥对 → `[x]`；补充 §2.1 包头格式：Long Header/Header Protection → `[x]`，Short Header → `[~]`；修正 §10.1 文件引用 QuicCrypto→QuicTlsEngine；共 68 个 QUIC 测试通过）*

---

## 十八、已完成测试清单

### Batch 1（基础连接与流收发）

| 测试方法                                      | 文件                           | 覆盖 RFC                                                             | 完成日期       |
|-------------------------------------------|------------------------------|--------------------------------------------------------------------|------------|
| `testHandshakeCompletes`                  | `QuicSimplyTest`             | RFC 9000 §7, RFC 9001; plaintext 握手                                | 2026-02-27 |
| `testBidiStreamClient2ServerEcho`         | `QuicBidiStreamSndRcvTest`   | RFC 9000 §2, §3.1; STREAM 帧; client-bidi 流                         | 2026-02-27 |
| `testBidiMultipleStreamClient2ServerEcho` | `QuicBidiStreamSndRcvTest`   | RFC 9000 §2.1; 多流复用隔离                                              | 2026-02-27 |
| `testUniStreamServer2Client`              | `QuicUniStreamSndRcvTest`    | RFC 9000 §3.4; server-uni 流推送                                      | 2026-02-27 |
| `testUniMultipleStreamServer2Client`      | `QuicUniStreamSndRcvTest`    | RFC 9000 §3.4; 多条 server-uni 流独立性                                  | 2026-02-27 |
| `testUniStreamWithIllegalClientWrite`     | `QuicUniStreamSndRcvBadTest` | RFC 9000 §3.4, §19.8, §10.2; STREAM_STATE_ERROR + CONNECTION_CLOSE | 2026-02-28 |

### Batch 2 — 已完成项

| 测试方法                                          | 文件                           | 覆盖 RFC                                                                 | 完成日期       |
|-----------------------------------------------|------------------------------|------------------------------------------------------------------------|------------|
| `testClientUniStreamWithIllegalServerWrite`   | `QuicUniStreamSndRcvBadTest` | RFC 9000 §3.4, §19.8, §10.2; STREAM_STATE_ERROR + CONNECTION_CLOSE（对称） | 2026-02-28 |
| `testBidiStreamClient2ServerMultipleMessages` | `QuicBidiStreamSndRcvTest`   | RFC 9000 §2.2; bidi client→server 顺序交付                                 | 2026-02-28 |
| `testBidiStreamServer2ClientMultipleMessages` | `QuicBidiStreamSndRcvTest`   | RFC 9000 §2.2; bidi server→client 顺序交付                                 | 2026-02-28 |
| `testUniStreamClient2ServerMultipleMessages`  | `QuicUniStreamSndRcvTest`    | RFC 9000 §2.2; uni client→server 顺序交付                                  | 2026-02-28 |
| `testUniStreamServer2ClientMultipleMessages`  | `QuicUniStreamSndRcvTest`    | RFC 9000 §2.2; uni server→client 顺序交付                                  | 2026-02-28 |

### Batch 2 — RFC 合规测试（握手 + 流 ID）

| 测试方法                                          | 文件                      | 覆盖 RFC                                                              | 完成日期       |
|-----------------------------------------------|-------------------------|---------------------------------------------------------------------|------------|
| `testStreamIdEncoding`                        | `rfc/QuicRFCStreamTest` | RFC 9000 §2.1; 流 ID 编码 bit0/bit1 + 步长4                              | 2026-02-28 |
| `testClientSendsInitialPacket`                | `rfc/QuicRFCStreamTest` | RFC 9000 §17.2; Initial 包 Long Header + Type=0x00 + Version + CID   | 2026-02-28 |
| `testServerRepliesInitialPacket`              | `rfc/QuicRFCStreamTest` | RFC 9000 §7; 服务端 Initial 响应 + CID 映射 + 1-RTT 验证                     | 2026-02-28 |
| `testServerInitialPacketFormat`               | `rfc/QuicRFCStreamTest` | RFC 9000 §17.2; Server Initial DCID=client SCID + Version + SCID 长度 | 2026-02-28 |
| `testIsEstablishedAfterHandshake`             | `rfc/QuicRFCStreamTest` | RFC 9000 §7; connectAsync 返回 QuicChannel 即已 ESTABLISHED             | 2026-02-28 |
| `testPlaintextHandshakeFlow`                  | `rfc/QuicRFCStreamTest` | RFC 9000 §7; plaintext 2-packet 握手 + CID 更新 + PING/ACK 1-RTT        | 2026-02-28 |
| `testHandshakeRejectsUnknownVersion`          | `rfc/QuicRFCStreamTest` | RFC 9000 §6; 错误 Version → 服务端回复 VN 包 + DCID/SCID/版本列表格式验证           | 2026-02-28 |
| `testServerIgnoresNonInitialForNewConnection` | `rfc/QuicRFCStreamTest` | RFC 9000 §7; 非-Initial Long Header 不应建立新连接                          | 2026-02-28 |
| `testLongShortHeaderBitIdentification`        | `rfc/QuicRFCStreamTest` | RFC 9000 §17; bit7 Long/Short Header + Fixed Bit                    | 2026-02-28 |

### Batch 2 — TLS RFC 9001 合规测试

| 测试方法                                | 文件                      | 覆盖 RFC                                                   | 完成日期       |
|-------------------------------------|-------------------------|----------------------------------------------------------|------------|
| `testTlsHandshakeEstablished`       | `rfc/QuicRFCStreamTest` | RFC 9001 §4; TLS 1.3 握手完成 + isEstablished + isSslEnabled | 2026-02-28 |
| `testTlsHandshakeDoneFrame`         | `rfc/QuicRFCStreamTest` | RFC 9001 §4.1.2; HANDSHAKE_DONE 帧由 Server 发送             | 2026-02-28 |
| `testTlsHandshakeEventSequence`     | `rfc/QuicRFCStreamTest` | RFC 9001 §4; TLS 握手事件序列 Initial→Handshake→1-RTT          | 2026-02-28 |
| `testTlsPostHandshake1Rtt`          | `rfc/QuicRFCStreamTest` | RFC 9001 §4; 握手后 1-RTT STREAM 帧收发验证                      | 2026-02-28 |
| `testTlsServerCertificateReceived`  | `rfc/QuicRFCStreamTest` | RFC 9001 §4.4; 客户端成功接收服务端证书链                             | 2026-02-28 |
| `testTlsClientSendsHandshakePacket` | `rfc/QuicRFCStreamTest` | RFC 9001 §4; 客户端发送 Handshake 响应（Finished + CRYPTO）       | 2026-02-28 |

### 握手失败场景覆盖

| 测试方法                                     | 文件                      | 覆盖 RFC                                | 完成日期       |
|------------------------------------------|-------------------------|---------------------------------------|------------|
| `testHandshakeTimeout`                   | `rfc/QuicRFCStreamTest` | RFC 9000 §7; 连接不可达端口 → Future 超时/失败   | 2026-02-28 |
| `testServerHandlesCryptoFrameCorruption` | `rfc/QuicRFCStreamTest` | RFC 9000 §7; 损坏 CRYPTO 帧 → 服务端不崩溃     | 2026-02-28 |
| `testServerSilentDiscardsGarbage`        | `rfc/QuicRFCStreamTest` | RFC 9000 §5; 非 QUIC 垃圾数据 → 静默丢弃       | 2026-02-28 |
| `testServerSendsRetryForInvalidToken`    | `rfc/QuicRFCStreamTest` | RFC 9000 §8.1; 无效 Token → Retry 包格式验证 | 2026-02-28 |
| `testClientRecognizesVersionNegotiation` | `rfc/QuicRFCStreamTest` | RFC 9000 §6; 客户端识别 VN 包 + 版本列表解析      | 2026-02-28 |

### Batch 2 — 连接生命周期与 PING RTT

| 测试方法                          | 文件               | 覆盖 RFC                                                                      | 完成日期       |
|-------------------------------|------------------|-----------------------------------------------------------------------------|------------|
| `testHandshakeCompletes`      | `QuicSimplyTest` | RFC 9000 §7; connectAsync 返回 QuicChannel 即已 ESTABLISHED                     | 2026-02-28 |
| `testConnectionGracefulClose` | `QuicSimplyTest` | RFC 9000 §10.2; closeGracefully 发送 CONNECTION_CLOSE(NO_ERROR) + Future 正常完成 | 2026-02-28 |
| `testPingRtt`                 | `QuicSimplyTest` | RFC 9000 §12 §19.2; PING 帧发送 + ACK 接收 + RTT 测量                              | 2026-02-28 |

### Batch 2 — 0-RTT 早期数据（RFC 9001 §4.9.1）

| 测试方法                                              | 文件                    | 覆盖 RFC                                            | 完成日期       |
|---------------------------------------------------|-----------------------|---------------------------------------------------|------------|
| `testServerDiscardsZeroRttWithoutActiveHandshake` | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; 无活跃握手时静默丢弃 0-RTT 包               | 2026-02-28 |
| `testServerBuffers0RttDuringTlsHandshake`         | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; TLS 握手进行中缓冲 0-RTT 包（最多 64 个）     | 2026-02-28 |
| `testServerDiscards0RttAboveBufferLimit`          | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; 超过缓冲上限 64 个包后静默丢弃                | 2026-02-28 |
| `testServerIgnores0RttWhenTlsDisabled`            | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; TLS 禁用时忽略 0-RTT（established 连接上） | 2026-02-28 |
| `testDrains0RttDataAfterHandshakeCompletes`       | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; 握手完成后 `drain0RttData()` 按序交付缓冲数据 | 2026-02-28 |
| `testAntiReplayBlocks0RttDuplicates`              | `rfc/QuicRFCRtt0Test` | RFC 9001 §4.9.1; Anti-replay：相同0-RTT包号不重复处理       | 2026-02-28 |

### simply 原生客户端 Smoke 测试

| 测试方法                                | 文件                            | 覆盖内容                              | 完成日期       |
|-------------------------------------|-------------------------------|-----------------------------------|------------|
| `testHandshakeCompletes`            | `simply/QuicSimplyClientTest` | 原生 UDP 明文握手（Raw QuicSimplyClient） | 2026-02-28 |
| `testSendPingAndReceiveAck`         | `simply/QuicSimplyClientTest` | PING 帧发送 + ACK 接收                 | 2026-02-28 |
| `testSendStreamFrame`               | `simply/QuicSimplyClientTest` | STREAM 帧构建与发送                     | 2026-02-28 |
| `testSendMultipleFramesInOnePacket` | `simply/QuicSimplyClientTest` | 多帧合并进单个包                          | 2026-02-28 |
| `testFrameBuilderCorrectness`       | `simply/QuicSimplyClientTest` | 帧构建器序列化基础验证                       | 2026-02-28 |

### Batch 3 — 纯单元测试（VarInt / ACK / Reassembler）

| 测试方法                              | 文件                          | 覆盖 RFC                               | 完成日期       |
|-----------------------------------|-----------------------------|--------------------------------------|------------|
| `testVarIntEncode1Byte`           | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 1 字节编码 0–63            | 2026-02-28 |
| `testVarIntEncode2Byte`           | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 2 字节编码 64–16383        | 2026-02-28 |
| `testVarIntEncode4Byte`           | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 4 字节编码 16384–10^9      | 2026-02-28 |
| `testVarIntEncode8Byte`           | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 8 字节编码 10^9+           | 2026-02-28 |
| `testVarIntBoundaryValues`        | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 边界值编解码                 | 2026-02-28 |
| `testVarIntRoundTrip`             | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; encode↔decode 互逆       | 2026-02-28 |
| `testVarIntEncodedLength`         | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; encodedLength 一致性      | 2026-02-28 |
| `testVarIntEncodeNegativeThrows`  | `rfc/QuicRFCHandshakeTest`  | RFC 9000 §16; 负数抛异常                  | 2026-02-28 |
| `testShouldNotAckBeforeThreshold` | `QuicAckTrackerTest`        | RFC 9000 §13.2; threshold 前不发 ACK    | 2026-02-28 |
| `testAckAfterTwoElicitingPackets` | `QuicAckTrackerTest`        | RFC 9000 §13.2; 达 threshold 后发 ACK   | 2026-02-28 |
| `testImmediateAckOnOutOfOrder`    | `QuicAckTrackerTest`        | RFC 9000 §13.2; gap 触发立即 ACK         | 2026-02-28 |
| `testAckRangesContiguous`         | `QuicAckTrackerTest`        | RFC 9000 §19.3; 连续 PN→1 range        | 2026-02-28 |
| `testAckRangesWithGap`            | `QuicAckTrackerTest`        | RFC 9000 §19.3; 缺 PN→2 ranges        | 2026-02-28 |
| `testGenerateResetsCounter`       | `QuicAckTrackerTest`        | RFC 9000 §13.2; generate 后 pending=0 | 2026-02-28 |
| `testInOrderFragments`            | `QuicStreamReassemblerTest` | RFC 9000 §2.2; 按序片段直接交付              | 2026-02-28 |
| `testOutOfOrderReassembly`        | `QuicStreamReassemblerTest` | RFC 9000 §2.2; 乱序→重组→正确交付            | 2026-02-28 |
| `testDuplicateFragmentIgnored`    | `QuicStreamReassemblerTest` | RFC 9000 §2.2; 重复片段去重                | 2026-02-28 |
| `testFinFlagSignalsStreamEnd`     | `QuicStreamReassemblerTest` | RFC 9000 §2.2; FIN 标记流结束             | 2026-02-28 |
| `testBufferOverflowRejects`       | `QuicStreamReassemblerTest` | RFC 9000 §2.2; 缓冲区溢出拒绝               | 2026-02-28 |

### Batch 3 — 帧交互（CONNECTION_CLOSE）

| 测试方法                                | 文件                     | 覆盖 RFC                                                   | 完成日期       |
|-------------------------------------|------------------------|----------------------------------------------------------|------------|
| `testCloseWithErrorCode`            | `rfc/QuicRFCFrameTest` | RFC 9000 §10.2; closeWithError(INTERNAL_ERROR) Future 完成 | 2026-02-28 |
| `testServerReceivesConnectionClose` | `rfc/QuicRFCFrameTest` | RFC 9000 §10.2; 客户端 CONNECTION_CLOSE→服务端关闭               | 2026-02-28 |

### Batch 3 — 其它（关闭后行为、PING 超时）

| 测试方法                                | 文件                     | 覆盖 RFC                               | 完成日期       |
|-------------------------------------|------------------------|--------------------------------------|------------|
| `testPostCloseNewStreamFails`       | `rfc/QuicRFCOtherTest` | RFC 9000 §10.2; 关闭后新建流失败             | 2026-02-28 |
| `testPostClosePingFails`            | `rfc/QuicRFCOtherTest` | RFC 9000 §10.2; 关闭后 ping 失败          | 2026-02-28 |
| `testPingTimeoutOnClosedConnection` | `rfc/QuicRFCOtherTest` | RFC 9000 §12/§19.2; 关闭连接 ping 超时快速失败 | 2026-02-28 |

### Batch 4 — QuicSentPacketTracker 单元测试（RFC 9002 §5/§6）

| 测试方法                                 | 文件                          | 覆盖 RFC                                              | 完成日期       |
|--------------------------------------|-----------------------------|-----------------------------------------------------|------------|
| `testInitialValues`                  | `QuicSentPacketTrackerTest` | RFC 9002 §6.2.2; 初始 smoothedRtt=333ms, rttVar=166ms | 2026-03-01 |
| `testBytesInFlightAckEliciting`      | `QuicSentPacketTrackerTest` | RFC 9002 §6; ack-eliciting 包计入/减少 bytesInFlight     | 2026-03-01 |
| `testBytesInFlightNonAckEliciting`   | `QuicSentPacketTrackerTest` | RFC 9002 §6; 非 ack-eliciting 包不计 bytesInFlight      | 2026-03-01 |
| `testUnackedCountTracking`           | `QuicSentPacketTrackerTest` | RFC 9002 §6; unackedCount 随发送/确认增减                  | 2026-03-01 |
| `testFirstRttSampleInitialization`   | `QuicSentPacketTrackerTest` | RFC 9002 §5.3; 首次 RTT 样本替换初始估算值                     | 2026-03-01 |
| `testMinRttTracking`                 | `QuicSentPacketTrackerTest` | RFC 9002 §5.2; minRtt 在首次 ACK 后更新                   | 2026-03-01 |
| `testPacketThresholdLossDetection`   | `QuicSentPacketTrackerTest` | RFC 9002 §6.1.1; PACKET_THRESHOLD=3 丢包声明            | 2026-03-01 |
| `testNoFalseLossBeforeThreshold`     | `QuicSentPacketTrackerTest` | RFC 9002 §6.1.1; gap < 3 时不误报丢包                     | 2026-03-01 |
| `testRangeAckRemovesMultiplePackets` | `QuicSentPacketTrackerTest` | RFC 9002 §5; 范围 ACK 移除多个包，bytesInFlight 归零          | 2026-03-01 |
| `testPtoResetAfterAllAcked`          | `QuicSentPacketTrackerTest` | RFC 9002 §6.2; 全部确认后 PTO 定时器重置                      | 2026-03-01 |

### Batch 4 — QuicCongestionControl 单元测试（RFC 9002 §7）

| 测试方法                                 | 文件                          | 覆盖 RFC                                                   | 完成日期       |
|--------------------------------------|-----------------------------|----------------------------------------------------------|------------|
| `testInitialWindowAndState`          | `QuicCongestionControlTest` | RFC 9002 §7.2; INITIAL_WINDOW=14720, MINIMUM_WINDOW=2944 | 2026-03-01 |
| `testSlowStartCwndGrowth`            | `QuicCongestionControlTest` | RFC 9002 §7.3.1; 慢启动 cwnd += ackedBytes                  | 2026-03-01 |
| `testSlowStartToAvoidanceTransition` | `QuicCongestionControlTest` | RFC 9002 §7.3.1/7.3.3; cwnd≥ssthresh 进入拥塞避免              | 2026-03-01 |
| `testCongestionAvoidanceFormula`     | `QuicCongestionControlTest` | RFC 9002 §7.3.3; CA 公式：cwnd += 1472*acked/cwnd           | 2026-03-01 |
| `testLossEntersRecovery`             | `QuicCongestionControlTest` | RFC 9002 §7.3.2; 丢包→RECOVERY，ssthresh=cwnd/2             | 2026-03-01 |
| `testMinimumWindowEnforced`          | `QuicCongestionControlTest` | RFC 9002 §7.2; cwnd 不低于 MINIMUM_WINDOW=2944              | 2026-03-01 |
| `testNoDoubleReductionInRecovery`    | `QuicCongestionControlTest` | RFC 9002 §7.3.2; RECOVERY 中重复丢包不再削减                      | 2026-03-01 |
| `testRecoveryExit`                   | `QuicCongestionControlTest` | RFC 9002 §7.3.2; PN>recoveryStartPn 的确认退出 Recovery       | 2026-03-01 |
| `testPersistentCongestion`           | `QuicCongestionControlTest` | RFC 9002 §7.6; 持续拥塞→cwnd=MINIMUM_WINDOW                  | 2026-03-01 |
| `testReset`                          | `QuicCongestionControlTest` | RFC 9002 §9.4; reset() 恢复初始状态                            | 2026-03-01 |
| `testCanSend`                        | `QuicCongestionControlTest` | RFC 9002 §7; canSend() 门控发送                              | 2026-03-01 |
| `testEcnCongestionTreatedAsLoss`     | `QuicCongestionControlTest` | RFC 9002 §7.1; ECN-CE 信号视同丢包                             | 2026-03-01 |

### Batch 4 — QuicFlowControl 单元测试（RFC 9000 §4 / §19.9 / §19.10）

| 测试方法                                        | 文件                    | 覆盖 RFC                                              | 完成日期       |
|---------------------------------------------|-----------------------|-----------------------------------------------------|------------|
| `testConnectionDataWithinLimit`             | `QuicFlowControlTest` | RFC 9000 §4.1; 连接级接收在限额内                            | 2026-03-01 |
| `testConnectionDataViolation`               | `QuicFlowControlTest` | RFC 9000 §4.1; 累计超出 MAX_DATA → violation            | 2026-03-01 |
| `testValidateStreamDataValid`               | `QuicFlowControlTest` | RFC 9000 §4.1; 流级数据在限额内                             | 2026-03-01 |
| `testValidateStreamDataViolation`           | `QuicFlowControlTest` | RFC 9000 §4.1; 流级数据超出 MAX_STREAM_DATA → violation   | 2026-03-01 |
| `testConnectionWindowNoExpansionBelow50Pct` | `QuicFlowControlTest` | RFC 9000 §4.2; 低于 50% 不扩展连接窗口                       | 2026-03-01 |
| `testConnectionWindowExpandAt50Pct`         | `QuicFlowControlTest` | RFC 9000 §4.2; 达 50% 扩展连接窗口（×2）                     | 2026-03-01 |
| `testStreamWindowNoExpansionBelow50Pct`     | `QuicFlowControlTest` | RFC 9000 §4.2; 流级窗口低于 50% 不扩展                       | 2026-03-01 |
| `testStreamWindowExpandAt50Pct`             | `QuicFlowControlTest` | RFC 9000 §4.2; 流级窗口达 50% 扩展                         | 2026-03-01 |
| `testBuildMaxDataFrame`                     | `QuicFlowControlTest` | RFC 9000 §19.9; MAX_DATA 帧格式（type=0x10）             | 2026-03-01 |
| `testBuildMaxStreamDataFrame`               | `QuicFlowControlTest` | RFC 9000 §19.10; MAX_STREAM_DATA 帧格式（type=0x11）     | 2026-03-01 |
| `testGettersAndUpdateMaxData`               | `QuicFlowControlTest` | RFC 9000 §4; getters 与 updateConnectionMaxData 单调递增 | 2026-03-01 |
