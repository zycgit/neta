---
sidebar_position: 8
title: HTTP/2
description: 说明 Neta 在 HTTP/2 上的四层对象模型、编解码、控制消息、duplexe 装配、h2c 升级路径和使用注意事项。
---

## 1. 简介

Neta 在 neta-codec-http 模块中提供一套分层的 HTTP/2 codec 与适配器。当前实现保留了协议层的中间结构，底层调试、协议桥接和高层 HTTP 适配都落在同一组组件上。

当前实现明确采用 4 层模型：

- ByteBuf 层：负责连接前言和原始字节输入输出
- frame 层：负责 9 字节帧头和 payload 的二进制编解码
- message 层：负责 HEADERS、DATA 以及 SETTINGS、PING 等协议语义消息
- HttpObject 层：负责把请求和响应语义映射成通用 HTTP 对象

这套实现主要解决四类问题：

- 如何把原始 ByteBuf 稳定解析成 HTTP/2 frame
- 如何把一个或多个 frame 还原成可操作的协议语义消息
- 如何在需要时把 HTTP/2 请求和响应适配回 Neta 的通用 HttpObject 模型
- 如何在服务端自动处理 server preface、SETTINGS ACK、PING ACK、WINDOW_UPDATE 和 stream reset 等协议动作

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- 协议接入方式
  - h2 prior knowledge 明文链路
  - HTTP/1.1 -> h2c upgrade 服务端桥接
  - TLS 握手完成后的 ALPN 路由分支接入
- frame 层能力
  - 连接前言校验
  - 9 字节帧头编解码
  - DATA、HEADERS、CONTINUATION、SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 等帧的基础收发
- message 层能力
  - HEADERS header block 解码与 CONTINUATION 重组
  - DATA 内容块解码
  - SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 的协议处理与事件转换
  - 按 RFC 约束校验 frame target、control payload 和 header block 连续性
  - 按对端 `SETTINGS_MAX_FRAME_SIZE` 拆分 HEADERS 和 DATA 的出站分片
  - HPACK header block 编解码
- HttpObject 适配能力
  - HttpRequest 或 HttpResponse、HttpHeaders、HttpContent、LastHttpContent 的双向适配
  - FullHttpRequest、FullHttpResponse 的整对象适配
- 双工入口
  - Http2FrameDuplexe：ByteBuf &lt;-&gt; Http2Frame &gt;
  - Http2ObjectDuplexe：Http2Frame &lt;-&gt; HttpObject
  - H2cUpgradeServerDuplexe：HTTP/1.1 与 HTTP/2 的服务端升级桥
- 协议自动处理能力
  - 服务端首次收包时发送 server SETTINGS preface
  - 收到 SETTINGS 后自动排队 SETTINGS ACK
  - 收到 PING 后自动排队 PING ACK
  - 收到 DATA 后自动排队连接级和 stream 级 WINDOW_UPDATE
  - 通过网络事件或编码错误路径发送 RST_STREAM

### 2.2 能力边界

当前这套 HTTP/2 组件负责：

- 原始字节、frame、HttpObject 之间的逐层转换
- client 和 server 两侧共享同一个 HTTP/2 语义入口
- HPACK header block 编解码
- h2 prior knowledge 与 h2c upgrade 这两条已落地链路
- 一部分协议控制消息的自动维护

当前这套 HTTP/2 组件不负责：

- TLS 建链、ALPN 协商和证书管理（需要搭配 SSL 编码器/解码器）
- 路由、控制器、订阅、应用层重试和业务级流控策略
- WebSocket over HTTP/2 的 bootstrap 和 stream-scope 会话模型
- 服务端推送模型本身

要特别区分两件事：

- Http2ObjectDuplexe 是 HTTP/2 的协议语义层，但它现在已经直接把 payload 暴露为 HttpObject。
- SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY、PRIORITY、PUSH_PROMISE 不会继续映射成通用 HTTP 请求响应对象，而是由协议层内部维护或转换成 HTTP/2 事件。

### 2.3 当前明确限制

- 当前文档说明的是 neta-codec-http 中已实现且已有明确入口的 HTTP/2 组件，不覆盖未来的 HTTP/2 WebSocket 扩展场景
- 当前主打 cleartext HTTP/2 场景
  - 一类是 prior knowledge
  - 一类是 HTTP/1.1 h2c upgrade
- PRIORITY 和 PUSH_PROMISE 现在会在 message 层被解析为 HTTP/2 事件，但仍不会继续映射到 HttpObject
- SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 在 message 层会被内部维护、自动补帧或转换成事件，而不是继续作为公开下游消息
- 控制消息的自动维护主要实现在 Http2ObjectDuplexe 内部的 decoder/encoder 状态机
  - 自动发送不等于业务层一定能直接看到相同对象
- Header 压缩依赖 HPACK，当前默认表大小为 4096，默认 header list 上限为 8192
- 连接级和 stream 级流控窗口依赖当前实现的窗口模型，不等同于完整应用层 back pressure 方案

## 3. 组件分层

