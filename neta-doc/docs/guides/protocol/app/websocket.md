---
sidebar_position: 6
title: WebSocket
description: 说明 Neta 在 WebSocket 上的支持范围、典型装配方式、内部工作机制、关键数据流和使用注意事项。
---

## 1. 简介

WebSocket 是一个建立在 HTTP 语义之上的全双工长连接协议。在 HTTP/1.x 下它通过 Upgrade 建立，在 HTTP/2 下则可以通过 RFC 8441 的 extended CONNECT，或在兼容模式下继续复用 HTTP 语义桥接。它的目标如下：

- 先通过 HTTP/1.x Upgrade 或 HTTP/2 语义握手完成协商。
- 握手成功后切换到持续连接。
- 双方都可以主动发送消息。
- 消息既可以是文本，也可以是二进制，还支持 Ping、Pong、Close 等控制帧。

Neta 对 WebSocket 的支持位于 neta-codec-http 模块中，整体上分成 4 层：

- Handshake 层：处理 handshake、校验、拒绝响应、transparent mode 切换。
- Frame 层：处理 WebSocketFrame 和 HttpByteBuf 之间的双向转换。
- Extension 层：根据握手协商结果执行扩展运行时逻辑，并校验 RSV 位是否合法。
- Message 层：处理 WebSocketMessage、消息片段流、控制事件、可选聚合和可选自动分帧。

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- handshake
  - 服务端握手：WebSocketServerHandshakeDuplexer
  - 客户端握手：WebSocketClientHandshakeDuplexer
  - 客户端 HTTP + WebSocket 混合升级：WebSocketClientUpgradeRouteDuplexer
- HTTP/2 传输形态
  - 标准 WebSocket over HTTP/2：RFC 8441 extended CONNECT
  - 兼容 WebSocket over HTTP/2：沿用 RFC 6455 Upgrade 语义的桥接链路
  - 支持 HTTP/1.1 -> h2c -> HTTP/2 后继续进入 WebSocket
- 协议版本
  - RFC 6455 家族：V7、V8、V13
  - 兼容旧版 V0/Hixie-76 framing
- Frame 层编解码
  - TEXT、BINARY、CONTINUATION、PING、PONG、CLOSE
  - 客户端掩码与服务端非掩码方向校验
  - 16 位和 64 位扩展长度解析
- Message 层能力
  - 默认保留 frame 语义
  - 可选，把 frame 序列聚合为一条完整消息
  - 可选，把单条完整消息自动拆成多个 frame，避免必须一次性聚合成单块内容
- 协议控制能力
  - Ping 自动回 Pong
  - Pong 作为事件向上传播
  - Close 校验、回包、状态标记与关闭流程
  - 文本帧与 close reason 的 UTF-8 校验
- 状态与上下文
  - 握手完成后安装 WebSocketContext
  - 发布 WebSocketHandshakeEvent
  - HTTP/1.x 通过 HttpThroughEvent.enable() 通知 HTTP codec 进入 transparent mode
  - HTTP/2 按 stream 维度切换到 WebSocket 分支，不复用 HTTP/1.x transparent mode 语义
- 扩展协商与运行时承载
  - 默认不启用任何 WebSocket 扩展
  - 已支持结构化扩展协商结果和运行时扩展链路
  - 已支持在 WebSocketContext 和 WebSocketHandshakeEvent 中读取协商结果

### 2.2 已支持的 WebSocket 扩展

当前内置并已验证的扩展如下：

| 扩展名 | 状态 | 协商约束 | 运行时行为 |
| --- | --- | --- | --- |
| permessage-deflate | 已支持 | 仅 RFC 6455；支持 `client_no_context_takeover`、`server_no_context_takeover`、`client_max_window_bits`、`server_max_window_bits`；`*_max_window_bits` 当前支持 `8..15` | 按消息执行压缩/解压，使用 `RSV1`，支持分片消息和 negotiated window bits |
| deflate-frame | 已支持 | 仅 RFC 6455；仅接受单个 `deflate-frame`，且不接受参数 | 按单帧执行原始 DEFLATE 压缩/解压，使用 `RSV1` |
| x-webkit-deflate-frame | 已支持 | 仅 RFC 6455；仅接受单个 `x-webkit-deflate-frame`，且不接受参数 | 作为 `deflate-frame` 的兼容别名复用同一套运行时逻辑 |

需要特别说明的边界：

- `WebSocketSettings` 只负责声明当前端点允许哪些扩展参与协商，不会自动执行扩展。
- 扩展真正生效依赖 `WebSocketExtensionDuplexer`，它必须放在 Frame 层之后、Message 层之前。
- 当前框架已经可以按协商结果顺序解析多个扩展项，并把顺序写入 `WebSocketContext.extensionList()`。
- 当前内置的 3 个压缩扩展都占用 `RSV1`，对外稳定用法仍建议按单扩展启用，不建议把这些内置压缩扩展组合使用。

显式启用内置扩展的典型方式如下：

```java
WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13)
        .usePerMessageDeflateDefaults();

ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(settings));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
ctx.addLast("ws-message", new WebSocketMessageDuplexer());
```

如果需要启用其它内置扩展，可改为：

```java
WebSocketSettings.of(WebSocketVersion.V13).useDeflateFrameDefaults();
WebSocketSettings.of(WebSocketVersion.V13).useXWebkitDeflateFrameDefaults();
```

### 2.3 能力边界

当前实现更适合做“协议层正确、装配清晰、可继续扩展”的 WebSocket 基础设施，而不是一套完整应用框架。

它负责：

- 协议握手
- Frame/Message 转换
- 分片处理
- 控制帧处理
- 基本长度保护与关闭语义

不负责：

- 应用层心跳策略
- 业务级 ACK、重试、断点续传
- 文件传输协议
- 业务路由和订阅模型
- 大对象传输方案本身

大对象传输相关能力的边界也需要单独明确：

- 入站消息聚合、出站自动分帧、超大单帧流式切片，解决的是 WebSocket 协议层问题
- 这些能力不等同于完整的大对象传输方案
- 文件上传下载、模型输出流、音视频分片、带重试和进度控制的传输，仍应优先采用应用层 chunk 协议

### 2.4 当前明确限制

- WebSocket 扩展默认关闭。
  - 如果 `WebSocketSettings` 没有注册任何扩展支持实现，握手阶段会拒绝非空的 `Sec-WebSocket-Extensions` 协商结果。
  - 只有在 settings 中显式注册了对应扩展，并且 pipeline 中接入 `WebSocketExtensionDuplexer`，扩展才会真正执行。
- 当前内置扩展都仅面向 RFC 6455 framing。
  - V0/Hixie-76 兼容 framing 仍可使用基础握手与 frame codec，但不支持内置扩展。
- `permessage-deflate` 的参数支持不是“全量 RFC 7692 任意组合”。
  - 当前稳定支持的是 `client_no_context_takeover`、`server_no_context_takeover`、`client_max_window_bits`、`server_max_window_bits`。
  - `client_max_window_bits` 在请求阶段允许无值提示，协商结果阶段必须是具体值。
  - `server_max_window_bits` 在协商结果阶段必须是具体值，且不会放宽客户端请求过的上界。
