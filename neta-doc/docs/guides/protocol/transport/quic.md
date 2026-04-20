---
sidebar_position: 13
title: QUIC
description: 说明 Neta 的 QUIC 传输支持范围、对象模型、运行机制、配置项以及服务端和客户端的使用方式。
---

## 简介

QUIC 是一种构建在 UDP 之上的面向连接传输协议。它把连接建立、TLS 1.3、包号空间、丢包恢复、连接迁移、多路复用流和可选 DATAGRAM 能力统一放在同一层实现。

和 TCP 相比，QUIC 有几个非常显著的差异：

- 它运行在 UDP 之上，但对应用暴露的是“已建链连接”而不是裸数据报
- 它天然支持多条并发 stream，而不是只有一条共享字节流
- 握手期就内建加密与传输参数协商，不需要像 TCP 那样再外挂一层 TLS
- 它允许连接在路径变化时继续存活，并通过 Connection ID 维持逻辑连接标识

Neta 在 neta-core 的 net.hasor.neta.channel.quic 包中提供 QUIC 传输支持，目标是把 QUIC 也纳入统一的 NetManager、NetListen、NetChannel 和 pipeline 模型，同时保留 QUIC 特有的连接、流和 DATAGRAM 语义。

整体上可以分成 5 层：

- Provider 层：QuicProvider 负责创建基于 DatagramChannel 的 QUIC 客户端和服务端底层通道
- 握手层：QuicAsyncClientChannel、QuicAsyncServerChannel、QuicAsyncChannelHandshake 与 QuicTlsEngine 负责 Initial、Handshake 和 1-RTT 建链流程
- 连接层：QuicChannelAsync 负责已建链连接上的收发、ACK、丢包恢复、拥塞控制、流控、CID 管理和路径校验
- 通道层：QuicChannel 负责对外暴露连接级 API，QuicStreamChannel 和 QuicDatagramChannel 负责暴露子通道 API
- 协议层：业务通过 ProtoInitializer 把编解码器和 handler 安装到连接、stream 或 datagram 的 pipeline 上

这套实现主要覆盖四类能力：

- 使用统一 API 建立 QUIC 服务端监听与客户端连接
- 支持握手后连接、双向流、单向流和可选 DATAGRAM 子通道
- 支持连接级流控、流数量控制、PING、PATH_CHALLENGE、连接迁移和带错误关闭
- 支持在更上层叠加 HTTP/3 之类基于 QUIC 的应用协议

## 范围与能力

- 传输入口
  - QuicProvider 作为 AsyncChannelProvider 接入 NetManager
  - 支持 bind(...) 创建 QUIC 监听器
  - 支持 connectSync(...) 和 connectAsync(...) 创建 QUIC 客户端连接
  - 支持通过 SoConfig.QUIC() 快速创建 QuicSoConfig
- 握手与安全
  - 支持 QUIC Initial、Handshake 和 1-RTT 建链阶段
  - 支持 QUIC 内部 TLS 1.3 集成，证书配置通过 QuicSoConfig.setSslConfig(...) 提供
  - 支持 QUIC V1 和 V2 的版本选择
  - 握手完成后可以通过 QuicChannel.getSslContext() 读取协商结果
- 连接模型
  - 服务端监听器运行在 UDP 端点上，但对外暴露为带握手语义的 QuicListen
  - 每条握手完成的连接都会创建一个 QuicChannel
  - 已建链连接具备自己的 ACK、流控、拥塞控制、CID 管理和路径校验状态
- 数据模型
  - 默认使用 `STREAM` 模式，双向流和单向流会映射为独立的 QuicStreamChannel
  - 也支持 `CHANNEL` 模式，把重组后的 stream 数据作为 QuicMessage 投递到连接级 pipeline
  - 支持 RFC 9221 DATAGRAM 能力，每条连接最多映射一个 QuicDatagramChannel
  - stream 保留 QUIC 的流语义，DATAGRAM 保留消息边界但不保证可靠送达
