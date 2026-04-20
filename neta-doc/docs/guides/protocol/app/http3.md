---
sidebar_position: 9
title: HTTP/3
description: 说明 Neta 在 HTTP/3 上的分层模型、Http3Settings 初始化方式、duplexe 装配、与 QUIC 的关系以及集成入口。
---

## 1. 简介

Neta 在 neta-codec-http 模块中提供 HTTP/3 codec 与适配器。它建立在 QUIC stream 语义之上，把 HTTP/3 frame、QPACK、请求响应对象以及上层聚合链路拆成可以独立组合的协议层组件。

和 HTTP/2 相比，HTTP/3 的几个关键变化是：

- 它不再运行在 TCP 上，而是运行在 QUIC 之上
- 连接建立、TLS 1.3、流控和丢包恢复由 QUIC 负责，不再由 HTTP/3 自己维护
- header 压缩从 HPACK 换成了 QPACK
- 每个请求或响应最终都落在 QUIC 的 stream 语义上，而不是一条共享 TCP 字节流

当前实现可以按 4 层理解：

- QUIC stream 承载层：负责把 HTTP/3 数据放在 QUIC stream 上收发
- frame 层：负责 `Http3Frame` 和原始字节之间的编解码
- message 层：负责 `Http3Frame` 和通用 `HttpObject` 之间的语义适配
- 聚合层：负责把 `HttpObject` 片段聚合成 `FullHttpRequest` 或 `FullHttpResponse`

这套实现主要解决四类问题：

- 如何用统一的 `Http3Settings` 初始化本地 HTTP/3 编解码栈
- 如何把 HTTP/3 frame 稳定转换成通用 HTTP 请求响应对象
- 如何在业务只关心完整请求响应时继续追加 aggregator
- 如何把 HTTP/3 放到已有 QUIC 或 `NetaHttpServer` 集成链路上

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- 统一初始化模型
  - 通过 `Http3Settings.defaultLocalSettings(serverMode)` 构造本地初始化参数
  - 支持显式设置 QPACK 动态表容量、field section 上限和 blocked streams 数
  - 服务端默认打开 `enableConnectProtocol(true)`
- frame 层能力
  - `Http3FrameDuplexe` 负责 `ByteBuf <-> Http3Frame`
  - 支持 HTTP/3 基本 frame 的二进制编解码和 varint 线格式处理
- message 层能力
  - `Http3ObjectDuplexe` 负责 `Http3Frame <-> HttpObject`
  - 支持把请求响应语义映射为 `HttpRequest`、`HttpResponse`、`HttpHeaders`、`HttpContent`、`LastHttpContent`
  - 出站侧使用 QPACK 对 header block 进行编码
- 上下文与事件能力
  - `Http3Context` 可读取 readiness、远端 SETTINGS 和最大 stream ID 等状态
  - `Http3ResetEvent` 可用于把应用侧 reset 意图映射到底层 QUIC `RESET_STREAM`
  - 连接级 GOAWAY 可通过 HTTP 事件体系向上暴露
- 集成能力
  - 可以显式装配 `Http3FrameDuplexe -> Http3ObjectDuplexe -> Aggregator`
  - `neta-lab/nhttp` 已提供 `NetaHttpServer.startHttp3(...)` 集成入口

### 2.2 能力边界

当前这套 HTTP/3 组件负责：

- HTTP/3 frame 与 `HttpObject` 之间的逐层转换
- QPACK 相关本地初始化参数管理
- 请求响应语义的适配与聚合
- 将 reset、settings 和连接级状态接入 Neta 的上下文与事件模型

当前这套 HTTP/3 组件不负责：

- QUIC 握手、TLS 1.3、CID、ACK、拥塞控制、迁移和 DATAGRAM
- 直接把裸 UDP 数据报变成 HTTP/3 请求响应
- 连接级自定义多路复用消息模式
- 应用路由、控制器、重试和业务级流控策略

需要特别区分两件事：

