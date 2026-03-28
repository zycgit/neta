---
sidebar_position: 7
title: HTTP
description: 说明 Neta 在 HTTP/1.x 上的对象模型、编解码、聚合、双工装配、transparent mode 和使用注意事项。
---

本文是 Neta HTTP/1.x 支持的正式说明文档，面向两类读者：

- 需要搭建服务端、客户端或升级前置链路的使用者
- 需要定位流模式、聚合器、transparent mode 和消息体边界处理的维护者

正文按“先边界、再装配、再机制、最后参考”的顺序展开：

- 第 1 章说明 HTTP 在 Neta 中的整体定位
- 第 2 章说明已支持能力、能力边界和当前限制
- 第 3 章到第 4 章说明组件分层、推荐入口和典型装配方式
- 第 5 章到第 6 章说明内部机制以及数据流、异常流、事件流
- 第 7 章到第 8 章提供组件参考和实际使用注意事项

阅读时可以直接按目标进入对应章节：

- 关注 HTTP/1.x 具体支持什么，读取第 2 章
- 关注服务端、客户端如何装配，读取第 3 章和第 4 章
- 关注状态机、transparent mode、自动响应和异常处理，读取第 5 章和第 6 章
- 关注构造器、参数和误用点，读取第 7 章和第 8 章

## 1. 简介

Neta 在 neta-codec-http 模块中提供一套面向 HTTP/1.x 的对象模型和编解码链路，项目的重点是把 socket 字节流和 HTTP 对象之间的转换、聚合、升级切换做成可组合的协议层组件。

整体上可以分成 4 层：

- 流式 codec 层：负责 ByteBuf 和 HttpObject 片段流之间的双向转换
- 完整消息聚合层：负责把片段对象聚合成 FullHttpRequest 或 FullHttpResponse
- duplexe 入口层：把服务端和客户端常见的收发组合包装成一个双工节点
- 上下文与切换层：通过 HttpContext 维护连接状态，并用 transparent mode 支持 Upgrade 后的原始流量透传

这套实现主要解决三类问题：

- 如何把 HTTP/1.x 请求和响应拆成稳定的片段流
- 如何在需要时聚合成完整消息，同时保留 trailer、content-length 和自动响应语义
- 如何在协议升级后停止解析 HTTP，切换为原始字节透传

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- 协议范围
  - HTTP/1.0 与 HTTP/1.1 起始行解析和编码
  - 请求侧和响应侧独立的 codec、aggregator、duplexe 入口
- 对象模型
  - 流模式：HttpRequest 或 HttpResponse、HttpHeaders、HttpContent、TrailerHttpHeaders、LastHttpContent
  - 完整消息对象：FullHttpRequest、FullHttpResponse
  - 透传对象：HttpByteBuf
- 消息体边界处理
  - 请求侧支持 Content-Length 和 Transfer-Encoding: chunked
  - 响应侧支持 chunked、Content-Length 和 close-delimited body
  - 响应侧识别 1xx、204、304 为无 body 响应
- 聚合能力
  - HttpRequestAggregator 聚合入站请求
  - HttpResponseAggregator 聚合入站响应
  - 聚合时合并 initial headers 与 trailer headers
  - 聚合完成后自动重写 Content-Length，并移除 Transfer-Encoding
- 双工入口
  - 服务端：HttpServerDuplexe、HttpServerDuplexeAggregator
  - 客户端：HttpClientDuplexe、HttpClientDuplexeAggregator
- 升级与切换
  - 通过 HttpThroughEvent.enable() 和 HttpThroughEvent.disable() 切换 transparent mode
  - transparent mode 下 decoder 不再解析 HTTP，而是输出 HttpByteBuf
  - transparent mode 下 encoder 只接受 HttpByteBuf，并直接透传 payload
- 请求聚合器自动响应
  - 支持 Expect: 100-continue 自动返回 100 Continue
  - 不支持的 Expect 自动返回 417 Expectation Failed
  - 头部声明或实际累计内容超过上限时自动返回 413 Request Entity Too Large

### 2.2 能力边界

这一套 HTTP/1.x 组件负责：