- 当前文档只把单扩展启用视为稳定用法。
  - 框架已经具备多扩展结果解析和顺序保留能力。
  - 但内置压缩扩展共享 `RSV1`，组合启用不属于当前对外承诺的稳定能力。
- 当前最直接、最成熟的装配仍然是 HTTP/1.x Upgrade。
- HTTP/2 路径已经支持，但稳定前提是先进入 `Http2FrameDuplexe -> Http2ObjectDuplexe -> nextPartition(...)` 的常规链路，再在每个 stream 分区内部切 HTTP/WebSocket 分支。
- 控制帧限制严格遵循协议。
  - 控制帧不能分片。
  - 控制帧 payload 不能超过 125 字节。
  - close code 和 reason 会被严格校验。
- 当前实现实际主动使用的 WebSocket close code 以 `1002`、`1007`、`1009` 为主。
  - `1002(PROTOCOL_ERROR)`：协议格式、opcode、mask 方向、控制帧约束、close code 合法性等校验失败。
  - `1007(INVALID_DATA)`：文本 payload 或 close reason 不是合法 UTF-8。
  - `1009(MESSAGE_TOO_BIG)`：单帧或聚合后消息长度超过当前实现支持的上限。
  - `1005(NO_STATUS)`、`1006(ABNORMAL_CLOSURE)`、`1015(TLS_HANDSHAKE)` 仅作为本地语义值使用，不会上线发送。
- RFC 6455 的扩展 payload length 字段仍会按 64 位规则解析，但对外暴露的 payloadLength，以及 message/frame 相关长度上限参数，当前都使用 int。
  - 因此超大单帧接收采用“流式切片输出”，而不是构造一个超过 int 索引范围的单块 ByteBuf。

## 3. 组件分层

### 3.1 整体分层

```text
HTTP Codec 层
  负责 HTTP/1.x 解析、编码，以及 transparent mode 切换

WebSocket Handshake 层
  负责 handshake、HTTP reject、上下文安装、事件发布

WebSocket Frame 层
  负责 WebSocketFrame <-> HttpByteBuf

WebSocket Extension 层
  负责按协商结果执行运行时扩展，并校验 RSV 位

WebSocket Message 层
  负责 WebSocketMessage、消息片段流、控制事件、聚合与自动分帧
```

### 3.2 推荐入口

常规场景优先使用双工器，而不是直接拼底层部件：

- Frame 层推荐：WebSocketFrameDuplexer
- 扩展层推荐：WebSocketExtensionDuplexer
- Message 层推荐：WebSocketMessageDuplexer

只有在这些场景才建议直接挂底层 handler：

- 只测入站或只测出站
- 需要把 inbound 和 outbound 分开装配
- 需要更细粒度控制聚合、自动分帧、事件传播

### 3.3 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| WebSocketServerHandshakeDuplexer | 服务端握手入口 | 服务端 Upgrade |
| WebSocketClientHandshakeDuplexer | 客户端握手入口 | websocket only client |
| WebSocketClientUpgradeRouteDuplexer | 客户端 HTTP 和 WebSocket 混用桥 | 同连接先 HTTP 后升级 |
| WebSocketFrameDuplexer | 帧层双工入口 | 常规 frame pipeline |
| WebSocketFrameDecoder | HttpByteBuf -> WebSocketFrame | 只做入站帧解析 |
| WebSocketFrameEncoder | WebSocketFrame -> HttpByteBuf | 只做出站帧编码 |
| WebSocketExtensionDuplexer | 扩展运行时入口 | 启用任意已协商扩展时接在 frame 与 message 之间 |
| WebSocketMessageDuplexer | 消息层双工入口 | 常规业务收发消息 |
| WebSocketInboundHandler | frame -> message/event | 定制入站聚合或事件处理 |
| WebSocketOutboundHandler | message/event -> frame | 定制出站序列或自动分帧 |

## 4. 使用方式

这一节重点说明组装形态和适用范围。

先看推荐组合矩阵，再按场景挑选具体装配：

| 场景 | 推荐入口组合 | 切换方式 | 适用范围 |
| --- | --- | --- | --- |
| HTTP/1.x 纯 WebSocket 服务端 | `HttpServerDuplexe -> WebSocketServerHandshakeDuplexer -> WebSocketFrameDuplexer -> WebSocketMessageDuplexer` | 握手成功后 HTTP codec 切到 transparent mode | 端口只做 WebSocket |
| HTTP/1.x 混用端口服务端 | `HttpServerDuplexe -> typedRouting(default=http) -> WebSocketServerUpgradeRouteDuplexer / ws branch` | 路由从 HTTP 分支切到 `HttpRouteKey.BRANCH_SOCKET` | 同端口同时处理 HTTP 和 WebSocket |
| WebSocket only 客户端 | `HttpClientDuplexe -> WebSocketClientHandshakeDuplexer -> WebSocketFrameDuplexer -> WebSocketMessageDuplexer` | 自动握手或手动从 `ws-client` 节点发起 | SDK、长连接客户端 |
| HTTP + WebSocket 混用客户端 | `HttpClientDuplexe -> typedRouting(default=http) -> WebSocketClientUpgradeRouteDuplexer / ws branch` | 同连接先 HTTP 再升级 | 单连接复用 |
| HTTP/2 直连 WebSocket | `Http2FrameDuplexe -> Http2ObjectDuplexe -> nextPartition(h2-stream) -> per-stream route` | 每个 stream 单独切 HTTP/WebSocket 分支 | prior knowledge h2 或 ALPN h2 |
| HTTP/1.1 / h2c / HTTP/2 汇聚入口 | `HttpAggregatorRoute -> BRANCH_H1 / BRANCH_H2C / BRANCH_H2` | 顶层先判协议，再在 h2 branch 里按 stream 分区 | cleartext TCP 统一入口 |
| TLS 汇聚入口 | `SslDuplexer -> HttpAggregatorOverTlsRoute -> BRANCH_H1 / BRANCH_H2C / BRANCH_H2` | ALPN + 首包联合判定 | HTTPS/WSS 统一入口 |

### 4.1 服务端：纯 WebSocket 端口

适用范围：

- 端口只接收 WebSocket Upgrade
- 不处理普通 HTTP 业务
- 适合网关、推送通道、实时消息入口