- `Http3ObjectDuplexe` 面向的是 HTTP 请求响应语义，而不是裸 QUIC 消息。
- QUIC 的 `CHANNEL` 模式适合连接级多路复用消息；HTTP/3 本身依赖 stream 语义，因此常规 HTTP/3 业务不应该直接建立在 `QuicMessage` 模式之上。

### 2.3 当前明确限制

- 当前文档聚焦 neta-codec-http 中已经形成公开入口的 HTTP/3 codec 与集成方式，不展开 QUIC 握手内部状态机
- 当前主线场景是常规请求响应语义，不展开自定义 control stream 处理流程
- HTTP/3 settings 仅讨论协议层已暴露的公开配置，不展开所有内部状态容器的实现细节
- 如果你的目标是浏览器可访问服务，更推荐使用上层 `NetaHttpServer.startHttp3(...)`，而不是自己手工拼 QUIC 传输细节

## 3. 组件分层

### 3.1 整体分层

```text
QUIC stream bytes / VirtualChannel bytes
  -> Http3FrameDuplexe
  -> Http3Frame

Http3Frame
  -> Http3ObjectDuplexe
  -> HttpObject

HttpObject
  -> HttpRequestAggregator / HttpResponseAggregator
  -> FullHttpRequest / FullHttpResponse
```

常规业务链路可进一步压缩为：

```text
QUIC stream
  -> Http3FrameDuplexe
  -> Http3ObjectDuplexe
  -> Aggregator
  -> FullHttpRequest / FullHttpResponse
```

### 3.2 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| Http3Settings | 统一管理本地初始化参数与远端 SETTINGS 视图 | 调整 QPACK、header 上限、extended CONNECT |
| Http3FrameDuplexe | `ByteBuf <-> Http3Frame` 的双工入口 | frame 层调试、下钻观察 HTTP/3 线格式 |
| Http3ObjectDuplexe | `Http3Frame <-> HttpObject` 的双工入口 | 常规 HTTP/3 请求响应处理 |
| Http3Context | 暴露 readiness、远端 settings、最大 stream ID | 路由判定、协议状态观测 |
| Http3ResetEvent | 把应用层 reset 意图映射到 QUIC `RESET_STREAM` | 中止流、拒绝请求、异常回滚 |
| HttpRequestAggregator / HttpResponseAggregator | 把对象片段聚合为完整消息 | 业务层只处理完整请求或响应 |

### 3.3 Http3Settings 的定位

`Http3Settings` 既是“本地初始化参数对象”，也是“远端 SETTINGS 的状态视图承载对象”。

在本地初始化时，最常见的入口是：

```java
Http3Settings settings = Http3Settings.defaultLocalSettings(true);
```

当前默认值与语义是：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `QPACK_MAX_TABLE_CAPACITY` | `4096` | 本地 QPACK 动态表容量 |
| `MAX_FIELD_SECTION_SIZE` | `65536` | 本地 header field section 上限 |
| `QPACK_BLOCKED_STREAMS` | `0` | 本地允许的 blocked streams 数 |
| `ENABLE_CONNECT_PROTOCOL` | 服务端默认开启 | 服务端模式下默认打开 extended CONNECT |

常见自定义方式如下：

```java
Http3Settings settings = Http3Settings.defaultLocalSettings(true)
        .maxFieldSectionSize(128 * 1024)
        .qpackMaxTableCapacity(8192)
        .qpackBlockedStreams(16);
```

### 3.4 各层可见对象

| 层 | 输入输出对象 | 这一层主要看到什么 |
| --- | --- | --- |
| QUIC stream 承载层 | `ByteBuf` | stream 上的原始 HTTP/3 线格式字节 |
| frame 层 | `Http3Frame` | HEADERS、DATA、SETTINGS、GOAWAY 等 frame |
| message 层 | `HttpObject` + HTTP 事件 | 请求响应对象、内容片段、reset 或 goaway 事件 |
| 聚合层 | `FullHttpRequest` / `FullHttpResponse` | 完整业务请求响应 |

## 4. 使用方式

### 4.1 服务端：显式装配 HTTP/3 pipeline

