# HTTP/3 RFC 对齐缺口复核

更新时间：2026-04-15

## 背景

当前 `neta-codec-http` 已经具备一套可运行的 HTTP/3 风格 codec 与消息转换链路，包括：

- HTTP/3 frame 编解码
- HEADERS / DATA 等基础语义转换
- 本地 `Http3Settings` 参与 codec 初始化
- 本地 `Http3Settings` 自动参与首个出站 `SETTINGS` 帧发送
- 一套可自测通过的 HTTP/3 / QPACK 相关单元测试

但这并不等同于“已经实现了 RFC 9114 意义上的完整 HTTP/3”。

结合当前主代码阅读结果，现阶段更准确的定位应是：

- 已具备 HTTP/3 语义层与部分帧层能力
- 已开始对齐 `SETTINGS` / QPACK / stream-scope 这些关键概念
- 但距离“基于 QUIC 的完整 RFC HTTP/3 实现”仍有一批关键缺口需要补齐

## 当前结论

截至 2026-04-15，当前实现不能视为 RFC 9114 / RFC 9204 完整实现，主要原因如下：

1. 对外公开的 HTTP/3 使用路径仍然主要建立在 UDP 测试与 UDP 入口之上，而不是完整 QUIC transport 之上。
2. 控制流、单向流类型、关键流生命周期等 HTTP/3 连接级语义还未完全建模。
3. QPACK 目前更接近“可工作的字段段压缩能力”，还不是带 encoder stream / decoder stream / acknowledgment / blocked-stream 语义的完整 RFC 9204 实现。
4. 对 malformed message、伪首部、帧出现位置、消息完整性等 RFC 约束的校验仍然偏宽松。
5. CONNECT、server push、部分错误码与异常路径还没有按 RFC 要求真正落实。

因此，当前更合理的对外口径应是：

- 已有 HTTP/3 基础能力与演进中的协议栈
- 但仍处于“未完成 RFC 收口”的阶段

## 当前已确认存在的缺口

### 1. 传输层仍未真正收口到 QUIC

当前最核心的问题不是 frame codec，而是 transport 语义。

根据现有主代码与测试路径，公开可见的 HTTP/3 使用方式仍然主要表现为：

- `startHttp3(...)` 等高层入口仍绑定 `SoConfig.UDP()`
- `Http3UdpProtocolTest` 这类测试验证的是 UDP 路径上的行为
- 尚未把 HTTP/3 明确收口为“运行在 QUIC stream 之上的应用层协议”

这意味着当前还不能把它视为真正的 HTTP/3 transport 实现。

后续需要补齐：

- 对外 HTTP/3 入口切换到明确的 QUIC 配置与初始化路径
- 明确 `QuicChannel` / `QuicStreamChannel` 与 HTTP/3 codec 的绑定方式
- 区分 connection-level control stream、request stream、QPACK stream、datagram 等边界
- 增加真实 QUIC 场景下的 client/server 集成测试，而不只是 UDP 自环测试

### 2. 控制流与单向流类型语义仍不完整

HTTP/3 要求对单向流类型有严格约束，包括：

- control stream
- QPACK encoder stream
- QPACK decoder stream
- push stream
- unknown unidirectional stream

当前实现虽然已经识别部分 stream type / frame type，但还未真正按 RFC 把它们分层建模并实施严格约束。

当前仍需补齐：

- 每个 endpoint 的 control stream 生命周期管理
- `SETTINGS` 必须作为 control stream 首帧的校验
- 禁止重复 control stream、禁止重复 `SETTINGS` 等错误路径
- 对未知单向流类型的忽略 / 关闭策略
- 对关键控制流关闭后的连接级错误处理

### 3. QPACK 仍未达到 RFC 9204 完整实现

当前 `QpackEncoder` / `QpackDecoder` 已经具备一定压缩与解码能力，但从协议完整性上看仍有明显缺口。

目前更像：

- 支持静态表与部分字段段表示
- 支持一定程度的动态表相关处理
- 但没有真正把 encoder stream / decoder stream 协议动作落到 HTTP/3 连接级交互中

仍需补齐的关键点：

- QPACK encoder stream（`0x02`）
- QPACK decoder stream（`0x03`）
- Insert Count / Base / Section Acknowledgement / Stream Cancellation / Insert Count Increment
- blocked streams 约束与相关错误处理
- 动态表更新与对端确认之间的完整同步逻辑
- 与 HTTP/3 unidirectional stream 生命周期的绑定

如果这一层不补齐，当前 QPACK 更适合作为“内部可工作的 header compression 方案”，而不是 RFC 9204 完整实现。

### 4. HTTP 消息合法性校验仍然偏宽松

当前消息转换路径中，一些本应视为协议错误的情况仍然被宽松处理，例如：