推荐装配：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-message", new WebSocketMessageDuplexer());
ctx.addLast("app-handler", appHandler);
```

这个形态的特点：

- HTTP codec 只负责 Upgrade 前后的协议切换。
- 握手成功后，HTTP codec 切到 transparent mode。
- 后续 websocket 数据进入 Frame 层，再进入 Message 层。

如果启用了扩展，推荐改成：

```java
WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13)
  .usePerMessageDeflateDefaults();

ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(settings));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-ext", new WebSocketExtensionDuplexer());
ctx.addLast("ws-message", new WebSocketMessageDuplexer());
ctx.addLast("app-handler", appHandler);
```

启用扩展后，稳定链路变成：Handshake -> Frame -> Extension -> Message。

### 4.2 服务端：HTTP + WebSocket 混用端口

适用范围：

- 同一端口既处理普通 HTTP，也处理 WebSocket
- 适合统一入口服务或管理端口

推荐做法：

- pipeline 只放协议和路由组件，不在里面写业务回包逻辑。
- HTTP 分支负责普通请求和 upgrade 检测。
- WebSocket 分支负责 frame 和 message 编解码。
- 真正的业务处理，统一放到 `neta.subscribe(...)` 里。

下面这个写法就是当前推荐模式，和 `RealAsServerTest` 一致：

```java
neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
    ctx.addLast("http-server", new HttpServerDuplexe());
  final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
  ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(BRANCH_HTTP, branchCtx -> {
        // for http
    branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
        branchCtx.addLastDecoder("ws-handshake-events", handshakeEventTap(serverEvents));
        branchCtx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));
    }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
        // for websocket
        branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
        branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
        branchCtx.addLastDecoder("ws-events", serverEventTap(serverEvents));
    });
  routingControl[0] = routing.control();
    ctx.addLast("server-route", routing.build());
}, SoConfig.TCP());
```

这个版本有 3 个特点：

- 路由默认固定在 HTTP 分支。`WebSocketServerUpgradeRouteDuplexer` 在握手完成后主动切到 WebSocket 分支。
- `WebSocketServerUpgradeRouteDuplexer` 负责把 upgrade 请求导到 WebSocket 分支，不需要再额外套一层 handshake 子路由。
- 这里应显式使用 `switchRouteNextTick(...)`。WebSocket upgrade 没有 route seed handoff，且当前 HTTP 握手事务必须先在旧分支完整收尾，所以不适合使用默认 immediate 的 `switchRoute(...)`。
- HTTP 回包和 WebSocket 回包都放在订阅器里处理，pipeline 只负责把协议数据解出来。

“同端口 HTTP + WebSocket”服务端可按以下结构装配：

- `HttpServerDuplexe` 负责 HTTP 编解码。
- HTTP 分支放 `WebSocketServerUpgradeRouteDuplexer` 和 `HttpRequestAggregator`。
- WebSocket 分支默认放 `WebSocketFrameDuplexer` 和 `WebSocketMessageDuplexer`。
- 如果开启扩展，在两者之间插入 `WebSocketExtensionDuplexer`。
- 业务层通过 `neta.subscribe(...)` 统一处理 `FullHttpRequest` 和 `WebSocketMessage`。

### 4.3 客户端：WebSocket Only，自动握手

适用范围：

- 连接建立后立即升级为 WebSocket
- 适合 SDK、消息通道、推送客户端

推荐装配：

```java
WebSocketAutoHandshakeConfig auto = new WebSocketAutoHandshakeConfig("/chat");

ctx.addLast("http-client", new HttpClientDuplexe());
ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13, auto));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-message", new WebSocketMessageDuplexer());
ctx.addLast("app-handler", appHandler);
```

特点：

- onActive 时自动发出 Upgrade 请求。
- 业务层通常只需要等待 WebSocketHandshakeEvent 或 WebSocketContext ready。

### 4.4 客户端：WebSocket Only，手动握手

适用范围：

- 需要精确控制何时发起 Upgrade

平铺 pipeline 示例：

```java
ctx.addLast("http-client", new HttpClientDuplexe());
ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-message", new WebSocketMessageDuplexer());
ctx.addLast("app-handler", appHandler);
```

手动触发：

```java
channel.sendData(WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"),
                 "ws-client" // WebSocketClientHandshakeDuplexer 所在的 Handler name
).get();
```

这个模式适合 websocket only 客户端：

- 连接建立后不需要先跑普通 HTTP 请求。
- 业务自己决定何时发起 upgrade。
- 第一条握手请求直接从 `ws-client` 节点进入发送链路。

### 4.5 客户端：HTTP + WebSocket 混用端口

适用范围：

- 同一条连接先处理普通 HTTP，再升级成 WebSocket
- 适合单连接复用、先拉取 HTTP 资源再切实时通道的客户端

推荐装配：

```java
ctx.addLast("http-client", new HttpClientDuplexe());
final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];

ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault("http", branchCtx -> {
  branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplexer(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
  branchCtx.addLastDecoder("resp-agg", new HttpResponseAggregator());
}).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
  branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
  branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
  branchCtx.addLastDecoder("ws-handler", wsHandler);
});

routingControl[0] = routing.control();

ctx.addLast("client-route", routing.build());
```

这类场景的发送顺序通常分成两段。

先走普通 HTTP：

```java
DefaultFullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/http-echo");
httpRequest.setHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
httpRequest.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
channel.sendData(httpRequest, "http-client").get();
```

再在同一条连接上发起 websocket upgrade：

```java

FullHttpRequest handshake = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
handshake.setHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
channel.sendData(new DefaultHttpRequest(handshake.protocolVersion(), handshake.method(), handshake.uri())).get();
channel.sendData(new DefaultLastHttpHeaders(handshake)).get();
channel.sendData(new DefaultLastHttpContent(ByteBuf.EMPTY)).get();
handshake.release();
```

这个模式的关键点是：

- 普通 HTTP 请求继续走默认 HTTP 分支。
- 只有 upgrade 请求会被 `WebSocketClientUpgradeRouteDuplexer` 接管。
- 握手成功后，当前路由切到 websocket 分支，后续消息就不再按普通 HTTP 处理。

### 4.6 HTTP/2 直连：先按 stream 分区，再在流内切 HTTP/WebSocket

适用范围：

- 入口已经是 HTTP/2，可能来自 prior knowledge h2，也可能来自 TLS ALPN 直接协商到 h2
- 需要在同一条 HTTP/2 连接上同时承载普通 HTTP 流和 WebSocket 流
- 需要让每个 stream 维持独立的握手状态、消息状态和关闭状态

服务端推荐装配：

```java
ProtoHelper.standard()
    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))
    .nextDuplex("h2-message", new Http2ObjectDuplexe(true))
    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
      Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
      pb.policy(policy).byDefault(partitionCtx -> {
        partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
      }).byInitializer(partitionCtx -> {
        final ProtoRoutingControl[] streamRoutingControl = new ProtoRoutingControl[1];
        ProtoRoutingBuilder<Object, Object> streamRouting = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
          branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(streamRoutingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
          branchCtx.addLastDecoder("http-request", new HttpRequestAggregator(1024 * 1024));
          branchCtx.addLastDecoder("http-handler", httpHandler);
        }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
          branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
          branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
          branchCtx.addLastDecoder("ws-handler", wsHandler);
        });
        streamRoutingControl[0] = streamRouting.control();
        partitionCtx.addLast("server-route", streamRouting.build());
      });
    })
    .config(ctx);
```

客户端推荐装配：

```java
ProtoHelper.standard()
    .nextDuplex("h2-frame", new Http2FrameDuplexe(false))
    .nextDuplex("h2-message", new Http2ObjectDuplexe(false))
    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
      Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
      pb.policy(policy).byDefault(partitionCtx -> {
        partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
      }).byInitializer(partitionCtx -> {
        final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
        ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
          branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplexer(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
        }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
          branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
          branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
        });
        routingControl[0] = routing.control();
        partitionCtx.addLast("client-route", routing.build());
      });
    })
    .config(ctx);