- 把字节流转换成 HTTP 片段流
- 把 HTTP 片段流编码回字节流
- 在需要时聚合为完整消息对象
- 在 Upgrade 场景下切换到 transparent mode
- 对一部分协议错误做恢复、标记或自动响应

这一套 HTTP/1.x 组件不负责：

- 路由、控制器、拦截器、会话和模板渲染
- 业务层重试、流控、上传协议和下载协议
- multipart 表单完整处理流程本身
- WebSocket、HTTP/2、HTTP/3 的完整能力说明

其中要特别区分两件事：

- HttpServerDuplexe 和 HttpClientDuplexe 只负责 HTTP codec 组合，不会自动聚合消息
- HttpServerDuplexeAggregator 和 HttpClientDuplexeAggregator 只负责聚合已有 HttpObject，不负责把字节解析成 HTTP 对象

### 2.3 当前明确限制

- 当前文档讨论的是 HTTP/1.x codec 体系，不覆盖 h2 和 h3 模块中的实现
- 请求侧不支持通过 connection close 判定 body 结束，支持方式只有 chunked 和 Content-Length
- 响应侧虽然支持 close-delimited body，但结束标记来自连接关闭，本身没有 in-band 结束符
- 默认长度限制是固定的
  - 初始行最大 4096
  - headers 总大小最大 8192
  - 单次内容切片最大 8192
  - 聚合器默认最大内容长度 1048576
- chunk size 解析后落到 int，超过 int 上限会按协议错误处理
- transparent mode 不是“半开解析模式”
  - 开启后 decoder 不再识别 HTTP 起始行和 header
  - 开启后 encoder 只接受 HttpByteBuf
- encoder 不会纠正错误的片段输出顺序
  - 如果把 trailer 提前发在 content 前面，encoder 仍会按收到的对象顺序编码
  - 这类行为适合做底层调试，不适合当作推荐业务写法

## 3. 组件分层

### 3.1 整体分层

```text
socket bytes
  -> HttpRequestDecoder / HttpResponseDecoder
  -> HttpObject 片段流

HttpObject 片段流
  -> HttpRequestAggregator / HttpResponseAggregator
  -> FullHttpRequest / FullHttpResponse

常规双工入口
  服务端: HttpServerDuplexe / HttpServerDuplexeAggregator
  客户端: HttpClientDuplexe / HttpClientDuplexeAggregator

升级后透传
  HttpThroughEvent.enable()
  -> HttpContext.transparentMode = true
  -> HttpByteBuf passthrough
```

### 3.2 推荐入口

常规场景优先选择这些公开入口：

- 服务端流模式 pipeline：HttpServerDuplexe
- 服务端完整消息 pipeline：HttpServerDuplexe + HttpServerDuplexeAggregator
- 客户端流模式 pipeline：HttpClientDuplexe
- 客户端完整消息 pipeline：HttpClientDuplexe + HttpClientDuplexeAggregator

只有在这些场景才建议直接挂底层部件：

- 只测试请求侧或响应侧某一半链路
- 需要把入站和出站分别放到不同分支里
- 需要明确保留片段对象，而不是统一走 duplexe 入口

### 3.3 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| HttpRequestDecoder | ByteBuf -> 请求片段对象 | 服务端入站请求解析 |
| HttpResponseDecoder | ByteBuf -> 响应片段对象 | 客户端入站响应解析 |
| HttpRequestEncoder | 请求片段对象 -> ByteBuf | 客户端出站请求编码 |
| HttpResponseEncoder | 响应片段对象 -> ByteBuf | 服务端出站响应编码 |
| HttpRequestAggregator | 请求片段流 -> FullHttpRequest | 服务端完整消息处理 |
| HttpResponseAggregator | 响应片段流 -> FullHttpResponse | 客户端完整消息处理 |
| HttpServerDuplexe | request decoder + response encoder | 服务端 HTTP/1.x 常规入口 |
| HttpClientDuplexe | response decoder + request encoder | 客户端 HTTP/1.x 常规入口 |
| HttpServerDuplexeAggregator | request aggregator + response aggregator | 服务端全消息聚合入口 |
| HttpClientDuplexeAggregator | response aggregator + request aggregator | 客户端全消息聚合入口 |
| HttpContext | 连接级状态、transparent mode、编解码状态机共享上下文 | 所有 HTTP/1.x handler 共用 |