- 缺少必需伪首部时用默认值兜底
- 请求缺少 `:method` 时默认为 `GET`
- 请求缺少 `:path` 时默认为 `/`
- 响应缺少 `:status` 时默认为 `200`

这类行为对调试友好，但不符合 RFC 对 malformed message 的处理要求。

仍需补齐：

- 请求必须包含必需伪首部的严格校验
- 响应必须包含 `:status` 的严格校验
- 禁止伪首部与普通首部乱序、重复或非法组合
- `content-length` 与 DATA 总长度一致性的校验
- connection-specific headers / transfer-encoding 等 HTTP/3 禁止首部的拦截
- trailer / body / END 语义的完整边界校验

### 5. CONNECT 语义仍未严格对齐 RFC

虽然当前 `SETTINGS_ENABLE_CONNECT_PROTOCOL` 已经开始参与 HTTP/2 / HTTP/3 的 settings 能力表达，但具体到 HTTP/3 消息层，CONNECT 仍未完全按 RFC 处理。

当前仍需补齐：

- 普通 CONNECT 请求与扩展 CONNECT 请求的首部差异处理
- CONNECT 请求下 `:scheme`、`:path` 等伪首部的合法性约束
- `:protocol` 与扩展 CONNECT 的联动校验
- 与后续 WebSocket over HTTP/3 设计的衔接

### 6. Server Push 仍基本缺失

当前代码中虽然已有：

- `PUSH_PROMISE`
- `CANCEL_PUSH`
- `MAX_PUSH_ID`

这些 frame / 常量入口，但从完整能力上看，server push 仍未真正落地。

仍需明确：

- 是否计划正式支持 HTTP/3 server push
- 如果支持，需要补齐 push stream / push ID / 生命周期 / 取消语义
- 如果暂不支持，需要在文档与代码中明确标识 unsupported，而不是只停留在常量层

### 7. RFC 错误码与异常路径尚未真正闭环

当前 `Http3ErrorCode` 已定义大量错误码，但“有枚举值”不等于“协议路径已落实”。

仍需继续梳理：

- 哪些错误必须触发 stream error
- 哪些错误必须触发 connection error
- 关键流关闭、重复 `SETTINGS`、未知帧位置错误、QPACK stream 错误等路径是否真正映射到对应错误码
- 当前异常在 pipeline / event / channel close 上的落点是否与 RFC 预期一致

### 8. 互操作性测试仍明显不足

当前已有测试更偏向：

- 自己编码、自己解码
- 自己发帧、自己收帧
- 同一实现内部互通

这对于迭代 codec 非常有价值，但不足以证明 RFC 兼容性。

后续仍需补齐：

- 与真实 QUIC + HTTP/3 对端的互操作测试
- 与主流实现的 handshake / settings / qpack / request-response 互通测试
- 异常输入、错误帧顺序、关键流丢失等负向测试
- 对 control stream / request stream / qpack stream 的专项测试矩阵

## 建议实施顺序

### 第一优先级：先把“是不是 HTTP/3 over QUIC”收口

建议优先完成：

1. 对外 HTTP/3 入口切换到 QUIC
2. 建立 request stream / control stream / unidirectional stream 的基本模型
3. 明确 control stream 生命周期与 `SETTINGS` 规则

原因：这一步不完成，后续很多 RFC 语义都只能停留在 codec 层模拟。

### 第二优先级：补齐控制面与 QPACK

建议随后完成：

1. QPACK encoder / decoder stream
2. 动态表同步与 acknowledgment
3. 关键控制流错误路径与连接级错误映射

原因：这是 HTTP/3 真正区别于“普通 HTTP 帧语义层”的核心部分。

### 第三优先级：收紧消息合法性与异常路径

建议继续完成：

1. malformed message 严格校验
2. CONNECT 语义修正
3. RFC 错误码闭环

原因：这一步决定实现是否可以稳定面对真实对端，而不只是内部 happy-path。

### 第四优先级：决定 push 与更高层协议接入范围

建议明确：

1. server push 是否支持
2. WebSocket over HTTP/3 是否进入下一阶段
3. 是否在当前版本明确声明“不支持的 HTTP/3 组合能力”

原因：这些决定会直接影响后续 API 设计与文档口径。

## 当前结论

截至 2026-04-15 的代码复核结论是：

- 当前 HTTP/3 实现已经具备基础 frame / message / settings 演进能力。
- 但它仍不能等同于“完整的 RFC 9114 / RFC 9204 实现”。
- 真正需要继续做的工作，首先不是再补几个 frame type，而是把 transport、control stream、QPACK、malformed 校验与错误路径完整收口。
- 在这些关键项完成之前，更准确的表述应是“HTTP/3 实现进行中”，而不是“HTTP/3 已完整支持”。