- 控制能力
  - 支持发送 MAX_DATA 和 MAX_STREAMS 提升连接与流配额
  - 支持 ping(...) 测量 RTT
  - 支持 pathChallenge(...) 和 migrate() 发起路径探测或连接迁移
  - 支持 closeGracefully() 和 closeWithError(...) 发送 CONNECTION_CLOSE
- 监听与扩展能力
  - 服务端支持通过 QuicConnectionListener 在连接建成后获得回调
  - 上层可以在 QUIC 之上叠加 HTTP/3 等协议
  - neta-lab/nhttp 已提供 startHttp3(...) 的集成入口

## RFC 支持现状

截至 2026-04-17：

- **已合规**
  - Long / Short Header 包解析、版本识别、Initial / Handshake / 1-RTT 主流程、ACK、PTO、NewReno、stream 重组、流控、CID 生命周期、路径校验
  - QUIC v1 与 v2 的 Initial salt、key label、包类型映射
  - Retry Integrity Tag 完整计算（RFC 9001 §5.8 / RFC 9369 §3.3.3），已通过 RFC 9001 Appendix A.4 官方测试向量
  - 基础 DATAGRAM（RFC 9221）收发
- **待收口（不建议用于需要与浏览器等严格 QUIC 实现互操作的生产场景）**
  - 客户端 Retry 接收路径（tag 校验、DCID 重派生、`retry_source_connection_id` 校验）
  - Key Update：当前未实现基于 Key Phase bit 的 packet-level 状态机；`handleKeyUpdate` 处理的是 TLS 1.3 KeyUpdate 消息，RFC 9001 §6.1 实际要求收到该消息后关闭连接（0x010a）
  - Stateless Reset 入站识别闭环（出站已实现）
  - Transport parameter 接收侧一致性校验（`original_destination_connection_id` / `initial_source_connection_id` / `retry_source_connection_id` 等）
  - 0-RTT resumption（仅有握手期缓冲/排水，缺 session ticket 持久化和 0-RTT 密钥派生）
  - 三 packet number space 各自独立的 recovery 细化、持续拥塞边界、ECN-CE 完整反馈
  - 与 picoquic / quiche / ngtcp2 / msquic 等参考实现的互操作测试

详细清单、行号与建议修复顺序见 `neta-doc/todo/quic-rfc-gap-audit-20260415.md`。

## 使用限制

- 对标准 QUIC 对端互通时，推荐始终启用 TLS 配置。未启用 TLS 的模式更适合内部调试、协议实验或测试场景，不应当作为默认的公网互通模式
- QUIC 建立在 UDP 之上，因此仍然受到 UDP 端口、防火墙、NAT 和 MTU 环境的影响
- DATAGRAM 只有在双方都协商出非 0 的 max_datagram_frame_size 时才能真正使用；仅本地打开开关并不等于能力已经生效
- QUIC 的业务数据推荐走 stream 或 datagram 子通道的正常 pipeline。少量控制帧和底层旁路接口属于传输控制面，不等价于普通业务消息路径
- 当前文档聚焦连接、流和 DATAGRAM 的使用模型，不展开握手内部状态机、TLS 消息布局和包保护细节
- 如果你需要面向浏览器或通用 HTTP 客户端互通，通常应该直接使用上层 HTTP/3 集成，而不是手写 QUIC 帧

## 3. 核心对象

### 3.1 整体对象分层

```text
Application
  -> NetManager
  -> bind / connect

QuicProvider
  -> QuicAsyncServerChannel / QuicAsyncClientChannel

QuicAsyncChannelHandshake
  -> QuicTlsEngine
  -> Initial / Handshake / 1-RTT

QuicChannelAsync
  -> ACK / loss detection / congestion control / flow control / CID / path

QuicChannel
  -> QuicStreamChannel*
  -> QuicDatagramChannel?
```

