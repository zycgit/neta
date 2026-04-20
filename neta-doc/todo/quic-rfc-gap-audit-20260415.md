# QUIC RFC 对齐缺口复核（复核与修订版）

- 首次撰写：2026-04-15
- 本次修订：2026-04-17
- 本次复核范围：`neta-core/src/main/java/net/hasor/neta/channel/transport/quic/` 全部 38 个源文件、对应单元测试 18 个、以及 `neta-codec-http/src/main/java/net/hasor/neta/codec/http/h3/` 21 个源文件（重点 sampling）。
- 本次修订原则：**不做主观评价，所有结论附源码行号引用**。

## 2026-04-17 增补：P0/P1 收口进度

本日已完成 P0 全部与 P1 主要条目，QUIC 测试套件从 115 → 124 pass，0 failure，0 error。

| 原优先级 | 条目 | 状态 | 落地证据 |
| --- | --- | --- | --- |
| P0-1 | Retry Integrity Tag | ✅ 完成 | `QuicCrypto.computeRetryIntegrityTag` + `QuicCryptoRetryTagTest`（RFC 9001 A.4 向量） |
| P0-2 | 客户端 Retry 接收路径 | ✅ 完成 | `QuicAsyncClientChannel` Retry 分派、tag 校验、DCID 替换与 Initial 密钥重派生；`QuicRFCRetryIntegrationTest` |
| P0-3 | Key Update §6.1 合规（TLS KeyUpdate 拒绝） | ✅ 完成 | `QuicChannelAsync.handleKeyUpdate` 改为以 crypto error `0x010a` 关闭连接；`QuicRFCErrorCodesTest` |
| P1-4 | 入站 Stateless Reset 识别闭环 | ✅ 完成 | `QuicChannelAsync` 短包解密失败分支追加 reset-token 匹配→draining 状态迁移 |
| P1-5 | Transport parameter 接收侧校验 | ✅ 完成 | `QuicAsyncChannelHandshake.buildInitConfigData` + `validatePeerCidParams` 落地 ODCID / ISCID / RSCID 一致性与 `ack_delay_exponent ≤ 20`、`max_ack_delay < 2^14`、`active_connection_id_limit ≥ 2`、`stateless_reset_token` 仅服务端且 16 字节、`disable_active_migration` 语义；两侧 promotion 路径在错误时以 `TRANSPORT_PARAMETER_ERROR (0x08)` 拒绝；`QuicRFCTransportParamValidationTest` |
| P1-6 | Key Phase bit packet-level 状态机 | 🟡 部分完成 | 见下方专述 |

### P1-6 状态细述

本次已完成的部分：

- `QuicTlsEngine.getClientAppTrafficSecret()` / `getServerAppTrafficSecret()` 对外暴露 TLS 1.3 `application_traffic_secret_0`。
- `QuicAsyncChannelHandshake` 在握手完成点（客户端与服务端两处）各自捕获并缓存 `clientAppSecret`/`serverAppSecret`。
- 重写 `rotateReadKeys` / `rotateWriteKeys` 使用 **RFC 9001 §6.1 正确的 HKDF 链**：
  - `secret_{N+1} = HKDF-Expand-Label(secret_N, "quic ku", "", Hash.length)`
  - `key_{N+1} = HKDF-Expand-Label(secret_{N+1}, "quic key", "", key_len)`
  - `iv_{N+1}  = HKDF-Expand-Label(secret_{N+1}, "quic iv",  "", iv_len)`
  - HP 密钥按 §6.6 在代际间保持不变。
  - 修复前：错误地把当前 packet key 当作 HKDF 输入，不符合 RFC。
- 新增 `currentKeyPhase` 字段与 `getCurrentKeyPhase()` 访问器；`rotateWriteKeys` 每次轮换翻转 Key Phase bit 并推进 `keyUpdateGeneration`。

仍然留待后续收口：

- short header 发送路径将 `getCurrentKeyPhase()` 编码到首字节 bit 0x04；
- short header 接收路径按照 Key Phase bit 的翻转触发"试解密"、旧密钥 PTO 保留窗口、以及代际回滚攻击防护；
- 本端主动发起 key update 的外部触发点（当前 `rotate*` 无生产调用方）。

