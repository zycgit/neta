---
sidebar_position: 6
title: WebSocket
description: 说明 Neta 在 WebSocket 上的支持范围、典型装配方式、内部工作机制、关键数据流和使用注意事项。
---

本文是 Neta WebSocket 支持的正式说明文档，面向两类读者：

- 需要组装服务端、客户端或 HTTP 混合链路的使用者
- 需要定位握手、frame、message 三层行为边界的维护者

正文按“先边界、再装配、再机制、最后参考”的顺序展开：

- 第 1 章说明 WebSocket 在 Neta 中的整体定位
- 第 2 章说明已支持能力、能力边界和当前限制
- 第 3 章到第 4 章说明组件分层、选型结论和典型装配方式
- 第 5 章到第 6 章说明内部机制以及数据流、异常流、事件流
- 第 7 章到第 8 章提供组件参考和实际使用注意事项

阅读时可以直接按目标进入对应章节：

- 关注是否支持某项协议能力，读取第 2 章
- 关注服务端、客户端或混合场景如何装配，读取第 3 章和第 4 章
- 关注握手失败、close code、事件传播和内部状态机，读取第 5 章和第 6 章
- 关注具体构造器、参数和限制，读取第 7 章和第 8 章

## 1. 简介

WebSocket 是建立在 HTTP Upgrade 之上的全双工长连接协议。它的目标很简单：

- 先通过 HTTP/1.x 完成升级协商。
- 握手成功后切换到持续连接。
- 双方都可以主动发送消息。
- 消息既可以是文本，也可以是二进制，还支持 Ping、Pong、Close 等控制帧。

Neta 对 WebSocket 的支持位于 neta-codec-http 模块中，整体上分成 3 层：

- 握手层：处理 handshake、校验、拒绝响应、transparent mode 切换。
- frame 层：处理 WebSocketFrame 和 HttpByteBuf 之间的双向转换。
- message 层：处理 WebSocketMessage、消息片段流、控制事件、可选聚合和可选自动分帧。

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- handshake
  - 服务端握手：WebSocketServerHandshakeDuplexer
  - 客户端握手：WebSocketClientHandshakeDuplexer
  - 客户端 HTTP + WebSocket 混合升级：WebSocketClientUpgradeRouteDuplexer
- 协议版本
  - RFC 6455 家族：V7、V8、V13
  - 兼容旧版 V0/Hixie-76 framing
- frame 层编解码
  - TEXT、BINARY、CONTINUATION、PING、PONG、CLOSE
  - 客户端掩码与服务端非掩码方向校验
  - 16 位和 64 位扩展长度解析
- message 层能力
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
  - 通过 HttpThroughEvent.enable() 通知 HTTP codec 进入 transparent mode

### 2.2 能力边界

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

### 2.3 当前明确限制

- WebSocket 扩展协商结果目前不被接受。
  - 握手代码会记录请求中的扩展信息。
  - 但如果协商结果中出现 Sec-WebSocket-Extensions，当前实现会把它视为不支持并拒绝升级。
- 当前主要面向 HTTP/1.x Upgrade 场景。
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
HTTP codec 层
  负责 HTTP/1.x 解析、编码，以及 transparent mode 切换

WebSocket 握手层
  负责 handshake、HTTP reject、上下文安装、事件发布

WebSocket frame 层
  负责 WebSocketFrame <-> HttpByteBuf

WebSocket message 层
  负责 WebSocketMessage、消息片段流、控制事件、聚合与自动分帧