```

这一组装配有 4 个关键点：

- 顶层稳定链路始终是 `Http2FrameDuplexe -> Http2ObjectDuplexe -> nextPartition(...)`，不要直接把 WebSocket 握手器挂在连接级别。
- 每个 stream 分区都要保留默认控制分支，一般用 `Http2ObjectStreamManager` 处理 reset、goaway、关闭请求等连接级控制动作。
- stream 内的默认路由仍然是“HTTP 语义分支”，因为 RFC 8441 标准 extended CONNECT 和兼容性 RFC 6455 over h2 都先表现为一条 HTTP 对象流，然后才切到 WebSocket 分支。
- 标准 RFC 8441 与兼容性 RFC 6455 over h2 的差异主要在握手请求形态，不在分区结构本身；稳定的 per-stream route 组合是一致的。

### 4.7 cleartext 统一入口：HTTP/1.1、h2c、HTTP/2 和 WebSocket 共存

适用范围：

- 同一个 cleartext TCP 入口既要处理普通 HTTP/1.1，也要接受 h2c upgrade，还要处理 prior knowledge h2
- h2 协议分支内部还要继续承载普通 HTTP 流和 WebSocket 流

推荐装配：

```java
ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
  ProtoRoutingControl routingControl = routing.control();

  routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch
      .nextDuplex("http-codec", new HttpServerDuplexe())
      .nextDuplex("h1-upgrade", new H2CUpgradeServerDuplexe(routingControl))
      .nextDecoder("http-request", new HttpRequestAggregator(1024 * 1024))
      .nextDecoder("http-handler", httpHandler));

  routing.branch(HttpRouteKey.BRANCH_H2C, branch -> branch
      .nextDuplex("http-codec", new HttpServerDuplexe())
      .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl)));

  routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch
      .nextDuplex("h2-frame", new Http2FrameDuplexe(true))
      .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))
      .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
        pb.policy(policy).byDefault(partitionCtx -> {
          partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
        }).byInitializer(partitionCtx -> {
          final ProtoRoutingControl[] streamRoutingControl = new ProtoRoutingControl[1];
          ProtoRoutingBuilder<Object, Object> streamRouting = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
            branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(streamRoutingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
            branchCtx.addLastDecoder("http-request", new HttpRequestAggregator(1024 * 1024));
            branchCtx.addLastDecoder("http-handler", httpHandler);
          }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
            branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
            branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
            branchCtx.addLastDecoder("ws-handler", wsHandler);
          });
          streamRoutingControl[0] = streamRouting.control();
          partitionCtx.addLast("server-route", streamRouting.build());
        });
      }));
}).config(ctx);
```

这类统一入口要记住：

- `HttpAggregatorRoute` 只负责入口协议判定，不负责 WebSocket 业务路由。
- `BRANCH_H2C` 只做升级桥接，真正的 h2 业务和 WebSocket 业务都应落到 `BRANCH_H2`。
- 如果入口是 TLS/WSS，把顶层选择器替换成 `SslDuplexer -> HttpAggregatorOverTlsRoute` 即可；如果 TLS 入口只承载 h2，不需要同时兼容 HTTP/1.1，可优先考虑 `Http2OverTlsRoute`。
- 这一套聚合入口可以同时覆盖“HTTP/1.1 普通请求”“HTTP/1.1 升级 WebSocket”“HTTP/1.1 升级 h2c 后再走 WebSocket”“prior knowledge h2 上直接走 WebSocket”等多种组合。

### 4.8 只用 Frame 层

适用范围：

- 业务自己处理协议消息
- 做协议测试、抓包验证、底层调试

装配方式：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("app-frame-handler", frameHandler);
```

### 4.9 入站聚合

适用范围：

- 业务只关心完整消息
- 消息体可控，通常不是超大对象

如果业务层只接收“已经聚合完成的一条消息”，最直接的装配方式是从 HTTP codec 开始完整挂起来：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-message", new WebSocketMessageDuplexer(true, 1024 * 1024));
ctx.addLastDecoder("app-handler", appHandler);
```

如果不使用 `WebSocketMessageDuplexer`，也可以只挂入站 handler，在 Frame 层后面显式打开聚合：

```java
new WebSocketInboundHandler(true)
new WebSocketInboundHandler(true, 1024 * 1024)
```

语义：

- 同一条分片的 TEXT 或 BINARY 消息，会在 final continuation 到达后再输出。
- 超过 maxMessagePayloadLength 时，会走 CLOSE(1009) 即 MESSAGE_TOO_BIG。

### 4.10 出站自动分帧

适用范围：

- 业务层始终发送完整消息
- 不希望底层实际产出超大单帧

如果需要让底层按阈值自动把一条完整消息拆成多个 frame，可以直接设置 `maxFramePayloadLength`：

```java
new WebSocketOutboundHandler(16 * 1024)
new WebSocketMessageDuplexer(16 * 1024)
new WebSocketMessageDuplexer(true, 1024 * 1024, 16 * 1024)
```

设置后语义是：

- 首帧仍然是 TEXT 或 BINARY。
- 后续帧使用 CONTINUATION。
- 最后一帧带 FIN=true。

如果从完整服务端链路开始装配，可以这样写：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
ctx.addLast("ws-message", new WebSocketMessageDuplexer(true, 1024 * 1024, 16 * 1024));
ctx.addLast("app-handler", appHandler);
```

## 5. 内部工作机制

### 5.1 握手到消息处理的整体时序

下面的时序图以 HTTP/1.x Upgrade 为例。HTTP/2 场景不会出现 `101 Switching Protocols`，而是先经过 `Http2FrameDuplexe -> Http2ObjectDuplexe -> nextPartition(...)`，再在每个 stream 分区内部通过 `WebSocketServerUpgradeRouteDuplexer` 或 `WebSocketClientUpgradeRouteDuplexer` 切到 WebSocket 分支。

```text
Client/App           HTTP codec            WS Handshake               WS Frame              WS Message            App Handler
    |                    |                       |                         |                       |                     |
    | HTTP Upgrade       |                       |                         |                       |                     |
    |------------------->| HttpObject            |                         |                       |                     |
    |                    |---------------------->| collect + validate      |                       |                     |
    |<-------------------|<----------------------| HTTP 101 / reject       |                       |                     |
    |                    |<----------------------| HttpThroughEvent.enable |                       |                     |
    |                    | switch transparent    | install WebSocketContext|                       |                     |
    |                    |---------------------->| WebSocketHandshakeEvent |                       |                     |
    | websocket bytes    |                       |                         |                       |                     |
    |------------------->| HttpByteBuf           |                         |                       |                     |
    |                    |---------------------->| pass through            |                       |                     |
    |                    |                       |------------------------>| decode frame          |                     |
    |                    |                       |                         |---------------------->| frame -> message    |
    |                    |                       |                         |                       |-------------------->|
    |                    | HttpByteBuf           |                         |<----------------------| message <- frame    |
    |<-------------------|<----------------------|<------------------------| encode frame          |                     |
```

### 5.2 HTTP codec 的前置位置

HTTP/1.x 下，WebSocket 通过 HTTP Upgrade 建立。当前实现的关键切换点如下：

- 握手前：流里是 HttpObject
- 握手后：HTTP codec 切成 transparent mode，流里变成 HttpByteBuf
- 然后 frame decoder 再把 HttpByteBuf 解释成 WebSocketFrame

HTTP/2 下则是另一条稳定路径：