### 3.2 关键对象职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| QuicProvider | 创建 QUIC 客户端与服务端底层通道 | NetManager 根据 QuicSoConfig 选择 provider |
| QuicAsyncServerChannel | 服务端 UDP 入口，负责按 DCID 分发、建链、握手提升和连接路由 | 服务端 bind(...) |
| QuicAsyncClientChannel | 客户端建链入口，负责发起握手并提升为连接 | 客户端 connectSync(...) / connectAsync(...) |
| QuicAsyncChannelHandshake | 握手状态机，负责 Initial、Handshake、1-RTT 密钥和传输参数协商 | 客户端和服务端建链期间 |
| QuicTlsEngine | QUIC 内部 TLS 1.3 引擎 | 启用 TLS 的 QUIC 握手 |
| QuicChannelAsync | 已建链连接运行时，负责报文收发、ACK、丢包恢复、流控和迁移 | 所有活动连接 |
| QuicChannel | 面向业务暴露的连接级句柄 | 打开 stream、打开 datagram、发送控制帧 |
| QuicStreamChannel | 单条 QUIC 流对应的子通道 | 可靠业务流、请求响应、多路复用消息 |
| QuicDatagramChannel | 连接级 DATAGRAM 子通道 | 非可靠低延迟消息 |
| QuicSoConfig | QUIC 专用配置对象 | TLS、版本、传输参数、DATAGRAM 和监听回调 |
| QuicConnectionListener | 服务端连接建立完成后的回调接口 | 建链后自动挂接控制流或附加逻辑 |

### 3.3 QuicChannel 的定位

QuicChannel 是握手完成后暴露给业务层的连接级入口。它不是底层 UDP socket，也不直接负责解析 QUIC 报文；它更像一个“已协商完成的连接控制面门面”。

它主要承担三类职责：

- 连接级控制
  - ping(...)
  - pathChallenge(...)
  - migrate()
  - closeGracefully()
  - closeWithError(...)
- 子通道管理
  - newBidiStream()
  - newUniStream()
  - openDatagramChannel()
  - findStream(...)
- 配额调节
  - sendMaxDataSize(...)
  - upgradeBidiStreams(...)
  - upgradeUniStreams(...)

如果你的业务要处理可靠消息，通常应该继续下钻到 QuicStreamChannel。如果你的业务要处理不可靠消息，应该显式打开 QuicDatagramChannel。

### 3.4 QuicSoConfig 的定位

QuicSoConfig 是 QUIC 传输的专用配置对象，它一部分字段会在握手期通告给对端，另一部分字段只影响本地运行时策略。

它主要管理五类参数：

- TLS 与证书配置
  - sslConfig
- 连接标识和版本
  - connectionIdLength
  - quicVersion
- 连接和流的传输参数
  - tpMaxIdleTimeout
  - tpInitialFrameMaxData
  - tpInitialMaxStreamDataBidiLocal
  - tpInitialMaxStreamDataBidiRemote
  - tpInitialMaxStreamDataUni
  - tpInitialMaxStreamsBidi
  - tpInitialMaxStreamsUni
  - tpInitialDatagramFrameMaxData
- 本地运行策略
  - disableDatagram
  - streamMode
  - streamIdleTimeoutMs
- 服务端监听扩展
  - connectionListener

在使用上可以把它理解成“UDP socket 参数 + QUIC transport parameter + 少量本地策略”的组合对象。

### 3.5 QuicChannelMode 的定位

`QuicChannelMode` 用来控制“QUIC stream 以什么形式暴露给上层 pipeline”。当前有两个公开模式：

- `STREAM`
  - 每条 QUIC stream 都会暴露成独立的 `QuicStreamChannel`
  - 业务在每条 stream 上拥有独立 pipeline、独立生命周期和独立关闭语义
- `CHANNEL`
  - stream 状态保留在传输层内部
  - 重组后的 payload 作为 `QuicMessage` 投递到连接级 pipeline
  - 出站时也直接通过 `QuicChannel.sendData(QuicMessage.of(...))` 发送