```

### 3.2 推荐入口

常规场景优先使用双工器，而不是直接拼底层部件：

- frame 层推荐：WebSocketFrameDuplexer
- message 层推荐：WebSocketMessageDuplexer

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
| WebSocketMessageDuplexer | 消息层双工入口 | 常规业务收发消息 |
| WebSocketInboundHandler | frame -> message/event | 定制入站聚合或事件处理 |
| WebSocketOutboundHandler | message/event -> frame | 定制出站序列或自动分帧 |

## 4. 使用方式

这一节重点说明组装形态和适用范围。

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
- 后续 websocket 数据进入 frame 层，再进入 message 层。

### 4.2 服务端：HTTP + WebSocket 混用端口

适用范围：

- 同一端口既处理普通 HTTP，也处理 WebSocket
- 适合统一入口服务或管理端口

推荐思路：

- 外层路由先区分 HTTP 和 WebSocket 分支。
- WebSocket 分支内部再区分 handshake 阶段和 socket 阶段。
- 握手成功后，通过 WebSocketHandshakeEvent 切换到 websocket codec 链。

核心原则只有两条：

- handshake 的整段 HttpObject 流必须留在 websocket 分支。
- 握手成功后的 HttpByteBuf 也必须继续落在 websocket 分支。

一个完整的服务端混用端口写法可以写成这样：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("http-agg", new HttpServerDuplexeAggregator(1024 * 1024));

ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
  WebSocketContext webSocketContext = context.rootContext(WebSocketContext.class, null);
  if (webSocketContext != null && webSocketContext.isReady()) {
    return HttpRouteKey.BRANCH_SOCKET;
  }
  if (rcvUp.queueSize() == 0) {
    return null;
  }
  HttpObject object = rcvUp.peekMessage();
  if (object instanceof FullHttpRequest) {
    FullHttpRequest request = (FullHttpRequest) object;
    String upgrade = request.getString(HttpHeaderNames.UPGRADE);
    if (upgrade != null && HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade)) {
      return HttpRouteKey.BRANCH_SOCKET;
    }
    return "http";
  }
  if (object instanceof HttpByteBuf) {
    return HttpRouteKey.BRANCH_SOCKET;
  }
  return "http";
});

routing.branch("http", branch -> {
  branch.nextDecoder("http-handler", httpHandler);
});

routing.branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
  ProtoRoutingBuilder<HttpObject, HttpObject> webSocketRouting =
      ProtoHelper.typedRoutingAsStatic((innerContext, innerRcvUp, innerSndDown) -> "handshake");

  webSocketRouting.branchByInitializer("handshake", innerBranchCtx -> {
    innerBranchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
    innerBranchCtx.addLastDecoder("ws-route-switch", new ThroughProtoHandler<HttpObject>() {
      @Override
      public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
        if (event.getData() instanceof WebSocketHandshakeEvent) {
          ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
          if (routingControl != null) {
            routingControl.switchRoute(HttpRouteKey.BRANCH_SOCKET);
          }
        }
        return true;
      }
    });
  });

  webSocketRouting.branchByInitializer(HttpRouteKey.BRANCH_SOCKET, innerBranchCtx -> {
    innerBranchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
    innerBranchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
    innerBranchCtx.addLast("ws-handler", webSocketHandler);
  });

  branchCtx.addLast("ws-route", webSocketRouting.build());
});

ctx.addLast("server-route", routing.build());
```

这个模式核心思路是：