这些属于 packet-level 状态机，与收发主路径耦合较深，建议单独一次迭代完成并配合专项单元测试，本次不在范围内。

### P0/P1 阶段性结论

- QUIC 握手层、Retry、CID transport parameter、TLS KeyUpdate 合规、Stateless Reset 入站闭环均已对齐 RFC 9000/9001。
- Key Update 的 HKDF 派生算法已合规，但 packet-level Key Phase 状态机需在 P2 中独立完成。
- 对互操作公网部署而言，上述 P0/P1 收口之后的遗留主要是 0-RTT resumption 与多 PN space recovery 的细节打磨，以及互操作矩阵测试。

## 本次修订摘要

2026-04-15 原稿的结构基本正确，但若干结论存在以下问题：

| 原稿结论 | 本次复核实际情况 |
| --- | --- |
| Retry Integrity Tag 是"简化实现" | **准确**。已在本次修订中修复（见下文"已修复项"） |
| 0-RTT 只实现了缓冲/排水 | **基本准确**，但需细化：确实只在握手期缓冲 0-RTT 包，缺 session ticket 持久化与 0-RTT 密钥派生闭环 |
| transport parameter 发送强于接收 | **准确但需补充**：接收侧参数覆盖面确实小于发送侧 |
| Stateless Reset 接收侧闭环不明显 | **部分准确但表述偏轻**。实测 `QuicConnectionIdManager.isStatelessReset(...)` 已声明但**被调用点为 0**，即入站识别完全未接入主收包路径 |
| Key Update 仅具备"辅助函数" | **表述偏轻且方向错误**。当前 `handleKeyUpdate` 实际处理的是 **TLS 1.3 KeyUpdate 消息**（RFC 8446 §4.6.3），而 RFC 9001 §6.1 明确禁止在 QUIC 上使用该消息且要求以 0x010a 关闭连接。QUIC 自身基于 Key Phase bit 的 packet-level key update 状态机**未实现** |
| Recovery 偏向单实现内部可运行 | **准确** |
| 互操作/负向测试不足 | **准确** |

**原稿缺漏项**：

- **客户端无 Retry 接收路径**（`QuicAsyncClientChannel.java` 在整个文件内无任何 Retry / TYPE_RETRY / integrity tag 校验相关代码）。结合修订前的"随机 tag"实现，这意味着服务端→客户端 Retry 在端到端层面**根本无法工作**。原稿未指出此项，严重低估了 Retry 缺口。

## 一、本次已修复项

### 1. Retry Integrity Tag → 实现 RFC 9001 §5.8 / RFC 9369 §3.3.3

**修复前行为**（`QuicAsyncServerChannel.java` 原 755-759）：

```java
// Retry Integrity Tag (16 bytes - simplified pseudo-tag).
// In a full implementation, this would be an AEAD computation.
byte[] integrityTag = new byte[16];
new SecureRandom().nextBytes(integrityTag);
```

即用 `SecureRandom` 随机填充 16 字节当 tag。任何严格实现（Firefox、Chrome、quic-go、ngtcp2、picoquic 等）都会**静默丢弃** Retry，Retry 链路在真实互操作中无效。

**本次修复**：

1. 在 [neta-core/src/main/java/net/hasor/neta/channel/transport/quic/QuicCrypto.java](../../../neta-core/src/main/java/net/hasor/neta/channel/transport/quic/QuicCrypto.java) 新增：
   - `computeRetryIntegrityTag(odcid, retryPacketWithoutTag, version)`
   - `verifyRetryIntegrityTag(odcid, retryBytes, version)`
   - `buildRetryPseudoPacket(odcid, retryPacketWithoutTag)`
   - 版本相关常量 `RETRY_KEY_V1`、`RETRY_NONCE_V1`、`RETRY_KEY_V2`、`RETRY_NONCE_V2`（值与 RFC 9001 §5.8 / RFC 9369 §3.3.3 一致）
2. 在 `QuicAsyncServerChannel.sendRetryPacket` 中改为调用 `QuicCrypto.computeRetryIntegrityTag(...)`。
3. 新增测试 `QuicCryptoRetryTagTest.java`，覆盖：
   - RFC 9001 Appendix A.4 官方测试向量（tag = `04a265ba2eff4d829058fb3f0f2496ba`）
   - tag 篡改被拒绝
   - ODCID 不一致被拒绝
   - v1 与 v2 key/nonce 产出不同 tag
   - round-trip 校验