### 3.4 选型结论

- 常规业务优先使用 duplexe 入口，而不是手动一对一拼装 encoder 和 decoder
- 业务逻辑只想处理完整消息时，在 duplexe 后面追加 aggregator
- 需要观察 header 分段、chunk、trailer 或错误对象传播时，保留流模式，不要先聚合
- 需要支持 Upgrade 后原始协议透传时，必须让相关 HTTP handler 一起接收 HttpThroughEvent（如 WebSocket）

## 4. 使用方式

### 4.1 服务端：请求片段流与响应片段流

适用范围：

- 业务需要自己处理 header 分段、body chunk 和 trailer
- 需要在 upgrade 或代理场景中精细控制对象流

推荐装配：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("app-handler", appHandler);
```

关键点：

- 入站是 HttpRequest、HttpHeaders、HttpContent、TrailerHttpHeaders、LastHttpContent 的片段流
- 出站也需要按片段对象顺序发送
- 这是最接近底层协议的服务端装配方式

### 4.2 服务端：完整请求与完整响应

适用范围：

- 业务层更适合直接处理完整请求和完整响应
- 需要自动处理 Expect 和超长请求的协议响应

推荐装配：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("http-agg", new HttpServerDuplexeAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- 入站请求会被聚合成 FullHttpRequest
- 出站响应会被聚合成 FullHttpResponse
- 头部和 trailer 会合并到最终 headers 中
- 聚合完成后会重写 Content-Length，移除 Transfer-Encoding

### 4.3 客户端：请求片段流与响应片段流

适用范围：

- 需要精细控制出站请求分段发送
- 需要观察响应的 header、body chunk 和 trailer

推荐装配：

```java
ctx.addLast("http", new HttpClientDuplexe());
ctx.addLast("app-handler", appHandler);
```

关键点：

- 出站请求由 HttpRequestEncoder 负责编码
- 入站响应由 HttpResponseDecoder 负责解析
- 如果后面要接 WebSocket 或其他 Upgrade 协议，这是常用入口

### 4.4 客户端：完整请求与完整响应

适用范围：

- 需要直接收发完整对象
- SDK 或调用方不想处理 chunk 和 trailer

推荐装配：

```java
ctx.addLast("http", new HttpClientDuplexe());
ctx.addLast("http-agg", new HttpClientDuplexeAggregator(1024 * 1024));
ctx.addLast("app-handler", appHandler);
```

关键点：

- 入站响应会聚合为 FullHttpResponse
- 出站请求也可以直接发送 FullHttpRequest
- FullHttpRequest 和 FullHttpResponse 本身同时实现 start line、headers 和 last content 相关接口，因此可以直接复用 encoder 路径

### 4.5 Upgrade 前后混合场景：通过 transparent mode 切换

适用范围：

- HTTP/1.x 只负责 Upgrade 前置协商
- 协议切换后需要把后续字节交给其他 codec 处理

推荐装配：

```java
ctx.addLast("http", new HttpServerDuplexe());
ctx.addLast("http-agg", new HttpServerDuplexeAggregator(1024 * 1024));
ctx.addLast("upgrade-handler", upgradeHandler);
ctx.addLast("next-protocol", nextProtocolHandler);
```

切换方式：

```java
channel.fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
```

关键点：

- 开启后 decoder 输出 HttpByteBuf，而不是 HttpRequest 或 HttpResponse
- 开启后 encoder 只接受 HttpByteBuf
- 关闭 transparent mode 后，HTTP 状态机会被重置，再次恢复 HTTP 解析和编码

## 5. 内部工作机制

### 5.1 流模式为什么是默认形态

Neta HTTP/1.x 默认把一条消息拆成片段流，而不是一开始就强制聚合。这样做有三个直接好处：

- 可以在 body 未完整到齐时就开始处理 header
- 可以保留 trailer、chunk 边界和错误恢复语义
- 可以为 Upgrade、代理和流式转发保留更低层的控制权

服务端入站请求在流模式下更接近下面这个时序：

```text
Peer                 HttpServerDuplexe        HttpRequestDecoder        App Handler
  |                        |                         |                        |
  | socket bytes           |                         |                        |
  |----------------------->|                         |                        |
  |                        |------------------------>| decode request line    |
  |                        |<------------------------| HttpRequest            |
  |                        |--------------------------------------------------->|
  |                        |------------------------>| decode headers         |
  |                        |<------------------------| HttpHeaders            |
  |                        |--------------------------------------------------->|
  |                        |------------------------>| decode body chunk      |
  |                        |<------------------------| HttpContent            |
  |                        |--------------------------------------------------->|
  |                        |------------------------>| close message          |
  |                        |<------------------------| LastHttpContent        |
  |                        |--------------------------------------------------->|