- 普通 HTTP 流量先留在默认 HTTP 分支。
- 只有 upgrade 请求才进入 websocket 分支。
- 握手成功后，不是动态重拼整条主链，而是切换到握手后的 websocket codec 链。

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

ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault("http", branchCtx -> {
  branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplexer(WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
  branchCtx.addLastDecoder("resp-agg", new HttpResponseAggregator());
}).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
  branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
  branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
  branchCtx.addLastDecoder("ws-handler", wsHandler);
});

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
channel.sendData(DefaultLastHttpContent.EMPTY).get();
handshake.release();
```

这个模式的关键点是：

- 普通 HTTP 请求继续走默认 HTTP 分支。
- 只有 upgrade 请求会被 `WebSocketClientUpgradeRouteDuplexer` 接管。
- 握手成功后，当前路由切到 websocket 分支，后续消息就不再按普通 HTTP 处理。

### 4.6 只用 frame 层

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

### 4.7 入站聚合

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

如果不使用 `WebSocketMessageDuplexer`，也可以只挂入站 handler，在 frame 层后面显式打开聚合：

```java
new WebSocketInboundHandler(true)
new WebSocketInboundHandler(true, 1024 * 1024)
```

语义：

- 同一条分片的 TEXT 或 BINARY 消息，会在 final continuation 到达后再输出。
- 超过 maxMessagePayloadLength 时，会走 CLOSE(1009) 即 MESSAGE_TOO_BIG。

### 4.8 出站自动分帧

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

因为 WebSocket 不是直接跑在裸 socket 上的独立首包协议，而是先通过 HTTP Upgrade 建立。当前实现的关键切换点是：

- 握手前：流里是 HttpObject
- 握手后：HTTP codec 切成 transparent mode，流里变成 HttpByteBuf
- 然后 frame decoder 再把 HttpByteBuf 解释成 WebSocketFrame

### 5.3 message 层的默认非聚合语义

默认不聚合的原因很实际：

- 这最贴近 WebSocket 原生分片语义
- 业务可以自己决定是流式消费还是自定义聚合
- 对大对象传输更安全，不会默认吃掉一整条大消息的内存

### 5.4 超大单帧的流式切片策略

原因也很直接：

- 协议头里的扩展长度字段需要按 64 位规则解析
- 但对外的 payloadLength 与各类 message/frame 长度阈值已经统一为 int
- 底层 ByteBuf 读写和索引本身也仍然是 int 模型
- 所以对超大单帧最稳妥的实现不是试图构造超大单块缓冲，而是按片段序列输出

这也是 WebSocketFrameDecoder 新增 maxPayloadChunkLength 的意义。

### 5.5 服务端握手的 HTTP 片段流处理方式

服务端握手器不是建立在 `FullHttpRequest` 之上，而是建立在 HTTP 片段流之上：

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

因此，新文里的 mixed 场景虽然常用 `FullHttpRequest` 做路由判定，但握手器本身的内部语义仍然是“HTTP 片段流握手处理器”，而不是“只接受一整块 full request 的升级器”。

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

服务端握手器的放行规则可以直接记成：

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

容易误解的点：`WebSocketClientUpgradeRouteDuplexer` 不是“观察到 upgrade 就顺便切路由”，而是先让客户端握手器完整拦截一次 upgrade 事务，等 `requestPending -> ready` 后再切到 websocket 分支。

### 5.8 客户端对 101 响应的校验过程

客户端收到 101 后，并不是只检查状态码，而是按版本做一整套校验。

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

因此客户端侧最稳妥的排错顺序通常是：

1. 先确认这次请求是否真的被识别为 websocket upgrade
2. 再确认 `requestPending` 是否被正确建立
3. 最后再看 101 失败是卡在状态码、header、accept 还是 sub-protocol 校验

### 5.9 分层异常模型

要正确理解调试信息，最好把当前实现里的异常分成三层，而不是笼统地看成“websocket 出错”。

握手层异常：

- 处理对象仍是 `HttpObject`
- 典型结果是 reject、clear，部分致命路径会 close
- `WebSocketHandshakeException` 属于这一层的领域异常
- 服务端拒绝时当前代码中最常见的 HTTP 状态是：
  - `400 Bad Request`：请求片段类型不对、payload 非法、握手头不完整、授权等待期间又收到额外输入、协商结果非法。
  - `405 Method Not Allowed`：authorizer 直接调用默认 `reject()`。
  - `426 Upgrade Required`：版本不兼容，同时附带 `Sec-WebSocket-Version` 响应头。
  - `500 Internal Server Error`：authorizer 自身执行失败或显式拒绝为 500。
- 客户端校验 101 响应失败时，当前实现统一抛出带 `400 Bad Request` 的 `WebSocketHandshakeException`，用于标识“升级响应内容不符合预期”。

frame 层异常：

- 处理对象是 `HttpByteBuf` 或 `WebSocketFrame`
- decoder/encoder 主要负责状态复位和日志
- frame codec 本身不直接决定 transport close
- 但 frame 和 message 层抛出的 `WebSocketProtocolViolationException` 会携带一个明确的 closeStatusCode。

message 层异常：

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

调试时最重要的不是只看“有没有异常”，而是先判定异常属于哪一层，因为三层的恢复动作完全不同：

- 握手层先看 reject、版本协商、request/response 片段是否对齐
- frame 层先看 opcode、mask、payload header 和 decoder 状态
- message 层先看 sequence、聚合阈值、控制帧和 UTF-8 语义

### 5.10 FrameDecoder 的内部工作方式

`WebSocketFrameDecoder` 的真实输入不是任意 `HttpObject`，而是握手成功、HTTP codec 进入 transparent mode 之后的 `HttpByteBuf`。

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

所以 `WebSocketFrameDecoder` 不是“按包读取”的解码器，而是一个典型的“粘包/半包友好”的流式帧解析器。

它的错误处理边界也要单独记住：

- `onError(...)` 主要负责 reset accumulator 和记录日志
- decoder 自身不直接决定是否关闭连接

因此 frame 层异常和握手层异常在恢复语义上完全不同。

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

也就是说，encoder 负责“能否被正确序列化”，而不是无条件替上层承担全部控制帧协议审计。

### 5.12 事件通道与消息通道边界

message 层里最容易混淆的是“消息通道”和“事件通道”是两套不同传播路径。

消息通道：

- `ProtoRcvQueue`
- `ProtoSndQueue`
- 这里只承载 `WebSocketMessage`

事件通道：

- `fireUserEvent(...)`
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
                 |     fireUserEvent(...)    |
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
   |                    |------------------------------->  | fire user event
   |                    |                                  X listener throws
   |                    | log only, keep protocol result   |
```