这个配置只改变“stream 如何暴露给业务”，不会改变底层 QUIC 的握手、流控、拥塞控制和 DATAGRAM 能力。

## 4. 使用方式

### 4.1 创建服务端 QUIC 监听

最常见的服务端用法，是先准备一个 QuicSoConfig，再通过 NetManager.bind(...) 在 UDP 地址上启动 QUIC 监听。

```java
NetManager neta = new NetManager();

QuicSoConfig server = SoConfig.QUIC();
server.setConnectionIdLength(8);
server.setTpMaxIdleTimeout(30_000);
server.setTpInitialFrameMaxData(1024 * 1024);
server.setTpInitialMaxStreamsBidi(100);
server.setTpInitialMaxStreamsUni(100);
server.setTpInitialDatagramFrameMaxData(1200);

SslCertConfig certConfig = new SslCertConfig();
// 证书和私钥的准备方式可参考 SSL/TLS 章节
server.setSslConfig(certConfig);

server.setConnectionListener(quic -> {
    System.out.println("new quic connection: " + quic.getRemoteAddress());
});

NetListen listen = neta.bind(
        new InetSocketAddress(9443),
        ctx -> {
            // 这里安装连接级 pipeline
        },
        server);
```

关键点：

- 监听地址本质上是一个 UDP 端点
- bind(...) 创建的是 QUIC 监听器，而不是裸 UDP 监听器
- 启用 TLS 后，服务端会在握手期完成 QUIC 内部 TLS 1.3 协商
- connectionListener 会在握手完成、QuicChannel 创建完成且其 pipeline 初始化之后触发

### 4.2 创建客户端 QUIC 连接

客户端侧的入口与 TCP、UDP 保持一致，仍然是 connectSync(...) 或 connectAsync(...)。区别在于返回通道真正进入可用状态前，内部会先经历 QUIC 握手。

```java
NetManager neta = new NetManager();

QuicSoConfig client = SoConfig.QUIC();
client.setConnectionIdLength(8);
client.setTpInitialFrameMaxData(1024 * 1024);
client.setTpInitialMaxStreamsBidi(100);
client.setTpInitialDatagramFrameMaxData(1200);

SslCertConfig clientSsl = new SslCertConfig();
client.setSslConfig(clientSsl);

QuicChannel quic = (QuicChannel) neta.connectSync(
        new InetSocketAddress("127.0.0.1", 9443),
        ctx -> {
            // 这里安装连接级 pipeline
        },
        client);
```

关键点：

- connectSync(...) 返回时，握手已经完成，拿到的是可直接使用的 QuicChannel
- 如果未启用 TLS，只适合受控环境下的协议测试或内部实验
- 握手完成后可以通过 getSslContext() 查看 ALPN、SNI 和证书相关上下文

### 4.3 选择 stream 暴露模式

QUIC 的两种模式分别适合两类完全不同的业务写法。默认值是 `QuicChannelMode.STREAM`。

```java
QuicSoConfig quicCfg = SoConfig.QUIC();
quicCfg.setStreamMode(QuicChannelMode.STREAM);
```

或者：

```java
QuicSoConfig quicCfg = SoConfig.QUIC();
quicCfg.setStreamMode(QuicChannelMode.CHANNEL);
```

两种模式的差异可以直接理解成下面这张表：

| 模式 | 上层看到什么 | 出站怎么发 | 主要特点 | 更适合的场景 |
| --- | --- | --- | --- | --- |
| `STREAM` | `QuicStreamChannel` | 通过 stream channel 自己发数据 | 每条流独立、边界清晰、生命周期隔离好 | HTTP/3、RPC、请求响应协议、需要 per-stream pipeline 的场景 |
| `CHANNEL` | 连接级 `QuicMessage` | `QuicChannel.sendData(QuicMessage.of(...))` | stream 状态收敛在连接层、业务只维护一个连接级 handler | 自定义多路复用消息协议、想按 `streamId` 自己分发、希望减少子通道管理的场景 |