```

这条链路的重点不是“一次收齐一条消息”，而是 decoder 可以按解析进度持续吐出片段对象。

请求和响应的标准输出序列分别是：

```text
Request side
  HttpRequest
  -> HttpHeaders 或 LastHttpHeaders
  -> 0..n 个 HttpContent
  -> 0..n 个 TrailerHttpHeaders
  -> LastHttpContent

Response side
  HttpResponse
  -> HttpHeaders 或 LastHttpHeaders
  -> 0..n 个 HttpContent
  -> 0..n 个 TrailerHttpHeaders
  -> LastHttpContent
```

### 5.2 消息体边界判定规则

请求侧和响应侧的边界判定并不完全相同。

请求侧优先级是：

- Transfer-Encoding: chunked
- Content-Length
- 无 body

这意味着，请求头里声明 chunked 后，就会忽略 Content-Length，直接进入 chunk size 和 chunk data 读取流程。请求侧不支持 connection close 结束 body 的处理路径。

响应侧优先级更复杂：

- 先判断是否属于无 body 响应
  - 1xx
  - 204
  - 304
- 再判断是否是 chunked
- 再判断是否声明了 Content-Length
- 最后才退化到 close-delimited body

对应状态切换可以概括成：

```text
READ_INITIAL
  -> READ_HEADER
  -> DONE_HEADER
     -> READ_CHUNK_SIZE
     -> READ_FIXED_LENGTH_CONTENT
     -> READ_VARIABLE_LENGTH_CONTENT
     -> READ_END
```

close-delimited response 的特点是：

- decoder 在 READ_VARIABLE_LENGTH_CONTENT 状态持续吐出 HttpContent
- 不会自己产出 LastHttpContent 作为结束信号
- 结束条件来自对端关闭连接

### 5.3 header、chunk 和 trailer 的处理方式

decoder 不会要求 header 必须一次性到齐。测试里明确覆盖了这些场景：

- initial line 可以跨多次接收拼接完成
- 某一行 header 没有收全时，已完整的 header 会先产出，未完整部分保留到下一批数据继续解析
- chunk data 可以跨包切分，decoder 会持续吐出多个 HttpContent

这意味着业务层不能假设“收到了 HttpHeaders 就代表后面不会再有 trailer”，也不能假设“一次网络包就是一条完整 HTTP 消息”。

### 5.4 transparent mode 的真实语义

transparent mode 不是一个简单布尔开关，它会触发整套 HTTP 状态清理。

HttpContext.switchTransparentMode(boolean enabled) 会：

- 切换 transparentMode 标志
- 重置 request decode state
- 重置 response decode state
- 重置 request encode state
- 重置 response encode state
- 清空 inboundErrorType

这一过程在 Upgrade 链路里更接近下面这个时序：

```text
Upgrade Handler        HttpServerDuplexe        HttpContext            Next Protocol
  |                         |                       |                        |
  | HttpThroughEvent.enable()                       |                        |
  |------------------------>|                       |                        |
  |                         |---------------------->| switchTransparentMode  |
  |                         |                       | reset req/resp state   |
  |                         |                       | reset encode state     |
  |                         |<----------------------| transparentMode=true   |
  |                         |----------------------------------------------->|
  |                         | subsequent HttpByteBuf passthrough             |