### 3.1 整体分层

Neta 当前 HTTP/2 明确采用下面这张三层模型图：

```text
socket bytes / h2c upgrade bytes
  -> Http2FrameDuplexe
  -> Http2Frame

Http2Frame
  -> Http2ObjectDuplexe
  -> HttpObject

HttpObject
  -> HttpRequestAggregator / HttpResponseAggregator
  -> FullHttpRequest / FullHttpResponse
```

如果把角色入口也算进去，常见链路可以再压缩成这样：

```text
Client / Server Socket
  -> Http2FrameDuplexe
  -> Http2ObjectDuplexe
  -> HttpObject 或 FullHttpRequest / FullHttpResponse
```

### 3.2 哪些消息会在哪一层出现

这一节定义各层的输入输出对象和可见消息边界。

| 层 | 输入输出对象 | 这一层会出现什么 | 不会继续出现什么 |
| --- | --- | --- | --- |
| ByteBuf 层 | ByteBuf | client preface、原始帧字节、h2c upgrade 前后的原始网络数据 | 不直接表达 HTTP/2 语义 |
| frame 层 | Http2Frame | DATA、HEADERS、CONTINUATION、SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY、PRIORITY、PUSH_PROMISE | 不做 header block 语义还原 |
| message 层 | HttpObject + Http2 事件 | HttpRequest、HttpResponse、HttpHeaders、HttpContent、LastHttpContent，以及 ping/pong、reset、goaway 等事件信号 | SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 不再作为公开下游消息 |
| HttpObject 层 | HttpObject | HttpRequest、HttpResponse、HttpHeaders、HttpContent、LastHttpContent | SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 不会自动映射成 HttpObject |

这一点可概括为一句话：

- frame 层看见的是“线上帧”
- message 层看见的是“协议语义消息”
- HttpObject 层看见的是“请求响应对象”

### 3.3 推荐入口

常规场景优先选择这些公开入口：

- 只做底层二进制调试时：Http2FrameDuplexe
- 需要观察协议控制消息时：Http2ObjectDuplexe
- 做 HTTP/1.1 到 h2c 的升级桥接时：H2cUpgradeServerDuplexe
- 业务层只想处理请求和响应时：按 `Http2FrameDuplexe -> Http2ObjectDuplexe` 装配，再按需要加 aggregator

只有在这些场景才建议直挂底层部件：

- 只测试 frame 层或 message 层某一段行为
- 需要精确观察控制帧或控制消息
- 做跨协议桥接，想保留协议中间层而不是立刻变成 HttpObject

### 3.4 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| Http2FrameDecoder / Http2FrameEncoder | ByteBuf 和 Http2Frame 之间的二进制编解码 | 只测帧头、payload、preface |
| Http2FrameDuplexe | frame 层双工入口 | frame 层调试、VirtualPipe 测试 |
| Http2ObjectDecoder / Http2ObjectEncoder | Http2Frame 和 HttpObject 之间的语义编解码 | 协议层调试、控制消息观测 |
| Http2ObjectDuplexe | HTTP/2 语义层双工入口 | 直接处理 HttpObject，同时保留 HTTP/2 事件 |
| H2cUpgradeServerDuplexe | HTTP/1.1 与 HTTP/2 的升级桥 | 服务端 h2c upgrade |
| Http2OverTlsRoute | TLS 握手完成后按 ALPN 结果选择 H1 或 H2 分支 | 纯 ALPN 分流 |
| HttpAggregatorOverTlsRoute | TLS 握手后汇聚 ALPN 与 HTTP 明文探测结果 | HTTPS 端口复用 |
| Http2Context | 连接级 HTTP/2 状态视图 | 观测 readiness、窗口、stream 状态 |

### 3.5 选型结论

- 常规业务优先显式使用 `Http2FrameDuplexe -> Http2ObjectDuplexe` 两层装配
- 业务层只关心请求响应时，直接在 message 层后接 `HttpRequestAggregator`、`HttpResponseAggregator` 或 duplex aggregator
- 需要保留协议层控制消息时，不要直接下沉到 HttpObject 层
- 需要 h2c upgrade 时，优先使用 H2cUpgradeServerDuplexe，而不是自己手工桥接 HTTP/1.1 和 HTTP/2 状态切换
- 需要 HTTPS 单端口同时承载 HTTP/1.1、HTTP/2 prior knowledge 和 h2c upgrade 时，优先使用 `SslDuplexer + HttpAggregatorOverTlsRoute`

## 4. 使用方式

### 4.1 服务端：HTTP/2 prior knowledge

适用范围：

- 连接建立后直接进入 HTTP/2
- 不需要先跑 HTTP/1.1 Upgrade
- 适合协议检测后分支到 h2 链路

推荐装配：