选型建议：

- 需要“每条流各自独立处理链”时，优先选 `STREAM`
- 需要“一个连接 handler 自己按 streamId 分发消息”时，优先选 `CHANNEL`
- 目标是 HTTP/3 这类已经依赖 stream 语义的上层协议时，优先使用 `STREAM` 或直接用更上层集成

### 4.4 打开双向流和单向流

QUIC 的常规业务数据通常走 stream。每条 stream 都会映射成独立的 QuicStreamChannel，并拥有独立 pipeline。

```java
Future<QuicStreamChannel> bidiFuture = quic.newBidiStream();
Future<QuicStreamChannel> uniFuture = quic.newUniStream();
```

关键点：

- 双向流适合请求响应、命令流或需要双向通信的业务协议
- 单向流适合日志流、推送流或只允许一端发送的场景
- QUIC 会校验对端通告的 MAX_STREAMS 配额，超过配额时不会继续分配新的流 ID
- 每条 QuicStreamChannel 都可以单独关闭、RESET 或 STOP_SENDING

### 4.5 使用连接级消息模式

如果你不想管理大量 `QuicStreamChannel`，而是希望在连接级 pipeline 里直接接收多路复用消息，可以切换到 `CHANNEL` 模式。

```java
QuicSoConfig quicCfg = SoConfig.QUIC();
quicCfg.setStreamMode(QuicChannelMode.CHANNEL);

ctx.getChannel().subscribe(data -> {
  QuicMessage message = (QuicMessage) data.getData();
  try {
    long streamId = message.streamId();
    boolean fin = message.isFin();
    ByteBuf body = message.content();
    // 根据 streamId、fin 和 body 自己做业务分发
  } finally {
    message.release();
  }
});
```

出站时：

```java
long streamId = quic.allocateBidiStreamId();
quic.sendData(QuicMessage.of(streamId, body, true));
```

关键点：

- `QuicMessage` 自带 `streamId`、`isFin()`、`isBidi()`、`isUni()` 元数据
- 收到的 `QuicMessage` 由消费方负责 `release()`
- 同一条 stream 可以连续收到多条消息，最后一条通常用 `fin=true` 标记结束
- 这种模式适合自定义多路复用协议，不适合替代需要 per-stream pipeline 的上层协议

### 4.6 使用 DATAGRAM 子通道

如果业务更关心低延迟而不是可靠性，可以使用 QUIC DATAGRAM。但前提是双方都协商出了非 0 的 max_datagram_frame_size。

```java
if (quic.isSupportDatagram()) {
    Future<QuicDatagramChannel> dgramFuture = quic.openDatagramChannel();
}
```

关键点：

- DATAGRAM 不会像 stream 一样重传，也不保证顺序
- 每条连接最多只有一个 QuicDatagramChannel
- 如果本地通过 setDisableDatagram(true) 禁用了 DATAGRAM，即使对端支持也不会开放该能力

### 4.7 连接级控制 API

QuicChannel 还提供了一组典型的连接级操作：

```java
quic.ping();
quic.ping(3000);
quic.pathChallenge(new byte[8]);
quic.sendMaxDataSize(2 * 1024 * 1024);
quic.upgradeBidiStreams(20);
quic.closeGracefully();
```

这组 API 分别对应：

- RTT 探测
- 路径探测
- 连接级流控扩容
- 可打开流数量扩容
- 优雅关闭或错误关闭

### 4.8 在 HTTP/3 中使用 QUIC

如果你的目标是直接提供浏览器可访问的 HTTP/3 服务，更推荐使用上层集成，而不是自己手工搭 QUIC 帧。

```java
NetaHttpServer server = new NetaHttpServer();
server.http3(true);
server.startHttp3(8443);
```

这条路径会在 QUIC 之上继续安装 HTTP/3 codec 和相应的应用层处理链。

这里要特别注意：