- 连接级先经过 `Http2FrameDuplexe -> Http2ObjectDuplexe`
- 再按 stream 进入 `nextPartition("h2-stream", ...)`
- 每个 stream 分区内部先走 HTTP 语义路由，再在握手完成后切到 WebSocket 分支

### 5.3 Message 层的默认非聚合语义

默认不聚合的原因如下：

- 这最贴近 WebSocket 原生分片语义
- 业务可以自己决定是流式消费还是自定义聚合
- 对大对象传输更安全，不会默认吃掉一整条大消息的内存

### 5.4 超大单帧的流式切片策略

原因如下：

- 协议头里的扩展长度字段需要按 64 位规则解析
- 但对外的 payloadLength 与各类 message/frame 长度阈值已经统一为 int
- 底层 ByteBuf 读写和索引本身也仍然是 int 模型
- 因此当前实现对超大单帧采用片段序列输出，而不是构造超大单块缓冲

这也是 WebSocketFrameDecoder 新增 maxPayloadChunkLength 的意义。

### 5.5 服务端握手的 HTTP 片段流处理方式

服务端握手器建立在 HTTP 片段流之上：

- `HttpRequest`
- `HttpHeaders`
- `HttpContent`
- `LastHttpContent`

这样做有三个直接好处：

- 可以直接挂在真实 `HttpServerDuplexe` 后面工作，不强依赖前置聚合器
- 能同时兼容片段流输入和已经聚合过的完整请求对象
- 握手成功后可以继续沿同一条 HTTP codec 链切到 transparent mode，而不是切换到另一套输入模型

服务端握手阶段的真实数据流更接近下面这个过程：

```text
Client                HTTP codec         Server Handshake
  |                       |                     |
  | HttpRequest           |                     |
  |---------------------->|                     |
  |                       |-------------------->| collect request line
  | HttpHeaders           |                     |
  |---------------------->|                     |
  |                       |-------------------->| append headers
  | LastHttpContent       |                     |
  |---------------------->|                     |
  |                       |-------------------->| close header/body section
  |                       |                     | validate version/key/path
  |                       |                     | call authorizer
  |                       |<--------------------| DefaultHttpResponse(101/reject)
  |                       |<--------------------| DefaultLastHttpHeaders
  |                       |<--------------------| DefaultLastHttpContent
  |<----------------------| HTTP response       |
```

因此，新文里的 mixed 场景虽然常用 `FullHttpRequest` 做路由判定，但握手器本身的内部语义仍然是 HTTP 片段流握手处理器。

### 5.6 客户端对 upgrade 请求的预缓存机制

其内部时序可以概括为：

```text
App         Client Handshake                HTTP codec          Server
 |                 |                            |                 |
 | HttpRequest     |                            |                 |
 |---------------->| buffer request fragments   |                 |
 |                 | assemble request fragments |                 |
 |                 | detect websocket upgrade   |                 |
 |                 | remember key/path/version  |                 |
 |                 |--------------------------->| send request    |
 |                 |                            |---------------->|
 |                 |                            | 101 response    |
 |                 |<---------------------------|<----------------|
 |                 | assemble response          |                 |
 |                 | validate accept/protocol   |                 |
 |                 | fire through + ready       |                 |
```

客户端握手器不是“看到 101 就算成功”，它必须把本端发出的 upgrade 请求关键信息跨阶段保留下来，用于后续校验响应。

必须记住的核心字段包括：

- `Sec-WebSocket-Key` 或 V0 的 `Key1/Key2/Key3`
- 请求路径
- 请求版本
- 请求中的子协议列表

### 5.7 握手器状态机与拦截规则

如果只看行为边界，可以把服务端和客户端握手器都理解成“有状态门禁”。

服务端状态机：

```text
                 +-----------+
    start -----> | not ready |
                 +-----------+
                       |
                       | collect + validate + authorize
                       v
                 +-------------+
                 | authPending |
                 +-------------+
                   |         |
        reject/err |         | accept
                   v         v
             +-----------+  +-------+
             | cleared   |  | ready |
             +-----------+  +-------+
```

服务端握手器的放行规则如下：

```text
Inbound -> Server Handshake

  not ready
    + 请求片段          -> collect / validate / authorize
    + 非请求片段        -> reject 400 + clear

  authPending
    + any inbound       -> reject 400 + clear

  ready
    + any inbound       -> pass through

Outbound -> Server Handshake

  not ready
    + 响应片段          -> pass through
    + 非响应片段        -> drop + warn

  authPending
    + any outbound      -> drop + warn

  ready
    + any outbound      -> pass through
```

客户端状态机：

```text
                 +-----------+
    start -----> | not ready |
                 +-----------+
                       |
                       | detect outbound ws upgrade
                       v
                 +----------------+
                 | requestPending |
                 +----------------+
                   |          |
      invalid 101  |          | valid 101
         / err     v          v
             +-----------+  +-------+
             | cleared   |  | ready |
             +-----------+  +-------+
```

客户端握手器的放行规则可以概括为：

```text
Outbound -> Client Handshake

  not ready
    + 请求片段
        -> buffer
        -> if websocket handshake
             pass through + requestPending=true
        -> else
             drop buffered request

    + 非请求片段
        -> drop + warn

  requestPending
    + any outbound
        -> drop + warn

  ready
    + any outbound
        -> pass through

Inbound -> Client Handshake

  not ready
    + 响应片段
        -> buffer response
        -> if requestPending && response==valid 101
             complete handshake
        -> if requestPending && invalid 101
             clear + close
        -> if !requestPending
             consume / reset only

    + 非响应片段
        -> drop + warn + clear

  ready
    + any inbound
        -> pass through
```

需要注意的是：`WebSocketClientUpgradeRouteDuplexer` 会先让客户端握手器完整拦截一次 upgrade 事务，等 `requestPending -> ready` 后再切到 websocket 分支。

这里同样保持 lazy 切换即可：客户端 websocket upgrade 不需要把已消费请求以 seed 形式补投递给新分支，也不需要在切换后的同一轮立刻执行 websocket 分支。

### 5.8 客户端对 101 响应的校验过程

客户端收到 101 后，会按版本执行一整套校验。

RFC 6455 家族下，最核心的校验链如下：

```text
Client Handshake
    -> status == 101 ?
    -> Connection contains Upgrade ?
    -> Upgrade == websocket ?
    -> Sec-WebSocket-Accept matches request key ?
    -> selected sub-protocol is in requested protocols ?
    -> negotiated extensions must stay empty ?
    -> success => install context + through + handshake event
```

如果是 V0/Hixie-76，则还会多一条 challenge body 校验路径。

可以把客户端校验时序理解成：

```text
Server                 HTTP codec                Client Handshake
  |                         |                            |
  | 101 + headers/body      |                            |
  |------------------------>|                            |
  |                         |--------------------------->| collect response fragments
  |                         |                            | validate status/headers
  |                         |                            | validate accept/challenge
  |                         |<---------------------------| HttpThroughEvent.enable()
  |                         |                            | install WebSocketContext
  |                         |--------------------------->| WebSocketHandshakeEvent
```

客户端侧建议按以下顺序排查：

1. 先确认这次请求是否真的被识别为 websocket upgrade
2. 再确认 `requestPending` 是否被正确建立
3. 最后再看 101 失败是卡在状态码、header、accept 还是 sub-protocol 校验

### 5.9 分层异常模型