4. 相关误导注释已清理。

**验证**：`./mvnw -pl neta-core -Dtest='Quic*' test` → 115 tests pass, 0 failures。

## 二、本次未修复但已确认的主要缺口（附源码行号）

### 1. 客户端完全缺失 Retry 接收路径 — **新发现**

- 证据：`QuicAsyncClientChannel.java` 全文搜索 `Retry|TYPE_RETRY|retry_source_connection_id|verifyRetryIntegrityTag`，除 Version Negotiation 说明中出现一次 "retry loop" 字样外，**无任何 Retry 处理代码**。
- 影响：即使服务端生成合法 Retry，neta 客户端既不会 (a) 验证 tag，也不会 (b) 用 Retry 携带的新 SCID 作为后续 Initial 的 DCID 重新派生 Initial keys，也不会 (c) 在后续 Initial 中携带 `retry_source_connection_id` transport parameter 供服务端校验 → 端到端 Retry 根本不工作。
- 收口需要：客户端收包派发处增加 Retry 分派、tag 验证、DCID 替换与 Initial 密钥重派生、transport parameter 校验。

### 2. Key Update 实现方向错误 — RFC 9001 §6.1 合规问题

- 证据：`QuicChannelAsync.handleKeyUpdate(byte[])` 处理的是 `msgType == 0x18` 的 **TLS 1.3 HandshakeType.key_update** 消息。
- RFC 9001 §6.1 明文要求："Endpoints MUST NOT send a TLS KeyUpdate message. Endpoints MUST treat the receipt of a TLS KeyUpdate message as a connection error of type 0x010a"。
- 而 QUIC 真正的密钥更新机制是 short header 的 **Key Phase bit** 切换（RFC 9001 §6），未在当前代码中实现为 packet-level 状态机。
- 收口需要：
  1. 将收到 TLS KeyUpdate 消息改为以 `KEY_UPDATE_ERROR (0x0e)` 或 crypto error `0x010a` 关闭连接（目前代码却会正常轮换密钥）。
  2. 实现基于 Key Phase bit 的读密钥派生、新旧密钥共存窗口、旧密钥弃用 PTO、本端主动发起 key phase 切换等逻辑。
  3. `rotateReadKeys/rotateWriteKeys` 当前存在但需要与 Key Phase bit 状态机而非 TLS 消息联动。

### 3. Stateless Reset 入站识别未接入收包路径

- 证据：全仓 grep `isStatelessReset` 仅命中 1 处，即定义本身（`QuicConnectionIdManager.java:221`）。**调用点为 0**。
- 服务端出站 Stateless Reset 的构造与发送是完整的（`QuicAsyncServerChannel.java` 约 529 行，使用 HMAC-SHA256(TOKEN_KEY, dcid)[0..16]）。
- 入站路径缺口：当 short header 包解密失败且尾部 16 字节与任一本端已知 reset token 匹配时，连接需进入 `draining` 状态并立即通知上层（RFC 9000 §10.3.1）。此闭环在当前实现中未见。
- 收口需要：在 `QuicChannelAsync` 短包接收失败路径追加一次 reset token 匹配检查；连接管理处新增一张"本端期望的对端 reset token → connection" 映射（当前 `QuicConnectionIdManager` 存的是本端生成的 reset token，仅供出站）。

### 4. Transport Parameters 接收侧覆盖不全

- 发送侧（`QuicTlsEngine.java` 约 1576-1592 行）包含：`original_destination_connection_id`、`initial_source_connection_id`、`max_idle_timeout`、`active_connection_id_limit`（硬编码 8）、以及若干 stream/flow 参数。
- 接收侧（`buildInitConfigData`，参见 `QuicAsyncChannelHandshake.java`）实际消费的子集小于发送侧。未按 RFC 9000 §7.4 / §18.2 落地的关键约束：
  - 客户端必须校验服务端 `original_destination_connection_id` 等于客户端首个 Initial 的 DCID；若服务端曾发 Retry，则必须额外收到并匹配 `retry_source_connection_id` 和 `initial_source_connection_id`。不匹配时以 `TRANSPORT_PARAMETER_ERROR (0x08)` 关闭（RFC 9000 §7.3）。
  - 客户端/服务端必须将收到的 `initial_source_connection_id` 与对端首个 Initial 的 SCID 比较。
  - `active_connection_id_limit` 收到后需实际限制本端可发给对端的 `NEW_CONNECTION_ID` 数量（当前为硬编码 8）。
  - `disable_active_migration` 收到后需禁用迁移流程。
  - `ack_delay_exponent`、`max_ack_delay` 需参与 ACK Delay 解码和 PTO 计算。