所以调试时如果看到“事件失败但协议动作已经发生”，这通常不是 bug，而是当前实现有意保持的边界。

### 5.13 公开入口与底层部件的关系

frame 层：

- `WebSocketFrameDuplexer` 是常规推荐入口
- `WebSocketFrameDecoder` / `WebSocketFrameEncoder` 是底层单向部件

message 层：

- `WebSocketMessageDuplexer` 是常规推荐入口
- `WebSocketInboundHandler` / `WebSocketOutboundHandler` 是底层单向部件

可以直接记成：

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
  +--> 握手层
  |      |
  |      +--> bad handshake
  |      +--> reject / clear
  |      `--> optional close
  |
  +--> frame 层
  |      |
  |      +--> bad frame bytes
  |      +--> reset / log
  |      `--> bubble up
  |
  `--> message 层
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
  +--> fireUserEventSnd(HttpThroughEvent.enable())
  |      |
  |      v
  |   [HTTP codec]
  |      |
  |      `--> switch transparent mode
  |
  `--> fireUserEventRcv(WebSocketHandshakeEvent)
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

- HTTP codec 负责 Upgrade 前置协议和 transparent mode 切换
- 握手层负责协商、拒绝和上下文安装
- frame 层负责 WebSocketFrame 和 HttpByteBuf 之间的转换
- message 层负责业务可见的 WebSocketMessage 和控制事件

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
- 典型触发点包括：不是 `101 Switching Protocols`、缺少 `Connection: Upgrade`、缺少 `Upgrade: websocket`、`Sec-WebSocket-Accept` 不匹配、子协议非法、扩展协商结果非空

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

这是最常见的误用来源：

- 握手前是 HttpObject
- 握手后是 HttpByteBuf

不要把握手成功后的数据还当成普通 HTTP 内容处理。

### 8.2 HTTP + WebSocket 混用时，路由边界必须正确

错误做法通常是：

- 把 HttpRequest 放进 HTTP 分支
- 但把后续 HttpHeaders 或 HttpContent 留在 WebSocket 分支

这样会直接破坏握手聚合。

### 8.3 默认消息处理方式不是“完整消息模式”

默认消息处理方式保留的是 sequence 片段流，而不是自动聚合后的整条消息。若业务层只接受完整消息，需要显式开启 aggregateFragments。

### 8.4 超大单帧输入要显式设置流式切片阈值

`maxPayloadChunkLength` 是 frame 层参数，不是另一种独立协议栈。

如果对端可能发来一个非常大的单帧，而本端需要按切片方式流式消费，需要显式设置：

```java
new WebSocketFrameDecoder(WebSocketVersion.V13, 16 * 1024)
new WebSocketFrameDuplexer(WebSocketVersion.V13, 16 * 1024)
```

设置后语义是：

- 当单帧 payload 超过 `maxPayloadChunkLength` 时，decoder 会把它切成等价的分片序列。
- 首片保留原始 opcode。
- 后续片使用 CONTINUATION。
- message 层因此可以继续按非聚合方式消费这些片段。

如果没有这个设置，业务层不能把“超大单帧输入”直接理解成“默认会被平滑拆成多个消息对象”。

### 8.5 注意对象所有权和释放边界

WebSocketFrameEncoder、WebSocketInboundHandler、WebSocketOutboundHandler、WebSocketMessageDuplexer 都会接管它们消费对象的生命周期。如果上层代码要缓存内容或跨线程使用，需要先 retain 或复制。

如果还要进一步看 ByteBuf 与 handler 的引用边界，可以继续参考 [引用所有权](../principle/ownership.md)。

如果还想看实现细节、边界案例、FAQ 风格的补充说明，可以继续阅读 faq 目录下的 WEBSOCKET 文档。