```java
ctx.addLast("h2-frame", new Http2FrameDuplexe(true));
ctx.addLast("h2-object", new Http2ObjectDuplexe(true));
ctx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- Http2FrameDuplexe 和 Http2ObjectDuplexe 负责 server 侧协议入口
- Http2ObjectDuplexe 已经直接把 payload 暴露为 HttpObject
- 再往后是否聚合，由聚合器决定

### 4.2 客户端：HTTP/2 prior knowledge

适用范围：

- 客户端连接建立后直接按 HTTP/2 发送请求
- 适合 cleartext prior knowledge 场景

推荐装配：

```java
ctx.addLast("h2-frame", new Http2FrameDuplexe(false));
ctx.addLast("h2-object", new Http2ObjectDuplexe(false));
ctx.addLast("h2-aggregator", new HttpClientDuplexeAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- 客户端第一批出站消息会先带上 client preface 与 SETTINGS
- Request 和 Response 最终仍然可以回到通用 HTTP 对象模型

### 4.3 服务端：HTTP/1.1 h2c upgrade

适用范围：

- 端口同时接收 HTTP/1.1 和 h2c upgrade
- 需要在同一服务端 pipeline 里完成升级桥接

推荐装配：

```java
ProtoRoutingBuilder<ByteBuf, ByteBuf> routing = ProtoHelper.typedRoutingAsStatic(new HttpAggregatorRoute());
ProtoRoutingControl routingControl = routing.control();

routing.branchByInitializer(HttpRouteKey.BRANCH_H1, branch -> {
  branch.addLast("http-codec", new HttpServerDuplexe());
  branch.addLast("h1-upgrade", new H2CUpgradeServerDuplexe(routingControl));
  branch.addLastDecoder("http-aggregator", new HttpRequestAggregator(1024 * 1024));
  branch.addLastDecoder("http-handler", httpHandler);
});

routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, branch -> {
  branch.addLast("http-codec", new HttpServerDuplexe());
  branch.addLast("h2c-upgrade", new H2cUpgradeServerDuplexe(routingControl));
});

routing.branchByInitializer(HttpRouteKey.BRANCH_H2, branch -> {
  branch.addLast("h2-frame", new Http2FrameDuplexe(true));
  branch.addLast("h2-object", new Http2ObjectDuplexe(true, routingControl));
  branch.addLastDecoder("h2-handler", appHandler);
});
```

关键点：

- 如果希望“同一连接先处理普通 HTTP/1.1，后续再发起 upgrade”，H1 分支里也要挂 `H2CUpgradeServerDuplexe`
- 这里应继续使用静态路由，由 `H2CUpgradeServerDuplexe` 通过 `ProtoRoutingControl.switchRoute(...)` 完成从 H1 到 H2 的切换
- 升级前只需要走 HTTP/1.1 codec，`H2cUpgradeServerDuplexe` 会在内部缓存 staged request parts 并完成升级判定
- 收到合法 Upgrade: h2c 和 HTTP2-Settings 后，桥接器会发送 101，再通过默认 immediate 的 `switchRoute(target, seed)` 触发切换到 `Http2FrameDuplexe -> Http2ObjectDuplexe` 路径
- immediate handoff 生效后，`h2` 分支会在同一个外层 RCV 调用里补跑一次空输入轮次，先发 server SETTINGS preface，再消费 route seed
- 原升级请求会作为一次性 route seed 提升为 stream 1 的 HTTP/2 请求对象，并由 `h2` 分支里的 `Http2ObjectDuplexe` 立即接入正常处理链
- 这个 route seed 只用于“刚才那条已经被 h2c 分支消费掉的 upgrade request”；后续客户端发来的 connection preface、client SETTINGS、SETTINGS ACK 都是切路由后的正常 HTTP/2 流量，不走 seed

### 4.4 服务端：TLS + ALPN 自动分支到 HTTP/2

适用范围：

- HTTPS 单端口同时承载 HTTP/1.1 和 HTTP/2
- 需要在 TLS 握手完成后按 ALPN 自动分流到 H1 或 H2 分支

推荐装配：

```java
ctx.addLast("https", ProtoHelper.standard()
  .nextDuplex("ssl", new SslDuplexer(sslConfig))
  .nextRouteAsStatic("alpn", new HttpAggregatorOverTlsRoute(), routing -> {
      ProtoRoutingControl routingControl = routing.control();

      routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch
        .nextDuplex("http-codec", new HttpServerDuplexe())
        .nextDuplex("h1-h2c-upgrade-bridge", new H2CUpgradeServerDuplexe(routingControl))
        .nextDecoder("http-aggregator", new HttpRequestAggregator(1024 * 1024))
        .nextDecoder("http-handler", httpHandler));

      routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch
        .nextDuplex("h2-frame", new Http2FrameDuplexe(true))
        .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))
        .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
      Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
      ProtoPartitionControl control = partition.control();
      partition.policy(policy)
        .byDefault(partitionCtx -> partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(control, policy)))
        .byInitializer(partitionCtx -> {
            partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1024 * 1024));
            partitionCtx.addLastDecoder("h2-handler", h2Handler);
        }))
      .branch(HttpRouteKey.BRANCH_H2C, branch -> branch
        .nextDuplex("http-codec", new HttpServerDuplexe())
        .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl))
        .nextDecoder("h2c-handler", h2cHandler));
        }));
  })
  .build());
```

关键点：

- `Http2OverTlsRoute` 本身不负责 TLS 建链，它在 `SslContext.isReady()` 后优先读取 ALPN 结果
- ALPN 协商为 `h2` 时直接进入 HTTP/2 分支
- 如果 ALPN 没有协商出 `h2`，但 TLS 解密后的应用层首包以 `PRI` 开头，仍会按 prior knowledge 进入 HTTP/2 分支
- 如果首个分支落到 HTTP/1.1，且 H1 分支内部挂了 `H2CUpgradeServerDuplexe`，同一条 TLS 连接后续仍可经由 HTTP/1.x upgrade 桥切到 H2 分支
- 客户端若只声明 `http/1.1`，同时也没有发送以 `PRI` 开头的首包或合法 upgrade 请求，最终才会稳定留在 H1 分支

### 4.5 只做 frame 层调试

适用范围：

- 需要观察 preface、帧头、flags 和 payload 的原始形态
- 需要验证帧边界、保留位、长度模型和分片输入输出

推荐装配：

```java
ctx.addLast("h2-frame", new Http2FrameDuplexe(true));
```

关键点：

- 这一层不会给你 HEADERS 里的伪头字段，也不会帮你做 HPACK 解码
- 它最适合做二进制协议层单测和问题定位

### 4.6 只做 message 层调试

适用范围：

- 需要直接处理 SETTINGS、PING、WINDOW_UPDATE 等协议消息
- 需要看 CONTINUATION 重组后 header block 的语义对象

推荐装配：

```java
ctx.addLast("h2-object", new Http2ObjectDuplexe(true));
```

关键点：

- 这一层对外直接提供 HttpObject 对象流
- SETTINGS、PING、WINDOW_UPDATE、RST_STREAM、GOAWAY 不再作为公开下游消息，而是被内部维护或转换成事件
- 这一层还负责 header block 连续性约束、自动 ACK/WINDOW_UPDATE 排队、以及按对端帧长分片
- 但它不会把控制帧伪装成通用请求响应对象

## 5. 内部工作机制

### 5.1 帧、消息、事件的高层关系图

这一节先给一张完整总览图，用来回答四个问题：

- 网络侧数据先经过哪些 codec
- HTTP/2 的 payload 和 control/event 在哪里分流
- 分区子链里默认分区和一般分区分别有几个
- 业务处理和响应回写从哪个位置重新进入编码路径

```text
网络侧入站 ByteBuf
  |
    v
+-------------------------------------------+
| Http2FrameDecoder / Http2FrameDuplexe     |
+-------------------------------------------+
  |
    v
+-------------------------------------------+
| Http2ObjectDecoder / Http2ObjectDuplexe   |
+-------------------------------------------+
  |
    v
+-------------------------------------------+
| Http2ObjectPartitionSelector              |
+-------------------------------------------+
  |
  +--> HTTP/2 控制事件 -----------------------------------------------------------+
  |    GOAWAY / RST_STREAM / PRIORITY / PUSH_PROMISE / stream-half-close          |
  |                                                                                v
  |                                           +-----------------------------------------------+
  |                                           | 默认分区 1 个                                  |
  |                                           | Http2ObjectLifecycleDuplexer                  |
  |                                           | 负责连接级与流级生命周期管理                  |
  |                                           | 处理控制事件和 stream 结束事件               |
  |                                           +-----------------------------------------------+
  |                                                                                |
  |                                                                                | 控制面回写
  |                                                                                | RST_STREAM / GOAWAY 等
  |                                                                                v
  |
  +--> HttpObject payload ---------------------------------------------------------+
     HttpRequest / HttpResponse / HttpHeaders / HttpContent / LastHttpContent    |
                                           v
                       +---------------------------------------------------+
                       | 一般分区 多个，按 streamId 建立                   |
                       |                                                   |
                       |  +---------------------+   +-------------------+  |
                       |  | 分区 A              |   | 分区 B ... N      |  |
                       |  | streamId = 1        |   | streamId = 3/5/7  |  |
                       |  | 聚合器或流内处理节点 |   | 聚合器或流内处理节点 |  |
                       |  | -> 业务 Handler     |   | -> 业务 Handler   |  |
                       |  +---------------------+   +-------------------+  |
                       +---------------------------------------------------+
                                           |
                                           v
                                       业务响应 HttpObject
                                           |
                                           v
                       +-------------------------------------------+
                       | Http2ObjectEncoder / Http2ObjectDuplexe   |
                       +-------------------------------------------+
                                           |
                                           v
                       +-------------------------------------------+
                       | Http2FrameEncoder / Http2FrameDuplexe     |
                       +-------------------------------------------+
                                           |
                                           v
                                       网络侧出站 ByteBuf
```

这张图表达的是当前实现中的几个固定关系：

- 从网络进入后，永远先经过 frame 层，再进入 message 层
- message 层已经把 payload 还原为 `HttpObject`，同时把控制面结果转成 `Http2Event`
- 分区子链里默认分区只有 1 个，它不承载业务请求响应，只负责 HTTP/2 生命周期和控制事件
- 一般分区有多个，它们按 `streamId` 动态建立，承载聚合器和业务 handler
- 业务响应从一般分区回写后，不会直接下网，而是重新回到 HTTP/2 message encoder，再进入 frame encoder
- 默认分区和一般分区都处在同一个 partition router 之后，但职责不同

如果只看服务端普通请求的主路径，可以再把它压缩成一句话：

- 入站 `ByteBuf -> Http2Frame -> HttpObject -> stream 分区 -> 业务处理`
- 出站 `业务响应 HttpObject -> Http2Frame -> ByteBuf`
- 控制面 `Http2Event -> 默认分区 -> 生命周期处理 -> 编码回写`

### 5.1.1 默认分区和一般分区的关系

默认分区和一般分区不是“主分区和子分区”的包含关系，而是同一层级下两类职责不同的分区实例：

- 默认分区固定只有 1 个，它对应 `PartitionKey.defaultKey()`
- 一般分区可以有多个，它们对应不同的 `streamId`
- 默认分区处理的是 HTTP/2 连接控制面和 stream 生命周期信号
- 一般分区处理的是某一个 stream 上的 `HttpObject` 消息流和业务逻辑

可以把它理解成：

- 默认分区负责“管协议”
- 一般分区负责“跑业务”

在当前实现里，这种分工有两个直接收益：

- 业务 handler 不需要直接消费 GOAWAY、RST_STREAM、PRIORITY 这类控制事件
- stream 结束、reset、goaway 这类生命周期动作可以统一由默认分区收口，而不是散落在每个 stream 子链里

### 5.2 frame 层职责和原理

frame 层的目标很单一：维护 RFC 9113 的 binary framing layer，而不是直接处理请求响应语义。它回答的问题只有两个：

- 这一段字节是否是合法的 HTTP/2 连接前言或 frame
- 一个合法 frame 应该如何被写回为 9 字节帧头加 payload

当前实现中，frame 层的职责包括：

- 处理 server side 对 client preface 的校验
- 解析 9 字节 frame header，得到 length、type、flags、stream identifier
- 按 payload length 截取 payload，并构造 Http2Frame
- 在出站方向把 Http2Frame 重新编码成 wire format
- 拒绝超出本端 SETTINGS_MAX_FRAME_SIZE 的 payload 长度

frame 层刻意不处理这些事情：

- 不做 HPACK header block 解码
- 不维护 stream 生命周期
- 不判断某个 control frame 应该自动 ACK，还是应该转成事件
- 不把 HEADERS/DATA 直接变成 HttpRequest 或 HttpResponse

这样分层的原因很直接：frame 层越纯粹，二进制协议调试越容易，message 层和 HttpObject 层也不会被迫耦合到底层字节细节。

### 5.3 message 层职责和原理

message 层是 HTTP/2 在 Neta 中真正的协议边界。它不再只看 frame 外形，而是开始解释 frame 的协议含义，并接管连接级、流级的核心语义。

这一层承担五类职责：

- 把 HEADERS 和 CONTINUATION 重组为完整 header block，并执行 HPACK 解码
- 把 HEADERS、DATA、END_STREAM 重组成稳定的 payload 消息序列
- 校验 control frame 的 target、长度和状态约束
- 为 SETTINGS ACK、PING ACK、WINDOW_UPDATE 维护协议自动动作
- 把对应用层有意义的控制面结果发布为事件，例如 Http2PongEvent、Http2ResetEvent、Http2GoawayEvent

如果没有 message 层，就会出现两个直接后果：

- frame 层不得不理解 HPACK、stream state 和 control semantics，职责会失真
- HttpObject 层不得不自己处理 GOAWAY、RST_STREAM、ACK 和流控，业务层边界会被破坏

因此，message 层承担 HTTP/2 的协议运行职责。

### 5.4 message 层的公开边界

当前 message 层对外坚持一个明确边界：payload object 和 control/event 分开。

继续向后传播的公开内容只有：

- HttpRequest / HttpResponse
- HttpHeaders / LastHttpHeaders
- HttpContent / LastHttpContent
- TrailerHttpHeaders

不会继续作为公开下游消息传播的内容包括：

- 由协议层自动维护的动作：SETTINGS ACK、PING ACK、WINDOW_UPDATE
- 由协议层转换出的事件：Http2PongEvent、Http2ResetEvent、Http2GoawayEvent、Http2PriorityEvent、Http2PushPromiseEvent

这意味着：

- 只关心 request 和 response 的业务层，可以继续下沉到 HttpObject 层
- 需要感知 ping/pong、reset、goaway、priority、push promise 的逻辑，应停留在 message 层或监听事件
- 应用层可以观察协议动作，但不需要重新承担协议层的状态机职责

### 5.5 message codec 的总览时序

这一节不再用一张大图容纳所有情况，而是拆成几张按场景阅读的小图。每张图只说明一类交互，避免消息、事件、异常和回传动作互相打架。

#### 5.5.1 HEADERS/DATA 到消息

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | HEADERS bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | HEADERS frame            |                       |
  |                        |-------------------------->|                       |
  |                        |                           | validate/decode       |
  |                        |                           | HttpRequest /         |
  |                        |                           | HttpResponse /        |
  |                        |                           | HttpHeaders           |
  |                        |                           |---------------------->|
  |                        |                           |                       |
  | DATA bytes             |                           |                       |
  |----------------------->|                           |                       |
  |                        | DATA frame               |                       |
  |                        |-------------------------->|                       |
  |                        |                           | HttpContent /         |
  |                        |                           | LastHttpContent       |
  |                        |                           |---------------------->|
```

#### 5.5.2 远端控制帧到事件

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | control bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | PING ack=true /          |                       |
  |                        | RST_STREAM / GOAWAY frame|                       |
  |                        |-------------------------->|                       |
  |                        |                           | Http2PongEvent /      |
  |                        |                           | Http2ResetEvent /     |
  |                        |                           | Http2GoawayEvent      |
  |                        |                           |---------------------->|
```

#### 5.5.3 本地异常到事件与回传帧

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | invalid bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | invalid stream /         |                       |
  |                        | invalid connection frame |                       |
  |                        |-------------------------->|                       |
  |                        |                           | Http2ResetEvent /     |
  |                        |                           | Http2GoawayEvent      |
  |                        |                           |---------------------->|
  | RST_STREAM / GOAWAY    | RST_STREAM / GOAWAY      |<======================|
  | bytes                  | frame                     |                       |
  |<-----------------------|                           |                       |
```

### 5.6 header block 重组原理

HEADERS 和 CONTINUATION 的连续性约束是 message 层最核心的语义之一。当前实现只在一个完整 header block 收齐后解码一次，而不会提前对半截 header 做语义解释。

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | inbound bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | HEADERS frame(stream=N, END_HEADERS=0)           |
  |                        |-------------------------->|                       |
  |                        |                           | open header block     |
  |                        |                           | accumulation          |
  |                        |                           |                       |
  | inbound bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | CONTINUATION frame(stream=N, END_HEADERS=0)      |
  |                        |-------------------------->|                       |
  |                        |                           | append fragment       |
  |                        |                           |                       |
  | inbound bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | CONTINUATION frame(stream=N, END_HEADERS=1)      |
  |                        |-------------------------->|                       |
  |                        |                           | finalize block        |
  |                        |                           | HPACK decode once     |
  |                        |                           | HttpRequest /         |
  |                        |                           | HttpResponse /        |
  |                        |                           | HttpHeaders           |
  |                        |                           |---------------------->|
```

这里有两条必须满足的规则：

- 当一个 stream 处于等待 CONTINUATION 的状态时，下一帧必须仍然是同一个 stream 的 CONTINUATION
- 只有最后一个带 END_HEADERS 的片段到达后，header block 才会被一次性 HPACK 解码并产生对应的 HttpObject 头对象

### 5.7 message codec 的协议动作时序

这一节继续使用小图，把不同协议动作分开说明。往返交互用一条双向线表示，不再拆成“去一条、回一条”两行。

#### 5.7.1 SETTINGS 握手

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | SETTINGS bytes         |                           |                       |
  |----------------------->|                           |                       |
  | SETTINGS frame <==================================>|                       |
  | SETTINGS ACK bytes     |                           |                       |
  |<-----------------------|                           |                       |
```

#### 5.7.2 ping/pong 交互

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | PING bytes             |                           |                       |
  |----------------------->|                           |                       |
  | PING frame(ack=false) <==========================>|                       |
  | PING ACK bytes         |                           |                       |
  |<-----------------------|                           |                       |
  |                        |                           |                       |
  | PING ACK bytes         |                           |                       |
  |----------------------->|                           |                       |
  |                        | PING frame(ack=true)     |                       |
  |                        |-------------------------->|                       |
  |                        |                           | Http2PongEvent        |
  |                        |                           |---------------------->|
```

#### 5.7.3 DATA / WINDOW_UPDATE 交互

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | DATA bytes             |                           |                       |
  |----------------------->|                           |                       |
  |                        | DATA frame(stream=1)     |                       |
  |                        |-------------------------->|                       |
  |                        |                           | HttpContent /         |
  |                        |                           | LastHttpContent       |
  |                        |                           |---------------------->|
  | WINDOW_UPDATE bytes    | WINDOW_UPDATE frame <====|                       |
  |<-----------------------|                           |                       |
```

这一阶段有两个层次：

- decoder 负责识别协议语义、产出消息或事件、并决定是否需要协议回包
- encoder 负责把这些待回包动作真正编码成 frame 并发给 Peer

### 5.8 message codec 的普通出站路径

这一节也把 decoder 和 encoder 放到一起，但重点落在“应用主动出站”这条链路上。

```text
App                  Message Codec               Frame Codec                Peer
  |                        |                           |                       |
  | HttpObject / Event     |                           |                       |
  |----------------------->|                           |                       |
  |                        | assign stream id         |                       |
  |                        | HPACK encode / split     |                       |
  |                        |-------------------------->|                       |
  |                        |                           | outbound bytes        |
  |                        |                           |---------------------->|
```

这里没有入站 decoder 行为，但保留它作为同一组参与者中的一部分，避免阅读时在不同小节里切换参与者模型。

### 5.9 事件发送和可观测性

当前实现把“协议层负责动作”和“应用层负责观察”明确分开：

- 协议层负责发送或排队 RST_STREAM、GOAWAY、ACK、WINDOW_UPDATE 等协议动作
- 应用层通过事件感知远端行为，或者感知本地协议层刚刚执行了什么动作

事件对象统一带有 isRemote 属性：

- isRemote=true 表示事件来源于远端入站 frame
- isRemote=false 表示事件来源于本地协议层动作

这使得应用层可以处理连接和 stream 的清理、监控、补偿和重试，但不需要接管协议实现本身。

在错误路径上，需要额外记住一个关键事实：

- decoder 遇到协议错误时，通常会同时产出两条结果
- 一条结果面向 Peer：请求 encoder 回传 RST_STREAM 或 GOAWAY
- 一条结果面向 App：发布本地 Http2ResetEvent 或 Http2GoawayEvent

也就是说，错误路径同时包含协议动作和应用可观测性。

### 5.10 本地 stream error 的双输出时序

当 message 层判定某个错误是 stream-level error 时，decoder 和 encoder 会在同一条时序里共同完成“事件发布 + 回传帧”这两个结果。

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | inbound bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | invalid stream frame     |                       |
  |                        |-------------------------->|                       |
  |                        |                           | raise stream error    |
  |                        |                           | Http2ResetEvent       |
  |                        |                           |---------------------->|
  | RST_STREAM bytes       | RST_STREAM frame <=======|                       |
  |<-----------------------|                           |                       |
```

这里体现的分层原则是：

- 协议层负责决定“应该 reset 哪个 stream，以及发送哪个 error code”
- 应用层负责看到本地 reset 事件后处理 stream registry、future、统计和业务补偿

stream error 的完整结果是：

- App 收到 Http2ResetEvent(isRemote=false)
- Peer 收到 RST_STREAM frame

### 5.11 本地 connection error 的双输出时序

当错误已经升级为 connection-level error 时，decoder 和 encoder 会在同一条时序里共同完成“事件发布 + 回传帧”这两个结果。

```text
Peer                 Frame Codec                Message Codec               App
  |                        |                           |                       |
  | inbound bytes          |                           |                       |
  |----------------------->|                           |                       |
  |                        | invalid connection frame |                       |
  |                        |-------------------------->|                       |
  |                        |                           | raise connection error|
  |                        |                           | Http2GoawayEvent      |
  |                        |                           |---------------------->|
  | GOAWAY bytes           | GOAWAY frame <==========|                       |
  |<-----------------------|                           |                       |
```

注意这里的 lastStreamId 表示“本端可能已经处理过的最高 peer-initiated stream id”，它是连接级停止边界，不是当前出错 stream 的简单回显。

connection error 的完整结果是：

- App 收到 Http2GoawayEvent(isRemote=false)
- Peer 收到 GOAWAY frame

### 5.12 h2c upgrade 与 message 层的关系

h2c upgrade 只是接入方式不同，不改变 message 层的职责。桥接器只负责：

- 校验 Upgrade: h2c、Connection、HTTP2-Settings
- 把 HTTP2-Settings 写入连接状态
- 发送 101 Switching Protocols
- 把已经在 h2c 分支里消费掉的升级请求以 immediate route seed 方式提升为 stream 1 的 HTTP/2 请求

这里最容易混淆的是 route seed 的用途：

- route seed 只负责把“已消费的 HTTP/1.1 upgrade request”补投递到 `h2` 分支
- h2c 当前直接使用默认 immediate 的 `switchRoute(target, seed)`，在切换生效后的同一外层 RCV 调用里让 seed 立刻可见
- 这个 immediate handoff 让 `h2` 分支可以先发 server SETTINGS preface，再立即消费 stream 1 upgrade request
- 客户端后续发来的 connection preface、client SETTINGS、SETTINGS ACK，不通过 seed 传递
- 这些后续数据在切路由后会作为正常 HTTP/2 流量进入 `Http2FrameDuplexe -> Http2ObjectDuplexe`

一旦切换完成，后续 HEADERS、DATA、ACK、PING、WINDOW_UPDATE、GOAWAY、RST_STREAM 全都仍由 message 层解释和维护。

### 5.13 分层异常模型

HTTP/2 的异常应该按层定位，而不是一上来就盯业务对象：

- ByteBuf / frame 层
  - 连接前言错误
  - frame payload 超过本端 SETTINGS_MAX_FRAME_SIZE
  - 9 字节帧头字段非法
- message 层
  - frame target 错误，例如 PING 使用了非零 stream id
  - SETTINGS ACK 带 payload
  - PING 长度不是 8
  - WINDOW_UPDATE 增量为 0
  - HEADERS / CONTINUATION 被其他 frame 夹断
  - GOAWAY、RST_STREAM、PRIORITY payload 长度不合法
  - PUSH_PROMISE 父流状态非法、promised stream id 方向错误或未保持远端流号递增
- HttpObject 适配层
  - 必需伪头缺失

### 6.3 事件流与协议自动动作

这一节不再保留一张新的汇总大图，而是直接按场景回看前文：

- `HEADERS/DATA -> 消息`：见第 5.5.1 节
- `远端控制帧 -> 事件`：见第 5.5.2 节
- `SETTINGS` 握手：见第 5.7.1 节
- `PING/PONG`：见第 5.7.2 节
- `DATA/WINDOW_UPDATE`：见第 5.7.3 节
- `stream error -> RST_STREAM + event`：见第 5.10 节
- `connection error -> GOAWAY + event`：见第 5.11 节

排错线索：

- 自动 ACK 和 WINDOW_UPDATE 会先进入 decoder state，再由 message duplexe 在发送周期取出
- 如果业务层没看到某个 ACK，不代表协议层没处理，它可能已经在 duplexe 内部被自动维护掉了
- 如果业务层需要感知 ping/pong、GOAWAY 或 RST_STREAM，应该监听 HTTP/2 事件，而不是等待对应 Http2Message
- 判断某个 reset 或 goaway 来自远端还是本地协议层时，应直接读取事件对象上的 isRemote 属性

## 7. 组件参考

### 7.1 Http2FrameDuplexe

- 作用：ByteBuf 和 Http2Frame 之间的 frame 层双工入口
- 关键参数：
  - `serverMode`：服务端模式下要求先接收 client preface
- 何时使用：只做帧层调试、测试或桥接时

### 7.2 Http2ObjectDuplexe

- 作用：Http2Frame 和 HttpObject 之间的语义层双工入口
- 关键参数：
  - `serverMode`：区分 client 和 server 语义
  - `localSettings`：本端 SETTINGS、HPACK 和帧长限制
- 何时使用：需要直接处理 HttpObject，同时保留控制帧事件和协议自动维护时

### 7.3 H2cUpgradeServerDuplexe

- 作用：服务端 HTTP/1.1 -> h2c upgrade 桥接
- 关键能力：
  - 升级请求校验
  - HTTP2-Settings 解码
  - 101 响应发送
- 以默认 immediate 的 `switchRoute(target, seed)` 做切换，把“已消费的升级请求”交给 `Http2FrameDuplexe -> Http2ObjectDuplexe`
- immediate handoff 后，`h2` 分支会同轮发出 server SETTINGS preface，并立即消费 stream 1 seed
- 前置要求：只需要放在 HTTP/1.1 codec 之后，不再要求上游先挂 `HttpRequestAggregator`
- 何时使用：一个端口既要接 HTTP/1.1 又要接 h2c 时

## 8. 使用注意事项

- 不要把 HTTP/2 语义层和 HttpObject 层误解成两套公开 payload 模型。当前公开 payload 边界就是 HttpObject。
- 业务如果需要感知 ping/pong、RST_STREAM 或 GOAWAY，应监听 HTTP/2 事件。
- PRIORITY 和 PUSH_PROMISE 不会继续变成公开 payload；如果业务需要感知它们，应监听 message 层发布的 HTTP/2 事件。
- H2cUpgradeServerDuplexe 不是普通的 HTTP/2 server duplexe。它在升级前仍然依赖 HTTP/1.1 codec，但不要求上游先聚合成 `FullHttpRequest`。
- 如果你看到 route seed，不要把它理解成 post-upgrade 的 HTTP/2 连接流量。seed 只对应 upgrade request 本身；client preface、client SETTINGS、SETTINGS ACK 都会在切路由后作为正常 HTTP/2 数据进入 h2 分支。
- 当前 `switchRoute(...)` 默认就是 immediate handoff。只有明确需要“切换延后到下一次网络事件”时，才应该使用 `switchRouteNextTick(...)`。
- 如果桥接场景没有 seed handoff，或者当前事务必须先在旧分支完整收尾，就应使用 `switchRouteNextTick(...)`。
- Http2ObjectDuplexe 会自动发 ACK 和 WINDOW_UPDATE。看到自动输出的控制帧时，先判断是不是协议维护行为，而不是业务逻辑重复发送。
- client 和 server 两侧的 stream id 语义不同。不要在客户端链路里手工写死新的请求 stream id，除非你明确要绕过默认分配策略。
- 如果只想调 frame 头、flags 或 payload 边界，直接用 Http2FrameDuplexe；如果问题已经涉及伪头、HPACK 或控制消息，再上升到 Http2ObjectDuplexe。
- 当前 HttpObject 已经由 Http2ObjectDuplexe 直接产出和消费，但对象生命周期判断仍然应遵守 [引用所有权](../../principle/handler/ownership.md) 中的原则，尤其是在 ByteBuf、HttpContent 和聚合对象同时存在时。
