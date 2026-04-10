# QUIC 测试计划 — Batch 3

> **目标**：在已有 44 个通过测试的基础上，系统补齐**纯单元测试层**（VarInt / AckTracker / StreamReassembler）  
> 并完善**连接终止**（§9.1 NO_ERROR close）与 **PING** 剩余场景（§12 timeout）。  
> 图例：`[x]` 已完成 / `[ ]` 待实现 / `[-]` 本轮暂缓

---

## 背景与优先级原则

| 依据                                         | 结论                                        |
|--------------------------------------------|-------------------------------------------|
| Batch 1–2 已覆盖握手、流收发、流错误、PING RTT 基础        | 基础集成路径可靠，可向上层单元测试推进                       |
| Batch 2+ 已覆盖 0-RTT 早期数据（全部 4 个可测项）         | 0-RTT 路径已封闭，QuicMonitor 为当前状态断言提供支支       |
| §2.3 VarInt、§3.3 乱序重组、§5.1 ACK 生成均为全 `[ ]` | 优先补纯单元测试，回报最高且无网络依赖                       |
| §9.1 CONNECTION_CLOSE(NO_ERROR) 仍为 `[ ]`   | `close()` 路径与已有 `closeWithError` 路径对称，成本低 |
| §12 PING timeout 路径未覆盖                     | 已有正常 PING，仅需补失败分支                         |
| 循序渐进原则                                     | 先孤立单元 → 再轻量集成，单轮总量控制在 20 个测试以内            |

---

## Group A — 纯单元测试（无网络）

### A1 · `QuicVarIntTest`（新建）

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/QuicVarIntTest.java`
- **对应跟踪**: §2.3 可变长整数（全 `[ ]`）
- **RFC**: RFC 9000 §16

| 编号   | 测试方法                       | 验证要点                                                 | 状态    |
|------|----------------------------|------------------------------------------------------|-------|
| A1-1 | `testEncode1Byte`          | 值 0、1、63 编码为 1 字节；首字节高 2 位 = 00                      | `[x]` |
| A1-2 | `testEncode2Byte`          | 值 64、100、16383 编码为 2 字节；首字节高 2 位 = 01                | `[x]` |
| A1-3 | `testEncode4Byte`          | 值 16384、500000、1073741823 编码为 4 字节；首字节高 2 位 = 10     | `[x]` |
| A1-4 | `testEncode8Byte`          | 值 1073741824 编码为 8 字节；首字节高 2 位 = 11                  | `[x]` |
| A1-5 | `testBoundaryValues`       | 边界值 0、63、64、16383、16384 编解码正确                        | `[x]` |
| A1-6 | `testRoundTrip`            | `decode(encode(v))` 对所有范围返回原始值，且 consumed 字节数与编码长度一致 | `[x]` |
| A1-7 | `testEncodedLength`        | `encodedLength(v)` 与 `encode(v).length` 结果一致         | `[x]` |
| A1-8 | `testEncodeNegativeThrows` | `encode(-1)` 抛出 `IllegalArgumentException`           | `[x]` |

**实现说明**：

- `QuicVarInt` 所有方法均为 `public static`，直接调用即可。
- 使用 JUnit 4 (`@Test` + `assert` 断言），与现有测试风格一致。
- 无需 mock，无网络依赖，执行时间应 < 10ms。

---

### A2 · `QuicAckTrackerTest`（新建）

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/QuicAckTrackerTest.java`
- **对应跟踪**: §5.1 ACK 生成（全 `[ ]`）
- **RFC**: RFC 9000 §13.2, §19.3
- **注意**: `QuicAckTracker` 是包私有类（`class`），测试与实现同包可直接访问。