- 收口需要：逐项接收 → 语义落地 → 非法值/冲突值映射到对应 error code。

### 5. 0-RTT 仅覆盖缓冲/去重语义

- 当前状态（`QuicAsyncChannelHandshake.java`）：`bufferedRtt0Data`、`seenRtt0PacketNumbers` 字段已存在，握手前 0-RTT 包入队、握手后 drain，重复 PN 去重。
- 缺口：
  - 无 session ticket 持久化与恢复（`handlePostHandshakeCrypto` 中对 `NewSessionTicket (0x04)` 仅打印日志）。
  - 无 0-RTT key 派生路径（`client_early_traffic_secret` 未接入 `QuicCrypto.derivePacketKeys`）。
  - 无 0-RTT AEAD 解密闭环。
- 结论：0-RTT 是骨架，不是可互操作的 resumption。

### 6. Recovery / 拥塞控制 — RFC 9002 细节仍待收口

- ACK tracker、丢包检测、PTO、NewReno 具备基础。
- 缺口：
  - 三套 packet number space（Initial/Handshake/Application）是否各自独立维护 `largest_acked_packet`、`loss_time`、`time_of_last_ack_eliciting_packet` 需要逐点校验（建议用单元测试固化）。
  - 握手期 PTO、持续拥塞 (persistent congestion)、ECN-CE 反馈路径的边界场景测试不足。

### 7. 互操作测试 / 负向测试仍缺

- 现有 18 个测试主要是自包实现间的黑盒/白盒联测，未与 picoquic、quiche、ngtcp2、msquic 等参考实现做互通。
- 负向测试（malformed frame、非法 transport parameter、非法 Key Phase、竞争迁移、reset 与正常包同 datagram 等）覆盖不足。

## 三、HTTP/3 层影响评估（neta-codec-http/h3）

本次对 `neta-codec-http/src/main/java/net/hasor/neta/codec/http/h3/` 的 21 个文件做了抽样评估，未对其本身做修改。

- HTTP/3 依赖 QUIC 的 stream、FIN、连接级流控与 DATAGRAM，这些能力在 QUIC 层当前状态下可用。
- Retry 修复属于 QUIC 握手层，HTTP/3 无需感知，因此本次 H3 不需联动。
- 未来若开启 HTTP/3 公网互通，仍受到上述第 1-4 项未完成的约束——尤其是 Retry 客户端接收缺失会影响任何要求地址校验的 HTTP/3 服务器的互通。
- 本文件**不列出 H3 代码改动**；相关优化在 QUIC transport 缺口收口后另行规划。

## 四、建议实施顺序（修订版）

### P0 — 与严格 QUIC 对端互操作的最小面

1. **[已完成] Retry Integrity Tag**（本次已修复并有 RFC 9001 A.4 测试向量覆盖）。
2. **客户端 Retry 接收路径**（新增；条目 1）。此项是让 Retry 在端到端层面真正起作用的前提。
3. **Key Update 合规性**：至少先补齐 "收到 TLS KeyUpdate 即关闭连接" 的 §6.1 合规行为（条目 2 一半），避免当前作为严重协议偏差存在。

### P1 — transport 基础正确性

4. 入站 Stateless Reset 识别闭环（条目 3）。
5. Transport parameter 接收侧：`original_destination_connection_id`、`initial_source_connection_id`、`retry_source_connection_id` 的强制一致性校验；`active_connection_id_limit`、`ack_delay_exponent`、`max_ack_delay`、`disable_active_migration` 的语义落地（条目 4）。
6. 基于 Key Phase bit 的 packet-level key update 状态机（条目 2 另一半）。

### P2 — 恢复 / 0-RTT / 测试收口