要正确理解调试信息，最好把当前实现里的异常分成三层，而不是笼统地看成“websocket 出错”。

Handshake 层异常：

- 处理对象仍是 `HttpObject`
- 典型结果是 reject、clear，部分致命路径会 close
- `WebSocketHandshakeException` 属于这一层的领域异常
- 服务端拒绝时当前代码中常见的 HTTP 状态如下：
  - `400 Bad Request`：请求片段类型不对、payload 非法、握手头不完整、授权等待期间又收到额外输入、协商结果非法。
  - `405 Method Not Allowed`：authorizer 直接调用默认 `reject()`。
  - `426 Upgrade Required`：版本不兼容，同时附带 `Sec-WebSocket-Version` 响应头。
  - `500 Internal Server Error`：authorizer 自身执行失败或显式拒绝为 500。
- 客户端校验 101 响应失败时，当前实现统一抛出带 `400 Bad Request` 的 `WebSocketHandshakeException`，用于标识“升级响应内容不符合预期”。

Frame 层异常：

- 处理对象是 `HttpByteBuf` 或 `WebSocketFrame`
- decoder/encoder 主要负责状态复位和日志
- frame codec 本身不直接决定 transport close
- 但 Frame 层和 Message 层抛出的 `WebSocketProtocolViolationException` 会携带一个明确的 closeStatusCode。

Message 层异常：

- 处理对象是 `WebSocketFrame` 或 `WebSocketMessage`
- 聚合超限、控制帧非法、UTF-8 非法等都在这一层体现
- 协议动作与事件动作分离，事件回调失败不回滚已完成的协议动作
- 入站侧捕获到 `WebSocketProtocolViolationException` 后，会把其中的 `closeStatusCode` 直接编码成 `CLOSE(code)` 回给对端。

当前实现里最重要的错误号映射如下：

- `1002(PROTOCOL_ERROR)`
  - `WebSocketFrameDecoder`：RSV 位非法、64 位长度最高位非法、opcode 非法、mask 方向错误。
  - `WebSocketUtils.validateControlFrame(...)`：控制帧分片、控制帧超过 125 字节、close payload 长度非法、close status code 非法。
  - `WebSocketInboundHandler` / `WebSocketOutboundHandler`：continuation 序列错误、消息类型与 sequence 不匹配、内部控制 opcode 非法。
- `1007(INVALID_DATA)`
  - `WebSocketInboundHandler`：TEXT 消息片段流或完整文本不是合法 UTF-8。
  - `WebSocketUtils.decodeUtf8(...)`：close reason 不是合法 UTF-8。
- `1009(MESSAGE_TOO_BIG)`
  - `WebSocketFrameDecoder`：单帧长度超过当前实现支持范围。
  - `WebSocketInboundHandler`：聚合消息长度超过 `maxMessagePayloadLength`。
- `1005(NO_STATUS)`
  - 仅表示收到的 close frame 没有携带状态码，当前实现把它作为 `WebSocketCloseEvent` 的本地值使用，不会主动发到线上。

`WebSocketCode` 中还定义了 `1000`、`1001`、`1003`、`1008`、`1010`、`1011` 等常量，但当前实现里并没有把它们作为常规自动错误响应码大量发出，更多是保留给协议语义表达或业务侧主动构造 close frame 使用。

三层异常模型可以用下面这个对照图理解：

```text
                    +-------------------------------+
                    |   Handshake Layer             |
                    | input : HttpObject            |
                    | error : reject / clear / close|
                    +-------------------------------+
                      |                |
                      |                +--> WebSocketHandshakeException
                      v
                    +-------------------------------+
                    |   Frame Layer                 |
                    | input : HttpByteBuf / Frame   |
                    | error : reset + log           |
                    +-------------------------------+
                      |                |
                      |                +--> protocol violation / encoder error
                      v
                    +-------------------------------+
                    |   Message Layer               |
                    | input : Frame / Message       |
                    | error : close / event split   |
                    +-------------------------------+
```

用一条时序来直观看异常是在哪一层被消费：

```text
Peer/App          Handshake          Frame Codec         Message Handler        Business
   |                  |                   |                    |                  |
   | bad upgrade      |                   |                    |                  |
   |----------------->| reject + clear    |                    |                  |
   | bad frame bytes  |                   |                    |                  |
   |------------------------------------->| reset + log        |                  |
   | bad close/text   |                   |                    |                  |
   |---------------------------------------------------------->| close / event    |
   | listener throws  |                   |                    |----------------->|
   |                  |                   |                    | keep protocol    |
```

调试时应先判定异常属于哪一层，因为三层的恢复动作完全不同：

- Handshake 层先看 reject、版本协商、request/response 片段是否对齐
- Frame 层先看 opcode、mask、payload header 和 decoder 状态
- Message 层先看 sequence、聚合阈值、控制帧和 UTF-8 语义

### 5.10 FrameDecoder 的内部工作方式

`WebSocketFrameDecoder` 的真实输入是握手成功、HTTP codec 进入 transparent mode 之后的 `HttpByteBuf`。

也就是：

```text
HttpByteBuf -> WebSocketFrame
```

它的内部处理过程可以简化成下面这个流式状态机：

```text
take HttpByteBuf
    -> append to CompositeByteBuf accumulator
    -> choose RFC6455 or V0 branch by version
    -> if header not enough
         wait for more bytes
    -> if payload not enough
         keep accumulator and continue waiting
    -> if one frame complete
         decode opcode / fin / mask / payload
         emit WebSocketFrame
```

如果按 RFC 6455 家族拆开看，解码步骤更接近：

```text
read first 2 bytes
    -> FIN
    -> opcode
    -> MASK
    -> payload length

if extended length exists
    -> read 2 or 8 bytes extended length

if masked
    -> read 4 bytes masking key

if payload incomplete
    -> wait for more bytes

if payload complete
    -> unmask if needed
    -> build concrete WebSocketFrame
```

如果是 V0/Hixie-76，则走的是另一套 framing 分支：

- 文本帧：`0x00 + payload + 0xFF`
- 关闭帧：`0xFF 0x00`
- 二进制帧：长度前缀模式

所以 `WebSocketFrameDecoder` 是一个典型的粘包、半包友好的流式帧解析器。

它的错误处理边界也要单独记住：

- `onError(...)` 主要负责 reset accumulator 和记录日志
- decoder 自身不直接决定是否关闭连接

因此 Frame 层异常和 Handshake 层异常在恢复语义上完全不同。

### 5.11 FrameEncoder 的内部工作方式

`WebSocketFrameEncoder` 的输入输出边界是：

```text
WebSocketFrame -> HttpByteBuf
```

它并不直接写 socket，而是把 frame 编成 transport byte buffer，再交给前面的 HTTP codec 在 transparent mode 下原样下发。

RFC 6455 家族的编码步骤可以概括为：

```text
read frame.opcode / finalFragment / masked / payload
    -> calculate header size
    -> write FIN + opcode
    -> write MASK + payload length
    -> write extended payload length if needed
    -> write masking key if needed
    -> mask payload if needed
    -> emit HttpByteBuf
```

V0/Hixie-76 路径则会根据 opcode 切到 text / binary / close 的专门分支。

编码器还有两个很容易忽略的实现边界：