| 编号   | 测试方法                              | 验证要点                                                               | 状态    |
|------|-----------------------------------|--------------------------------------------------------------------|-------|
| A2-1 | `testShouldNotAckBeforeThreshold` | 收到 1 个 ack-eliciting 包后，`shouldSendAck()` = false；收到 0 个包时也为 false | `[x]` |
| A2-2 | `testAckAfterTwoElicitingPackets` | 连续收到 2 个 ack-eliciting 包（threshold=2），`shouldSendAck()` = true     | `[x]` |
| A2-3 | `testImmediateAckOnOutOfOrder`    | 收到 pn=0、pn=2（跳过 1），`shouldSendAck()` = true（gap 触发）                | `[x]` |
| A2-4 | `testAckRangesContiguous`         | 收到 pn=0,1,2,3；`generateAckFrame()` 解析后仅含 1 个 range：[3,0]           | `[x]` |
| A2-5 | `testAckRangesWithGap`            | 收到 pn=0,1,3,4（缺 2）；`generateAckFrame()` 解析后含 2 个 range             | `[x]` |
| A2-6 | `testGenerateAckResetsCounter`    | `generateAckFrame()` 调用后 `getPendingAckEliciting()` = 0            | `[x]` |

**实现说明**：

- 直接 `new QuicAckTracker()` 实例化（包私有，测试在相同包）。
- `A2-4` 和 `A2-5` 使用 `QuicAckTracker.parseAckRanges()` 来验证生成帧的正确性（static 方法）。
- 执行时间应 < 50ms（无网络）。

---

### A3 · `QuicStreamReassemblerTest`（新建）

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/QuicStreamReassemblerTest.java`
- **对应跟踪**: §3.3 乱序流重组（全 `[ ]`）
- **RFC**: RFC 9000 §2.2
- **注意**: `QuicStreamReassembler` 是包私有类，测试与实现同包可直接访问。

| 编号   | 测试方法                           | 验证要点                                                        | 状态    |
|------|--------------------------------|-------------------------------------------------------------|-------|
| A3-1 | `testInOrderFragments`         | 按序添加 3 个片段后，`readContiguous()` 一次性返回完整数据                    | `[x]` |
| A3-2 | `testOutOfOrderReassembly`     | 先添加 offset=4 片段，再添加 offset=0 片段；`readContiguous()` 返回正确顺序数据 | `[x]` |
| A3-3 | `testDuplicateFragmentIgnored` | 相同 offset 的片段添加两次；`readContiguous()` 数据不重复                  | `[x]` |
| A3-4 | `testFinFlagSignalsStreamEnd`  | 添加带 `fin=true` 的最终片段后，`isFinReceived()` = true              | `[x]` |
| A3-5 | `testBufferOverflowRejects`    | 设置 maxBufferSize=16；添加超出限制的片段，`addFragment()` 返回 false      | `[x]` |

**实现说明**：

- 直接 `new QuicStreamReassembler()` 实例化。
- `isFinReceived()` 若不存在则通过 `finalOffset >= 0` 间接验证，或根据实现调整。
- 执行时间应 < 20ms。

---

## Group B — 连接终止集成（§9.1 补全）

### B1 · `QuicSimplyTest` 追加

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/QuicSimplyTest.java`（追加方法）
- **对应跟踪**: §9.1 正常关闭（`close()` NO_ERROR 路径）

| 编号   | 测试方法                          | 验证要点                                                                               | 状态    |
|------|-------------------------------|------------------------------------------------------------------------------------|-------|
| B1-1 | `testCloseWithNoError`        | 调用 `QuicChannel.close()` 后 Future 正常完成（不含错误码）；底层发送 CONNECTION_CLOSE(NO_ERROR=0x00) | `[x]` |
| B1-2 | `testPostCloseOperationFails` | 连接关闭后，新建流或发送数据应以 `SoCloseException` 失败（关闭后帧静默丢弃）                                   | `[x]` |

**实现说明**：

- `testConnectionGracefulClose` 已验证 `closeGracefully()`，本组测试 `close()`（立即关闭，NO_ERROR）。
- `testPostCloseOperationFails` 可通过对已关闭连接调用 `newStream()` 来触发，捕获预期异常。

---