```

这里的关键点是状态重置先发生，后续透传才开始生效。因此 transparent mode 不是“继续保留半截 HTTP 状态再往后跑”。

这也是为什么 Upgrade 场景下不能只让某一个 handler 切换 transparent mode。只切一半链路，会导致收发两侧状态不一致。

### 5.5 聚合器如何构造 Full 消息

聚合器接收片段流，并按下面的过程组装：

```text
Start message
  -> append initial headers
  -> append content chunks
  -> append trailer headers
  -> emit FullHttpRequest 或 FullHttpResponse
```

聚合时有几个关键动作：

- body 内容保存在聚合缓冲区中，必要时升级为 CompositeByteBuf
- trailer headers 会追加到最终 headers 中
- 聚合完成后自动设置 Content-Length 为累计后的字节数
- 聚合完成后删除 Transfer-Encoding，避免保留 chunked 外观

如果上游已经直接给出 FullHttpRequest 或 FullHttpResponse，聚合器会直接透传，不会再二次聚合。

### 5.6 请求聚合器的自动响应策略

HttpRequestAggregator 不是纯粹的内存聚合器，它还承担一部分服务端协议保护职责。

规则如下：

- 如果 headers 里的 Content-Length 已经超过 maxContentLength，立即返回 413，并进入 discard 模式
- 如果累计 body 超过 maxContentLength，也返回 413，并丢弃后续内容直到消息结束
- 如果 Expect 不是 100-continue，返回 417，并丢弃本次请求
- 如果 Expect 是 100-continue，且长度检查通过，则先发送 100 Continue，再继续等待 body

Expect 和长度检查参与时，服务端聚合器的动作顺序更接近下面这个时序：

```text
Peer               HttpServerDuplexe        HttpRequestAggregator        App Handler
  |                      |                           |                         |
  | HttpRequest          |                           |                         |
  |--------------------->|-------------------------->| start request           |
  | HttpHeaders          |                           |                         |
  |--------------------->|-------------------------->| inspect headers         |
  |                      |                           | check Content-Length    |
  |                      |                           | check Expect            |
  |                      |<--------------------------| 100 / 417 / 413         |
  |<---------------------| auto response             |                         |
  | HttpContent          |                           |                         |
  |--------------------->|-------------------------->| append or discard       |
  | LastHttpContent      |                           |                         |
  |--------------------->|-------------------------->| emit FullHttpRequest    |
  |                      |                           |------------------------>|
```

这张图里最容易忽略的点有两个：

- 自动响应可能发生在业务层看到完整请求之前
- 进入 discard 模式后，请求剩余片段仍会被消费，但不会再继续组装为业务可用对象

这一行为在单元测试和 client-server flow 测试里都有覆盖，因此它属于当前实现的正式语义，不是附带行为。

### 5.7 错误恢复和 bad message 标记

HTTP/1.x codec 对协议错误并不都是“直接抛异常然后断开连接”。当前实现至少有三种处理方式：

- 直接抛出协议异常，由上游或 pipeline 决定后续处理
- 记录 inbound error type，重置状态后允许下一条消息继续解析
- 把当前消息标记成 bad message，再继续吐出已成功解析的部分

请求侧 chunk 流解析出错，但当前请求对象已经建立时，恢复路径更接近下面这个时序：

```text
Peer               HttpServerDuplexe        HttpRequestDecoder        HttpRequestAggregator
  |                      |                          |                           |
  | HttpRequest          |                          |                           |
  |--------------------->|------------------------->| create request            |
  | HttpHeaders          |                          |                           |
  |--------------------->|------------------------->| emit headers              |
  | HttpContent          |                          |                           |
  |--------------------->|------------------------->| emit content              |
  | bad chunk delimiter  |                          |                           |
  |--------------------->|------------------------->| mark bad message          |
  |                      |                          | emit LastHttpContent      |
  |                      |                          | clear accumulator         |
  |                      |                          | reset decode state        |
  |                      |----------------------------------------------------->| aggregate bad request
  |                      |                          |                           | emit bad FullHttpRequest