- 数据所有权
  - 编码完成后，encoder 会释放输入 `WebSocketFrame`
  - 因此调用方不应再重复释放已经成功交给 encoder 的 frame
- 控制帧协议合法性边界
  - encoder 会继续负责与序列化直接相关的检查，例如 opcode、mask key、mask 方向
  - 但它不会全量兜底所有控制帧语义合法性

如果业务需要在发送前显式校验控制帧是否合法，应主动调用：

```java
WebSocketUtils.validateControlFrame(frame)
WebSocketUtils.validateControlFrame(frame, senderIsClient)
```

encoder 负责“能否被正确序列化”，而不是无条件替上层承担全部控制帧协议审计。

### 5.12 事件通道与消息通道边界

Message 层里最容易混淆的是“消息通道”和“事件通道”是两套不同传播路径。

消息通道：

- `ProtoRcvQueue`
- `ProtoSndQueue`
- 这里只承载 `WebSocketMessage`

事件通道：

- `fireEvent(...)`
- 这里传播的是 `WebSocketHandshakeEvent`、`WebSocketCloseEvent` 这类控制事件

边界可以用下面的图看清：

```text
                 +---------------------------+
                 |      Message Channel      |
                 | ProtoRcvQueue / SndQueue  |
                 | payload: WebSocketMessage |
                 +-------------+-------------+
                               |
                               v
                 +---------------------------+
                 |      Business Handler     |
                 +---------------------------+

                 +---------------------------+
                 |       Event Channel       |
                 |     fireEvent(...)    |
                 | payload: handshake/close  |
                 +---------------------------+
```

这意味着：

- `WebSocketOutboundHandler` 不从消息队列接收 `WebSocketCloseEvent`
- `CLOSE` 在很多场景下仍然更接近“控制帧 + 事件”语义，而不是普通业务消息
- 事件监听器抛异常时，不会回滚已经完成的协议动作

对应时序可以简化成：

```text
Peer/App          Message Handler                 Business Listener
   |                    |                                  |
   | protocol action    |                                  |
   |------------------->| execute close/pong/handshake     |
   |                    |------------------------------->  | fire network event
   |                    |                                  X listener throws
   |                    | log only, keep protocol result   |
```

所以调试时如果看到“事件失败但协议动作已经发生”，这通常表示当前实现有意保持协议动作与事件动作分离。

### 5.13 公开入口与底层部件的关系

Frame 层：

- `WebSocketFrameDuplexer` 是常规推荐入口
- `WebSocketFrameDecoder` / `WebSocketFrameEncoder` 是底层单向部件

Message 层：

- `WebSocketMessageDuplexer` 是常规推荐入口
- `WebSocketInboundHandler` / `WebSocketOutboundHandler` 是底层单向部件

可概括为：

```text
WebSocketFrameDuplexer   = WebSocketFrameDecoder   + WebSocketFrameEncoder
WebSocketMessageDuplexer = WebSocketInboundHandler + WebSocketOutboundHandler
```

### 5.14 关键辅助对象

除了握手器、frame codec、message handler 这些主链组件，还有几类对象值得单独记住：

- `WebSocketFrame`
  - 线协议帧对象
- `WebSocketMessage`
  - 应用消息对象，承载 text/binary 以及 sequence 语义
- `WebSocketHandshakeException`
  - handshake 领域异常
- `WebSocketCloseEvent`
  - close 事件通知对象
- `WebSocketContext`
  - 握手完成后挂到 `ProtoContext` / root context 的协商结果快照

调试时需要先区分协议帧、应用消息、上下文状态和领域异常这几类对象。

## 6. 三条关键流

### 6.1 数据流

```text
inbound

[Peer/App]
  |
  v
websocket bytes
  |
  v
[HTTP codec]
  |
  v
HttpByteBuf
  |
  v
[Handshake Duplexer]
  |
  v
[Frame Duplexer]
  |
  +--> WebSocketFrame
  |
  v
[Message Duplexer]
  |
  +--> WebSocketMessage
  |
  v
[Business Handler]


outbound

[Business Handler]
  |
  +--> WebSocketMessage
  |          or
  +--> WebSocketFrame
  |
  v
[Message Duplexer]
  |
  v
[Frame Duplexer]
  |
  v
HttpByteBuf
  |
  v
[Handshake Duplexer]
  |
  v
[HTTP codec]
  |
  v
websocket bytes
  |
  v
[Peer/App]
```

### 6.2 异常流

```text
输入对象或输入字节
  |
  +--> Handshake 层
  |      |
  |      +--> bad handshake
  |      +--> reject / clear
  |      `--> optional close
  |
  +--> Frame 层
  |      |
  |      +--> bad frame bytes
  |      +--> reset / log
  |      `--> bubble up
  |
  `--> Message 层
         |
         +--> bad message / control
         +--> close / event split
         +--> [HTTP codec / Channel] -> [Peer/App]
         `--> [Business / Listener]
```

常见触发点：

- 握手输入不是合法 HTTP request/response 片段
- 版本不兼容
- 掩码方向错误
- 无效 opcode
- 非法 continuation 序列
- 文本或 close reason 不是合法 UTF-8
- 控制帧长度超过 125
- 聚合消息超出限制

对应错误号可以直接按下面这组规则排查：

- 握手阶段：先看 HTTP 状态码，当前实现常见为 `400`、`405`、`426`、`500`
- 运行期协议格式错误：看 `CLOSE(1002)`
- UTF-8 校验失败：看 `CLOSE(1007)`
- 帧或聚合消息过大：看 `CLOSE(1009)`

### 6.3 事件流

```text
[Handshake Duplexer]
  |
  +--> fireEventSnd(HttpThroughEvent.enable())
  |      |
  |      v
  |   [HTTP codec]
  |      |
  |      `--> switch transparent mode
  |
  `--> fireEventRcv(WebSocketHandshakeEvent)
         |
         v
      [Business / Router]

[Inbound Handler]
  |
  +--> PING
  |      `--> auto send Pong reply -> [Peer/App]
  +--> PONG
  |      `--> publish Pong event -> [Business / Listener]
  `--> CLOSE
         +--> send close reply -> [Peer/App]
         `--> publish close event -> [Business / Listener]
```

### 6.4 一个完整视图

```text
                     inbound

websocket bytes
  |
  v
[HTTP codec]
  |
  v
HttpByteBuf
  |
  v
[Handshake Duplexer]
  |
  v
[Frame Duplexer]
  |
  +--> WebSocketFrame
  |
  v
[Message Duplexer]
  |
  +--> WebSocketMessage
  |
  v
[Business Handler]


                     outbound

[Business Handler]
  |
  +--> WebSocketMessage
  |          or
  +--> WebSocketFrame
  |
  v
[Message Duplexer]
  |
  v
[Frame Duplexer]
  |
  v
HttpByteBuf
  |
  v
[Handshake Duplexer]
  |
  v
[HTTP codec]
  |
  v
websocket bytes
```

这一张图的重点是把四层链路放到同一个总览里：

- HTTP Codec 层负责 Upgrade 前置协议和 transparent mode 切换
- Handshake 层负责协商、拒绝和上下文安装
- Frame 层负责 WebSocketFrame 和 HttpByteBuf 之间的转换
- Message 层负责业务可见的 WebSocketMessage 和控制事件