服务端常规装配方式如下：

```java
Http3Settings settings = Http3Settings.defaultLocalSettings(true)
        .maxFieldSectionSize(64 * 1024);

ctx.addLast("h3-frame", new Http3FrameDuplexe(true, settings));
ctx.addLast("h3-object", new Http3ObjectDuplexe(true, settings));
ctx.addLast("h3-aggregator", new HttpRequestAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- `serverMode=true` 时，本地 settings 会按服务端语义初始化
- `Http3FrameDuplexe` 和 `Http3ObjectDuplexe` 应当成对装配
- 业务层如果只想处理完整请求，继续在后面追加 `HttpRequestAggregator`

### 4.2 客户端：显式装配 HTTP/3 pipeline

客户端常规装配方式如下：

```java
Http3Settings settings = Http3Settings.defaultLocalSettings(false);

ctx.addLast("h3-frame", new Http3FrameDuplexe(false, settings));
ctx.addLast("h3-object", new Http3ObjectDuplexe(false, settings));
ctx.addLast("h3-aggregator", new HttpResponseAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- 客户端与服务端共享相同的公开入口，只是 `serverMode` 不同
- 客户端 settings 默认不会自动打开 extended CONNECT
- 如果业务层只关心完整响应，优先追加 `HttpResponseAggregator`

### 4.3 读取 HTTP/3 协议上下文

如果需要读取远端 settings 或当前协议 readiness，可以通过 `Http3Context` 获取只读状态视图：

```java
Http3Context h3 = context.context(Http3Context.class);
if (h3 != null && h3.isReady()) {
    long maxFieldSectionSize = h3.maxFieldSectionSize();
    long qpackMaxTableCapacity = h3.qpackMaxTableCapacity();
}
```

这适合以下场景：

- 路由或协议切换前确认远端 SETTINGS 是否已经到达
- 诊断对端 QPACK 或 header 上限参数
- 观测当前连接上已追踪到的最大 stream ID

### 4.4 使用 NetaHttpServer 提供 HTTP/3 服务

如果你的目标是快速启动一个 HTTP/3 服务，更推荐直接使用上层集成：

```java
NetaHttpServer server = new NetaHttpServer();
server.http3(true);
server.startHttp3(8443);
```

这条路径会在内部按下面的思路构造 pipeline：

```text
Http3FrameDuplexe -> Http3ObjectDuplexe -> HttpRequestAggregator -> 应用处理器
```

关键点：

- `startHttp3(...)` 监听的是 UDP 端口
- 内部会基于 `Http3Settings.defaultLocalSettings(true)` 构造本地 settings
- 当前集成会把 `maxHeaderSize` 继续映射到 `maxFieldSectionSize(...)`

## 5. 与 QUIC 的关系

HTTP/3 和 QUIC 在 Neta 中的职责边界可以概括为：

- QUIC 负责连接建立、TLS 1.3、流控、ACK、丢包恢复、CID 和迁移
- HTTP/3 负责 frame、QPACK、请求响应对象和流级 reset 语义
- 业务如果只想处理 HTTP 请求响应，应当直接进入 HTTP/3 对象层，而不是自己消费连接级 `QuicMessage`

对于选型可以直接记住两条：

- 做通用 HTTP/3 服务或客户端，请优先使用 `Http3FrameDuplexe -> Http3ObjectDuplexe` 或 `NetaHttpServer.startHttp3(...)`
- 做自定义 QUIC 多路复用协议，但不需要 HTTP 语义时，再考虑 QUIC 自己的 `STREAM` 或 `CHANNEL` 模式

## 6. 选型建议

- 业务只关心完整请求响应：使用 `Http3ObjectDuplexe` 后直接追加 aggregator
- 需要观察 frame 级行为、QPACK 或底层协议细节：保留 `Http3FrameDuplexe`
- 需要快速提供可用的 HTTP/3 服务：优先使用 `NetaHttpServer.startHttp3(...)`
- 需要调优 header 上限或 QPACK：优先从 `Http3Settings` 入手，而不是直接改内部状态类