7. Recovery 三 PN 空间独立维护（条目 6）。
8. 0-RTT session ticket 持久化与 early data key 派生（条目 5）。
9. 互操作矩阵与负向测试（条目 7）。

## 五、当前结论（修订）

截至 2026-04-17：

- QUIC transport **主流程可跑通**，crypto、版本识别、包号空间、Long/Short header 解析、stream 重组、流控、CID 生命周期、基础 ACK/recovery 都已落地。
- **Retry Integrity Tag 已修复并有官方测试向量验证**，意味着服务端侧 Retry 不再是"伪实现"。
- 但 **Retry 的客户端接收路径、Key Update 的 §6.1 合规与 Key Phase 状态机、Stateless Reset 入站闭环、transport parameter 接收语义** 这四项是下一阶段 P0/P1 必须推进的收口项。
- 不建议宣称"完整支持 RFC 9000/9001/9002"；准确表述应是 **"具备 RFC 级 transport 主体并在 Retry 完整性上合规，但若干互操作关键点仍在收口中"**。
# QUIC RFC 对齐缺口复核

更新时间：2026-04-15

## 背景

当前 `neta-core` 在 `net/hasor/neta/channel/transport/quic` 下已经不是一个占位实现，而是一套相当完整的 QUIC transport 骨架，已经覆盖了：

- Long Header / Short Header 包解析与构造
- QUIC v1 / v2 版本常量与基础映射
- Initial / Handshake / 1-RTT 握手主路径
- ACK / 丢包检测 / PTO / NewReno 拥塞控制
- stream 重组、流控、CID 生命周期、路径校验
- `HANDSHAKE_DONE`、`NEW_CONNECTION_ID`、`RETIRE_CONNECTION_ID`
- 基础 DATAGRAM 子通道能力

从“能跑通一条 QUIC 连接”的角度看，当前实现已经明显超过早期实验态。

但如果从 RFC 9000 / 9001 / 9002 的视角看，当前实现仍不能直接视为“完整 QUIC RFC 实现”。更准确的定位应是：

- 已经具备可工作的 QUIC transport 主体
- 已经覆盖大量核心 happy-path
- 但在互操作性、安全性、参数校验和若干关键细节上仍存在明确缺口

## 当前结论

截至 2026-04-15，当前实现可以视为“具备较强基础能力、但尚未 RFC 收口完成”的 QUIC transport，实现完成度大致可以概括为：

1. RFC 9000 transport 的主连接生命周期已经具备较高覆盖度。
2. RFC 9001 TLS 绑定已经具备握手主路径，但 0-RTT、Retry、key update 等关键细节仍有简化。
3. RFC 9002 recovery / congestion control 已有可工作的基础实现，但仍偏向单实现内部可运行，而不是严格的 RFC 级细化和互操作验证。
4. RFC 9221 DATAGRAM 已经有基本收发能力，但仍属于“基础可用”，不是完整生态级收口。

因此，当前更合理的对外口径应是：

- QUIC transport 已有较强基础能力
- 但尚不能宣称 RFC 9000 / 9001 / 9002 已完整支持

## 当前已实现且相对扎实的部分

### 1. 基础包格式、版本与握手主流程已经具备

当前代码已明确实现：

- `QuicPacket` 的 long header / short header 解析与构造
- `QuicVersion` 对 QUIC v1 / v2 的版本号、salt、包类型映射
- client / server 两侧的 Initial、Handshake、1-RTT 主处理路径
- `HANDSHAKE_DONE` 的发送与接收处理
- Version Negotiation 包的构造、解析与客户端重试逻辑

这说明当前不是“只有 API 形状”，而是已经具备真实 transport 交换主路径。

### 2. ACK、丢包检测、PTO、拥塞控制已有完整基础骨架

当前代码已具备：

- ACK range 跟踪与 ACK frame 生成
- 包阈值 / 时间阈值丢包检测
- RTT 估算与 PTO 定时器
- NewReno 风格拥塞控制
- ECN-CE 触发的拥塞响应入口

并且对应单元测试已经覆盖了大量基础公式与状态迁移，这一部分是当前实现里相对成熟的区域。

### 3. Stream、流控、CID、迁移、路径校验已有主能力

当前已具备：