## 7. 关键编码器、解码器、聚合器说明

### 7.1 WebSocketServerHandshakeDuplexer

作用：

- 收集服务端入站握手请求
- 校验版本、payload、headers
- 调用 authorizer
- 输出 101 或 reject
- 安装 WebSocketContext 并发布握手完成事件

关键参数：

```java
new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13)
new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13, authorizer)
```

何时使用：

- 所有服务端 WebSocket pipeline 都需要它

错误响应：

- `400 Bad Request`：握手输入类型不对、payload 非法、握手头不完整、协商结果非法
- `405 Method Not Allowed`：authorizer 默认 reject
- `426 Upgrade Required`：版本不兼容，并返回 `Sec-WebSocket-Version`
- `500 Internal Server Error`：authorizer 执行失败或显式拒绝为 500

### 7.2 WebSocketClientHandshakeDuplexer

作用：

- 发送或接收客户端升级请求
- 校验 101 响应
- 安装客户端 WebSocketContext

关键参数：

```java
new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)
new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13, autoConfig)
```

何时使用：

- 所有客户端 WebSocket pipeline 都需要它

错误响应校验：

- 当前实现把 101 校验失败统一归入 `WebSocketHandshakeException(400)`
- 典型触发点包括：不是 `101 Switching Protocols`、缺少 `Connection: Upgrade`、缺少 `Upgrade: websocket`、`Sec-WebSocket-Accept` 不匹配、子协议非法、协商到了未启用的扩展、协商结果包含重复扩展、扩展参数非法

### 7.3 WebSocketFrameDuplexer

作用：

- 组合 WebSocketFrameDecoder 和 WebSocketFrameEncoder

关键参数：

```java
new WebSocketFrameDuplexer(WebSocketVersion.V13)
new WebSocketFrameDuplexer(WebSocketVersion.V13, 16 * 1024)
```

参数含义：

- version：frame 编解码所使用的协议版本
- maxPayloadChunkLength：超大单帧接收时的切片阈值

### 7.4 WebSocketFrameDecoder

作用：

- 解析 RFC 6455 或 V0 分帧格式
- 校验 RSV、mask 方向、opcode、payload length
- 必要时把大单帧切成等价的分片序列

关键参数：

```java
new WebSocketFrameDecoder(WebSocketVersion.V13)
new WebSocketFrameDecoder(WebSocketVersion.V13, 16 * 1024)
new WebSocketFrameDecoder()
new WebSocketFrameDecoder(16 * 1024)
```

参数含义：

- 无参构造：优先使用握手协商出的版本，默认回退到 V13
- maxPayloadChunkLength：decoder 的流式切片阈值，必须大于 0

异常码：

- `1002(PROTOCOL_ERROR)`：RSV 非法、opcode 非法、mask 方向错误、64 位长度最高位非法
- `1009(MESSAGE_TOO_BIG)`：单帧长度超过当前实现支持范围

### 7.5 WebSocketFrameEncoder

作用：

- 把 WebSocketFrame 编码成 HttpByteBuf
- 按角色决定是否掩码
- 写出 7/16/64 位长度头

关键参数：

```java
new WebSocketFrameEncoder(WebSocketVersion.V13)
new WebSocketFrameEncoder()
```

### 7.6 WebSocketMessageDuplexer

作用：

- 组合 WebSocketInboundHandler 和 WebSocketOutboundHandler
- 为业务层提供 message 视角的双工入口

关键参数：

```java
new WebSocketMessageDuplexer()
new WebSocketMessageDuplexer(true)
new WebSocketMessageDuplexer(true, 1024 * 1024)
new WebSocketMessageDuplexer(16 * 1024)
new WebSocketMessageDuplexer(true, 1024 * 1024, 16 * 1024)
```

参数含义：

- aggregateFragments：是否在入站侧聚合分片消息
- maxMessagePayloadLength：聚合后的单条消息长度上限
- maxFramePayloadLength：出站自动分帧阈值

### 7.7 WebSocketInboundHandler

作用：

- 处理入站 TEXT/BINARY/CONTINUATION
- 自动处理 Ping/Pong/Close 协议语义
- 可选聚合分片消息

关键参数：

```java
new WebSocketInboundHandler()
new WebSocketInboundHandler(true)
new WebSocketInboundHandler(true, 1024 * 1024)
```

注意点：

- 默认不聚合
- 协议违规时会把 `WebSocketProtocolViolationException.closeStatusCode()` 直接转成 close reply
- continuation、控制帧、close payload、UTF-8 校验等错误默认分别映射到 `1002`、`1007`、`1009`
- 聚合超限时会返回 CLOSE(1009)

### 7.8 WebSocketOutboundHandler

作用：

- 处理出站消息 sequence
- 可选自动分帧
- 生成 Ping/Pong/Close 控制帧

关键参数：

```java
new WebSocketOutboundHandler()
new WebSocketOutboundHandler(16 * 1024)
```

注意点：

- maxFramePayloadLength 为 0 表示关闭自动分帧
- 如果业务已经自己按 sequence 分片，handler 会尊重现有 sequence，而不是再次重写它

## 8. 使用注意事项

### 8.1 握手前后输入类型不同

常见误用如下：

- 握手前是 HttpObject
- 握手后是 HttpByteBuf

不要把握手成功后的数据还当成普通 HTTP 内容处理。

### 8.2 HTTP + WebSocket 混用时，路由边界必须正确

常见错误做法如下：

- 把 HttpRequest 放进 HTTP 分支
- 但把后续 HttpHeaders 或 HttpContent 留在 WebSocket 分支

这样会直接破坏握手聚合。

### 8.3 默认消息处理方式不是“完整消息模式”

默认消息处理方式保留的是 sequence 片段流，而不是自动聚合后的整条消息。若业务层只接受完整消息，需要显式开启 aggregateFragments。

### 8.4 超大单帧输入要显式设置流式切片阈值

`maxPayloadChunkLength` 是 Frame 层参数，不是另一种独立协议栈。

如果对端可能发来一个非常大的单帧，而本端需要按切片方式流式消费，需要显式设置：

```java
new WebSocketFrameDecoder(WebSocketVersion.V13, 16 * 1024)
new WebSocketFrameDuplexer(WebSocketVersion.V13, 16 * 1024)
```

设置后语义是：

- 当单帧 payload 超过 `maxPayloadChunkLength` 时，decoder 会把它切成等价的分片序列。
- 首片保留原始 opcode。
- 后续片使用 CONTINUATION。
- Message 层因此可以继续按非聚合方式消费这些片段。

如果没有这个设置，业务层不能把“超大单帧输入”直接理解成“默认会被平滑拆成多个消息对象”。

### 8.5 注意对象所有权和释放边界

WebSocketFrameEncoder、WebSocketInboundHandler、WebSocketOutboundHandler、WebSocketMessageDuplexer 都会接管它们消费对象的生命周期。如果上层代码要缓存内容或跨线程使用，需要先 retain 或复制。

如果还要进一步看 ByteBuf 与 handler 的引用边界，可以继续参考 [引用所有权](../../principle/handler/ownership.md)。

如果还想看实现细节、边界案例、FAQ 风格的补充说明，可以继续阅读 faq 目录下的 WEBSOCKET 文档。