- HTTP/3 依赖 QUIC 的 stream 语义，而不是连接级 `QuicMessage` 语义
- 如果你只是想提供标准 HTTP/3 服务，优先直接使用上层集成，而不是先选 `CHANNEL` 模式再自己拼 HTTP 语义

## 5. 内部工作原理

### 5.1 服务端建链路径

服务端收到 UDP 数据报后，并不会立刻把它当成普通 payload 交给应用，而是先进入 QUIC 专用分发流程：

```text
UDP datagram
  -> QuicAsyncServerChannel
  -> parse Long Header / Short Header
  -> route by DCID
  -> new handshake or existing connection
  -> QuicAsyncChannelHandshake
  -> promote to QuicChannelAsync + QuicChannel
```

在这个过程中，服务端主要完成这些步骤：

- 从 Long Header 报文中识别 Initial、Handshake 等阶段
- 根据 DCID 判断该报文属于已有连接、进行中的握手，还是一个全新的连接尝试
- 对新连接执行 token 校验、Retry 或直接进入握手
- 驱动 QuicAsyncChannelHandshake 和 QuicTlsEngine 完成密钥派生与传输参数协商
- 握手完成后，把临时握手上下文提升为 QuicChannelAsync 和 QuicChannel

### 5.2 客户端建链路径

客户端发起 connect 后，内部并不是像 TCP 那样只等一个 socket connect 完成，而是会经历 QUIC 自身的建链流程：

```text
connectSync / connectAsync
  -> QuicAsyncClientChannel
  -> create DCID + derive Initial keys
  -> send Initial
  -> receive ServerHello / Handshake
  -> derive 1-RTT keys
  -> create QuicChannelAsync + QuicChannel
```

对业务来说，这意味着：

- connectSync(...) 返回的已经是可直接使用的握手后连接
- connectAsync(...) 只有在握手完成后才会进入成功回调
- 握手阶段的 ACK、CRYPTO 重组和 TLS 消息处理对业务是透明的

### 5.3 建链后的收发路径

握手完成后，连接进入 QuicChannelAsync 阶段。它承担了大部分运行时协议职责：

- 接收侧
  - 解析 1-RTT 报文
  - 记录 ACK 所需的接收包号
  - 更新丢包探测与拥塞控制状态
  - 按 streamMode 把 STREAM 帧分发给对应的 QuicStreamChannel，或重组为 QuicMessage 投递到连接级 pipeline
  - 把 DATAGRAM 帧分发给 QuicDatagramChannel
- 发送侧
  - 聚合待发送控制帧和业务帧
  - 构造 Short Header 报文
  - 更新 sent-packet tracker
  - 在需要时附带 ACK 或探测帧

这也是为什么 QuicChannel 自身只暴露控制 API，而不直接实现底层报文处理。

### 5.4 Stream 和 Datagram 的 pipeline 关系

Neta 对 QUIC 的一个重要适配点，是把连接级语义和业务消息语义拆开处理：

- 连接级 pipeline
  - 适合放连接建立、连接关闭、连接级控制逻辑
  - 在 `CHANNEL` 模式下，也负责接收 `QuicMessage`
- stream pipeline
  - 在 `STREAM` 模式下负责可靠业务消息、请求响应和应用协议编解码
- datagram pipeline
  - 适合放不可靠消息、状态广播和低延迟事件

这种拆分方式让业务不必直接关心底层帧类型，只需要选择合适的子通道语义。

### 5.5 QuicSoConfig 在内部的作用方式

QuicSoConfig 中的参数大致分成两类：

- 握手期会写到线协议上的参数
  - 例如 initial_max_data、initial_max_streams_*、max_datagram_frame_size
- 只影响本地运行时策略的参数
  - 例如 streamIdleTimeoutMs、disableDatagram、streamMode、connectionListener

这意味着同一个配置对象既影响“对端能看到什么”，也影响“本地运行时如何管理连接”。在调优时需要把这两类参数区分开看。