```

这条恢复路径的关键点是：

- 错误发生后不一定立刻断链，当前请求仍可能以 bad message 形式继续向后传播
- `clear accumulator` 和 `reset decode state` 发生在同一轮恢复里，后续新消息可以重新开始解析

例如请求侧 chunk delimiter 非法时，decoder 会把请求对象标记为 bad，并保留已经成功解析出来的 body 内容。后面的 request aggregator 再把它聚合成一个 isBad() 为 true 的 FullHttpRequest。

### 5.8 encoder 为什么不强制校验对象流顺序

request encoder 和 response encoder 的职责是把已有对象流序列化成字节，而不是重新做一次完整协议纠错。它们会根据当前 encode state 决定：

- 是直接写 body
- 还是写 chunk 前缀和后缀
- 还是写 last chunk 或 trailer terminator

但如果调用方给出的对象顺序本身不合理，例如先给 trailer 再给 content，encoder 仍会按输入顺序编码。测试里专门保留了这种“broken object stream passes through”的行为，用于证明 encoder 不会代替调用方修正语义。

## 6. 关键流

### 6.1 数据流

```text title='inbound'
socket bytes
  v
HttpServerDuplexe / HttpClientDuplexe
  v
  +--> HttpRequest / HttpResponse
  +--> HttpHeaders / LastHttpHeaders
  +--> HttpContent / TrailerHttpHeaders / LastHttpContent
  v
  +--> HttpServerDuplexeAggregator / HttpClientDuplexeAggregator
          v
          +--> FullHttpRequest / FullHttpResponse
                  v
               app handler
```

```text title='outbound'
app handler
  v
  +--> HttpRequest / HttpResponse / HttpHeaders / HttpContent
  v          or
  +--> FullHttpRequest / FullHttpResponse
  v
HttpServerDuplexe / HttpClientDuplexe
  v
socket bytes
```

排错线索：

- 收到的是多个 HttpContent，不是 FullHttpRequest，说明当前链路没有挂 aggregator
- trailer 消失了，先检查是否已经被聚合进最终 headers
- response 没有 LastHttpContent，先检查是否是 close-delimited body

### 6.2 异常流

```text
输入字节或片段对象
  v
  +--> HttpRequestDecoder / HttpResponseDecoder
  |      +--> 非法初始行 / 非法 header / 非法 chunk
  |      +--> reset decode state
  |      +--> optional mark bad message
  |      `--> continue next message or throw HttpProtocolException
  |
  `--> HttpRequestAggregator
         +--> Content-Length 超限
         +--> Expect 不支持
         +--> auto response 413 / 417
         `--> enter discard mode
```

排错线索：

- 服务端返回 100、417、413 时，先检查是否挂了 HttpRequestAggregator
- 同一连接前一条消息出错但后一条还能正常收，说明 decoder 走的是“状态重置后继续解析”路径
- FullHttpRequest.isBad() 为 true 时，不要误判为聚合器生成了错误对象，根因通常在 decoder 阶段

### 6.3 事件流

```text
upgrade handler / app handler
  v
  +--> HttpThroughEvent.enable()
  |      v
  |   all HTTP handlers
  |      +--> switch transparent mode
  |      +--> reset encode/decode state
  |      `--> HttpByteBuf passthrough starts
  |
  `--> HttpThroughEvent.disable()
         v
      all HTTP handlers
         +--> reset encode/decode state
         `--> HTTP parsing and encoding resume
