# Recovery 与容量反压覆盖边界待完善

## 背景

当前 `neta` 已经具备基于有界队列与 recovery 续跑的 pipeline 内部容量反压能力。

这里讨论的“反压”不是限流，而是下面这种容量反压语义：

- 末端 pipeline 无法继续消费更多数据。
- 下游 queue 满后，中上游继续向下写入会失败。
- 未完成的输出依赖 recovery 机制在后续可写时补跑。
- 新进数据因此无法继续写入整条 pipeline。

## 当前已确认成立的部分

### 1. pipeline 内部容量反压已经基本闭环

- `ProtoQueue` 是有界队列，`slotSize()` 用于判断剩余容量。
- `ProtoStackChain.offerMessage(...)` 在目标 queue 无法接受数据时会抛出 `ProtoFullException`。
- `ProtoStackChain` / `ProtoRecoveryState` 已经形成 pending -> active -> endRecovery 的恢复闭环。
- 路由与分区结构下，残留输出可以在队列重新可写后通过 recovery 补跑继续刷出。

### 2. 大多数协议发送路径仍然走主 pipeline

以下高层协议发送路径大多仍然走 `context.sendData(...)` 或 `channel.sendData(...)`，因此继承主链路的容量反压与 recovery 机制：

- HTTP/1.1
- HTTP/2
- WebSocket
- 常规 QUIC stream 上的业务数据

## 当前仍需进一步确认或完善的边界

### 1. transport 读侧更像 overflow-as-error，而不是平滑暂停读取

当前网络输入侧的主要路径是：

- `TcpRcvCompletionHandler.completed(...)`
- `NetChannel.notifyRcv(...)` / `notifyRcvSingle(...)`
- `SoContextService.notifyRcvChannelData(...)`

根据目前代码阅读结果：

- 如果读入数据时 pipeline 内部因 queue 满触发 `ProtoFullException`，
- 它当前更可能被包装成 `SoRcvException` 并走 `notifyRcvChannelException(...)`，
- 而不是显式进入“暂停读，等待 recovery，再继续读”的 transport 级反压流程。

这意味着：

- pipeline 内部有容量反压；
- 但 transport 读侧不一定已经形成优雅的可恢复暂停机制。

### 2. 少量协议控制路径或 bypass API 不完全受主 pipeline 反压约束

需要继续审计以下类型的路径：

- QUIC 控制帧发送
- QUIC stream 的 raw/bypass 发送接口
- 其他不走普通 `sendData(...)` / `context.sendData(...)` 的协议辅助路径

这些路径即使不构成问题，也需要明确文档边界：

- 哪些属于普通业务数据路径
- 哪些属于 transport / control frame 旁路

## 建议的下一步

1. 明确区分两层能力：
   - pipeline 内部容量反压
   - transport 读侧暂停/恢复反压

2. 对 `ProtoFullException` 在 RCV 入口上的处理补一轮专项审计：
   - TCP
   - UDP
   - QUIC stream
   - QUIC datagram

3. 确认是否需要把读侧 overflow 从“异常+关闭”升级为“暂停读取，等待 recovery 后恢复读取”。

4. 对 QUIC 的 bypass / control frame 发送路径补一份边界说明，明确它们是否受主 pipeline 容量反压控制。

5. 在文档中单独补一篇“容量反压覆盖范围”说明，明确回答：
   - 哪些协议路径已经完整继承 recovery + queue 机制
   - 哪些路径目前只是 fail-fast
   - 哪些路径属于特意绕开的 transport 控制面

## 当前结论

截至 2026-03-30 的代码阅读结论是：

- `neta` 已经具备 pipeline 内部的容量反压与 recovery 补跑闭环。
- 对主业务协议路径，这个判断大概率成立。
- 但在 transport 读侧与少量协议旁路路径上，仍需继续审计与完善，不能直接等同于“所有新增协议都已经完整继承了同级别的容量反压能力”。