- stream 状态跟踪与乱序重组
- connection-level / stream-level 流控基础
- `NEW_CONNECTION_ID` / `RETIRE_CONNECTION_ID` 生命周期处理
- 路径挑战与路径响应
- 基础连接迁移处理
- anti-amplification 相关计数与超时回调

这意味着 RFC 9000 中“连接跑起来以后”的很多关键 transport 结构已经存在。

### 4. DATAGRAM 与部分后握手控制能力已接入

当前已实现：

- `max_datagram_frame_size` transport parameter 的发送与读取
- DATAGRAM frame 收发子通道
- `NEW_TOKEN` frame 的接收路径
- post-handshake CRYPTO 的处理入口

因此 RFC 9221 的基础形态已经不是空白。

## 当前已确认存在的缺口

### 1. Retry 仍不是 RFC 级互操作实现

这是当前最明确、也最关键的缺口之一。

当前服务端已经具备 Retry 路径，但源码注释已经明确说明：

- Retry Integrity Tag 仍然是简化实现
- 不是 RFC 9000 / RFC 9001 要求的标准计算方式
- 严格客户端可能直接丢弃该 Retry 包

这意味着当前 Retry 更接近“内部功能性分支存在”，而不是“可与严格 QUIC 客户端稳定互通”。

这一项如果不修正，会直接影响真实互操作能力。

### 2. 0-RTT 只实现了缓冲与排水语义，尚未完成真正的 resumption 闭环

当前 0-RTT 相关代码和测试已经不少，已经验证了这些行为：

- 无活跃握手时静默丢弃
- TLS 握手进行中可缓冲 0-RTT 包
- 超过 64 个缓冲上限后静默丢弃
- 握手完成后可 drain 缓冲数据
- 相同包号可做基础 anti-replay 去重

但这不等于 RFC 9001 意义上的完整 0-RTT。

从当前源码和注释看，仍缺少至少这些关键点：

- session ticket 持久化与恢复
- 与 resumption 绑定的早期数据接受策略
- 真正的 0-RTT key 派生与解密闭环
- `NEW_TOKEN` / ticket / resumption 状态的持久化落点
- 更严格的 anti-replay 与跨连接恢复语义

因此当前 0-RTT 更准确的结论是：

- 已实现了“握手期缓冲早期包”的框架
- 但还不是完整可互操作的 0-RTT resumption 实现

### 3. Transport Parameter 的发送明显强于接收与校验

当前 `QuicTlsEngine` 编码 transport parameters 时已经带上了：

- `original_destination_connection_id`
- `initial_source_connection_id`
- `max_idle_timeout`
- `initial_max_data`
- `initial_max_stream_data_*`
- `initial_max_streams_*`
- `active_connection_id_limit`
- `max_datagram_frame_size`

但 `buildInitConfigData(...)` 当前真正消费的对端参数只覆盖了较小子集，主要还是：

- `initial_max_data`
- `initial_max_stream_data_*`
- `initial_max_streams_*`
- `max_datagram_frame_size`

大量 RFC 9000 关键参数目前没有形成完整的接收校验和语义约束，例如：

- `original_destination_connection_id`
- `retry_source_connection_id`
- `active_connection_id_limit`
- `max_idle_timeout`
- `ack_delay_exponent`
- `max_ack_delay`
- `disable_active_migration`
- `preferred_address`

这带来的问题不是“字段少一点”，而是：

- 某些必须校验的一致性条件没有真正落地
- 某些迁移 / CID / ACK 时序相关语义没有收口
- 参数非法值、缺失值、冲突值时的错误路径还不够严格

### 4. Stateless Reset 目前主要体现为发送能力，接收侧闭环不明显

当前服务端已经具备：

- 对未知 DCID 发送 Stateless Reset
- Stateless Reset Token 的生成能力

CID 管理器里也已经具备：

- reset token 记录
- `isStatelessReset(...)` 匹配辅助方法

但从当前主流程代码看，没有看到一个明确、完整的“入站短包被识别为 Stateless Reset 并触发连接终止”的闭环路径。

这意味着当前更像是：

- 具备 Stateless Reset 的部分构件
- 但接收识别与连接级反应仍不够明确或不够完整

如果这一点不补齐，和真实对端交互时，reset 行为可能只实现了一半。