```

排错线索：

- 开启 transparent mode 后还在发送 HttpRequest，会被 encoder 拒绝
- 关闭 transparent mode 后第一条 HTTP 消息解析异常，先检查是否有 handler 没收到切换事件
- duplexe 和 aggregator 混用时，transparent mode 事件需要覆盖整条 HTTP 链

## 7. 组件参考

### 7.1 HttpRequestDecoder

- 作用：把入站字节流解析为请求片段对象
- 关键参数：
  - maxInitialLineLength：request line 最大长度
  - maxHeaderSize：header 累计上限
  - maxChunkSize：单次 body 切片上限
- 何时使用：服务端接收入站请求，且业务需要保留流模式
- 相关上下文对象或事件：HttpContext、HttpThroughEvent

### 7.2 HttpResponseDecoder

- 作用：把入站字节流解析为响应片段对象
- 关键参数：
  - maxInitialLineLength：status line 最大长度
  - maxHeaderSize：header 累计上限
  - maxChunkSize：单次 body 切片上限
- 何时使用：客户端接收入站响应，或代理链路需要保留流模式
- 相关语义：支持 close-delimited body，识别 1xx、204、304 为无 body

### 7.3 HttpRequestEncoder

- 作用：把请求片段对象编码为出站字节流
- 关键语义：
  - chunkedEncoding 为 true 时输出 chunk size、chunk body 和结束块
  - transparent mode 下只接受 HttpByteBuf
- 对象所有权：消息一旦被 encoder 成功消费，encoder 会接管并释放源 HttpObject
- 何时使用：客户端出站请求，或代理链路中的 request 编码半边

### 7.4 HttpResponseEncoder

- 作用：把响应片段对象编码为出站字节流
- 关键语义：
  - FullHttpResponse 可直接走同一条片段编码路径
  - transparent mode 下只接受 HttpByteBuf
- 对象所有权：消息一旦被 encoder 成功消费，encoder 会接管并释放源 HttpObject
- 何时使用：服务端出站响应，或代理链路中的 response 编码半边

### 7.5 HttpRequestAggregator

- 作用：把请求片段流聚合为 FullHttpRequest
- 关键参数：
  - maxContentLength：允许聚合的最大 body 大小
- 何时使用：服务端业务只想处理完整请求时
- 常见自动响应：100 Continue、417 Expectation Failed、413 Request Entity Too Large

### 7.6 HttpResponseAggregator

- 作用：把响应片段流聚合为 FullHttpResponse
- 关键参数：
  - maxContentLength：允许聚合的最大 body 大小
- 何时使用：客户端业务只想处理完整响应时
- 关键语义：合并 trailer、重写 Content-Length、移除 Transfer-Encoding

### 7.7 HttpServerDuplexe 与 HttpClientDuplexe

- 作用：提供服务端和客户端的常规 HTTP/1.x 双工入口
- 何时使用：优先作为大多数 HTTP/1.x pipeline 的基础入口
- 关键区别：
  - HttpServerDuplexe = request decoder + response encoder
  - HttpClientDuplexe = response decoder + request encoder

### 7.8 HttpServerDuplexeAggregator 与 HttpClientDuplexeAggregator

- 作用：把双工方向上的 request 和 response 聚合器打包成一个节点
- 何时使用：希望整条链路都处理 FullHttpRequest 或 FullHttpResponse 时
- 关键限制：它们消费的是 HttpObject，不消费原始 ByteBuf

## 8. 使用注意事项

- method、uri、status、header value 这类 metadata 是稳定字符串；真正带引用计数语义的是 body payload，也就是 HttpContent.content() 返回的 ByteBuf。
- 请求或响应对象一旦成功交给 encoder，调用方就不要再手工 release 原对象；否则容易和 encoder 的释放动作重叠。
- 如果业务层要缓存 body 或跨线程使用内容，应先 retain 或复制相关 ByteBuf，再把对象继续交给后续 handler。
- 不要把 duplexe 当成聚合器使用。它只负责 codec 组合，不会自动产出 FullHttpRequest 或 FullHttpResponse。
- 不要把 duplexe aggregator 当成 codec 使用。它只消费 HttpObject，不解析原始字节。
- 请求侧 chunked 优先级高于 Content-Length。两者同时出现时，当前实现会按 chunked 处理。
- 请求侧不能依赖 connection close 判定 body 结束。没有 Content-Length 且没有 chunked 时，请求默认视为无 body。
- 响应侧如果依赖 close-delimited body，业务层必须结合连接关闭来识别消息结束，而不是等待 LastHttpContent。
- 开启 transparent mode 后，HTTP handler 的输入输出对象会切成 HttpByteBuf。此时继续发送 HttpRequest 或 HttpResponse 属于错误用法。
- 关闭 transparent mode 会重置当前编解码状态。不要指望在模式切换前后拼接同一条半截 HTTP 消息。
- 聚合器会把 trailer 合并到最终 headers 中。如果业务需要保留 trailer 的边界语义，不要先聚合。
- HttpRequestAggregator 可能主动发送 100、417、413。链路上出现这些响应时，先确认这是不是预期的协议保护行为。
- encoder 不会纠正错误的对象顺序。片段输出顺序由调用方自己保证。