### B2 · `rfc/QuicRFCTerminationTest`（新建）

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/rfc/QuicRFCTerminationTest.java`
- **对应跟踪**: §9.1 CONNECTION_CLOSE 帧格式（RFC 合规层面）

| 编号   | 测试方法                                | 验证要点                                                                                      | 状态    |
|------|-------------------------------------|-------------------------------------------------------------------------------------------|-------|
| B2-1 | `testConnectionCloseFrameType`      | 调用 `closeWithError(INTERNAL_ERROR, "test", ...)` 后，对端收到的包含 type=0x1c 的 CONNECTION_CLOSE 帧 | `[x]` |
| B2-2 | `testServerReceivesConnectionClose` | 客户端发送 CONNECTION_CLOSE，服务端连接对应关闭（两端最终均关闭）                                                 | `[x]` |

**实现说明**：

- `B2-1` 可通过 `QuicSimplyClient` 低级客户端捕获原始包字节来检验帧类型字节。
- `B2-2` 使用高层 API：在服务端注册 close 监听，验证服务端 channel 也进入关闭状态。

---

## Group C — PING 剩余场景（§12 补全）

### C1 · `QuicSimplyTest` 追加

- **文件**: `neta-core/src/test/java/net/hasor/neta/channel/quic/QuicSimplyTest.java`（追加方法）
- **对应跟踪**: §12.1 PING RTT 剩余 `[ ]` 条目

| 编号   | 测试方法                             | 验证要点                                                                           | 状态    |
|------|----------------------------------|--------------------------------------------------------------------------------|-------|
| C1-1 | `testPingTimeoutThrowsException` | 对已关闭连接调用 `ping(100)`，Future 以 `SoCloseException` 或 `TimeoutException` 失败（而非挂起） | `[x]` |

**实现说明**：

- 先建立连接 → 关闭连接 → 再调用 `ping(100)` → 验证 Future 立即失败而非等待超时后失败。
- 此测试同时验证"关闭后资源及时释放"，与 B1-2 的 `SoCloseException` 场景互补。

---

## 本轮测试汇总

| 组      | 测试文件                            | 测试数    | 类型     |
|--------|---------------------------------|--------|--------|
| A1     | `QuicVarIntTest`（新建）            | 8      | 纯单元    |
| A2     | `QuicAckTrackerTest`（新建）        | 6      | 纯单元    |
| A3     | `QuicStreamReassemblerTest`（新建） | 5      | 纯单元    |
| B1     | `QuicSimplyTest`（追加）            | 2      | 集成     |
| B2     | `QuicRFCTerminationTest`（新建）    | 2      | RFC 集成 |
| C1     | `QuicSimplyTest`（追加）            | 1      | 集成     |
| **合计** | —                               | **24** | —      |

预计完成后总测试数：**44（现有） + 24（新增） = 68 个**

---

## 执行顺序建议

```
1. A1 QuicVarIntTest        ← 无依赖，最简单，先验证基础编解码
2. A2 QuicAckTrackerTest    ← 依赖 QuicVarInt（parseAckRanges 内部用到），先完成 A1
3. A3 QuicStreamReassemblerTest ← 独立，可与 A2 并行
4. B1 QuicSimplyTest 追加   ← 复用已有 server/client boilerplate
5. C1 QuicSimplyTest 追加   ← 依赖 B1（关闭后状态）
6. B2 QuicRFCTerminationTest ← 最复杂（低级字节验证），最后实现
```

---

## 对应 QUIC_TEST_TRACKING.md 更新项

完成本 Batch 后需将以下条目标记为 `[x]`：

| 章节             | 条目                                                     |
|----------------|--------------------------------------------------------|
| §2.3 可变长整数     | 1字节/2字节/4字节/8字节编码、边界值、`decode/encode` 互逆               |
| §3.3 乱序流重组     | 顺序交付、乱序缓冲与交付、重叠去重、FIN 检测、缓冲区溢出                         |
| §5.1 ACK 生成    | 2个ack-eliciting包后发ACK、gap触发即时ACK、ACK ranges 编码、发送后重置计数 |
| §9.1 正常关闭      | `close()` NO_ERROR、关闭后帧静默丢弃、对端 CONNECTION_CLOSE 正确关闭   |
| §12.1 PING RTT | PING 超时后 Future 失败（TimeoutException）                   |

---

*创建时间：2026-02-28 | Batch 3 全部 24 项已完成 | 全量 68 个 QUIC 测试通过*