### 5. Key Update 具备辅助函数，但 RFC 9001 的 packet-level key phase 闭环仍显不足

当前代码里已经存在：

- `rotateReadKeys()`
- `rotateWriteKeys()`
- post-handshake CRYPTO 的 KeyUpdate 处理入口

但从当前主代码阅读结果看，key update 仍更像“有轮换工具与局部触发路径”，而不是 RFC 9001 那种完整的 packet-level key phase 状态机。

尤其当前没有看到足够明确的这些要素：

- 基于 short header Key Phase bit 的完整切换逻辑
- 新旧 key 共存窗口与切换判定
- 对乱序包、旧 key 包、更新竞争场景的处理
- 专门的 key update RFC 测试矩阵

因此当前这部分不能高估为“已经完整支持 QUIC key update”。

### 6. Recovery 仍偏向单实现内部可运行，距离 RFC 9002 细化收口还有距离

当前 recovery 基础已经不错，但仍有几个明显收口点：

- 代码主体更像围绕 established 1-RTT 路径进行统一跟踪
- 没有看到非常明确的多 packet number space 级别 recovery 闭环
- 持续拥塞、探测包、握手期 recovery、边界 ACK 时序等场景的验证还不够系统

这并不表示 recovery 不可用，而是表示：

- 当前 recovery 更接近“工程可运行版本”
- 还不是“经过 RFC 9002 全面边界校验的实现”

### 7. 互操作测试和负向测试仍然明显不足

当前测试已经覆盖了不少内部行为，尤其是：

- varint
- ACK / congestion / flow control
- 0-RTT 缓冲行为
- Retry / Version Negotiation 的部分黑盒场景

但这些测试仍然主要是：

- 自己实现和自己实现之间的验证
- 行为路径验证
- 单元公式验证

尚不足以证明：

- 与主流 QUIC 实现可稳定互通
- 对 malformed transport parameter、错误 key phase、非法 Retry、异常迁移、reset 竞争等场景有完整鲁棒性

## 建议实施顺序

### 第一优先级：先把互操作性最敏感的基础点补齐

建议优先完成：

1. 修正 Retry Integrity Tag，做成标准 RFC 算法
2. 明确 token / `NEW_TOKEN` / session ticket 的存储与使用路径
3. 把 Stateless Reset 的入站识别闭环补齐

原因：这三项直接决定是否能和严格 QUIC 对端真正对上。

### 第二优先级：补齐 0-RTT 与 transport parameter 语义

建议随后完成：

1. 完整 0-RTT resumption 闭环
2. transport parameter 的完整读取、约束校验与错误映射
3. `active_connection_id_limit`、迁移开关、ACK 参数等真正落到连接行为

原因：这一步决定当前实现是不是“只跑通主流程”，还是“真正按 QUIC transport 规则运行”。

### 第三优先级：补齐 key update 与 recovery 细化

建议继续完成：

1. 基于 Key Phase bit 的完整 key update 状态机
2. recovery 在不同 packet number space 上的细化
3. 更完整的持续拥塞、探测、握手期丢包等边界测试

原因：这一步决定实现是否能稳定应对长连接和复杂网络环境。

### 第四优先级：扩展互操作与负向测试矩阵

建议最后集中补齐：

1. 与真实 QUIC 客户端 / 服务端的互操作测试
2. transport parameter 异常值与缺失值测试
3. Retry / Version Negotiation / Stateless Reset / migration / key update 的专项负测
4. DATAGRAM、CID 轮换、路径迁移的组合测试

原因：没有这一层，当前完成度只能停留在“代码自证”，不能算真正的 RFC 级完成。

## 当前结论

截至 2026-04-15 的代码复核结论是：

- 当前 QUIC 实现已经具备较强的 transport 主体能力，不应再被视为空白或仅实验性质。
- 但它仍不能直接等同于 RFC 9000 / 9001 / 9002 的完整实现。
- 当前最关键的待补项不是再堆更多 frame type，而是把 Retry、0-RTT、transport parameter 校验、stateless reset、key update 和 recovery 细节真正收口。
- 在这些关键项完成之前，更准确的表述应是“QUIC transport 基础能力已基本成形，但 RFC 收口仍在进行中”。