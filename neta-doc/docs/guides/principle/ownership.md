---
sidebar_position: 5
title: 引用所有权
description: 说明 Neta Handler、ProtoQueue、HTTP/WebSocket codec 与 ByteBuf 在引用计数场景下的所有权边界。
---

这份文档先回答一个原则问题，再回答具体到 Handler、ProtoQueue、HTTP codec、WebSocket codec 时应该怎么判断。

核心问题只有一个：

当某一层从队列里拿到引用计数对象后，谁负责释放它？

在当前版本里，这个问题还要再往前追一层：

对象在还没被拿走之前，到底归谁管？

答案是：先归队列，取走后才归调用方。

## 1. 原则

Neta 中的“所有权”不是抽象概念，它对应的是引用计数里的那一份释放责任。

四条原则如下：

1. 谁继续透传原对象，谁就不新增所有权。
2. 谁消费原对象并产出替代对象，谁就接管原对象释放责任。
3. 谁想让同一份底层数据被多个对象同时持有，谁就必须显式 retain，并为每一份引用安排对等 release。
4. 对象只要还在 `ProtoQueue` 里，所有权就仍然归队列；一旦 `take` 成功，所有权立即转移给调用方；`peek` 不转移；`skip` 表示由队列直接丢弃并释放。

这四条原则的目标不是“让每一层都去猜谁该 release”，而是让释放责任和对象流转方式保持一一对应。

判断顺序如下：先看对象是否还在队列里，再看对象是否继续原样向下游传播，再看当前层是否把它转换成新对象，最后再看是否存在共享引用。释放责任与这几个判断保持一致。

## 2. 队列边界先行

在讨论某个 Handler 要不要 `release` 之前，先判断对象是否已经离开 `ProtoQueue`。

当前 `ProtoQueue` 的语义已经固定为：

1. `offer` 成功即进入队列，所有权交给队列。
2. `take` 成功即移出队列，所有权交给调用方。
3. `peek` 只是观察，不转移所有权。
4. `skip` 是丢弃，不是“伪 take”；若对象仍归队列所有，队列负责释放它。
5. 关闭路径上的 `afterClose` / `clearAndClose` 会统一回收仍然滞留在队列中的对象。

这意味着两个很重要的结论：

- 不能再把 `peek + skip` 当成“先看一眼再转移所有权”的写法。
- 不能假设“只要我看到了对象，我就天然应该 release 它”。

谁 release，取决于对象有没有真正离开队列，以及当前层是不是终止了它的继续传播。

## 3. 三种典型模式

### 1. 透传

```text
SRC ---- take ----> msg ----------------------> DST
                        |
                        +---- same instance ----+
```

适用场景：

- 路由器直接转发消息
- transparent mode 下把包装对象原样向后传递
- 非目标类型对象直接 passthrough

规则：

- 不要额外 release 原对象。
- 不要偷偷 retain，除非确实要共享生命周期。
- 如果对象只是通过 `peek` 看了一眼，那它仍归队列所有，当前层不能把 `skip` 当作“转交给下游后的收尾动作”。

### 2. 消费后重包装

```text
SRC ---- take ----> ByteBuf ---- decode ----> String ----> DST
                        |
                        +---- consumed ----> release by handler
```

适用场景：

- ByteBuf 解码成 String
- HTTP staged object 聚合成 FullHttpRequest / FullHttpResponse
- WebSocketFrame 编码成 HttpByteBuf

规则：

- 新对象一旦建立完成，原对象应由当前 Handler 释放。
- 推荐把 release 放在 finally 里，避免异常路径漏掉。
- 这里的前提是原对象已经通过 `take` 真正转移到当前 Handler 手里；如果对象还留在队列里，就不该由 Handler 手工释放。

### 3. 共享

```text
SRC ---- take ----> ByteBuf ---- retain ----> shared downstream object
                        |
                        +---- current owner keeps one ref
```

适用场景：

- 需要让原对象和派生对象同时活着
- CompositeByteBuf 收纳组件
- 自动回复控制帧时复用 payload

规则：

- retain 几次，就需要对应几次 release。
- 共享前先明确谁持有哪一份引用。

## 4. 判断顺序

实际写代码时，按这个顺序判断最稳：

1. 对象是不是还在 `ProtoQueue` 里。
2. 如果已经离开队列，它是通过 `take` 转移出来的，还是只是被 `peek` 观察到了。
3. 当前层是不是把同一个对象继续往后传。
4. 如果不是，当前层是不是已经生成了新的替代对象。
5. 如果原对象和新对象都要继续活着，是否已经显式 retain。
6. 如果当前层消费了原对象，是否存在 finally 或等价清理路径负责 release。

这套顺序比“记住每个类该不该 release”更稳定，因为它直接贴着对象流转本身判断。

## 5. 在 Neta 里的落地点

### ProtoHandler

ProtoHandler 的通用约束已经明确：

- 消费且不透传原对象的 Handler，需要接管引用生命周期。
- 但这个前提仅成立在它通过 `take` 拿到了对象之后；如果只是 `peek`，那对象仍然归队列所有。

这意味着 ProtoHandler 的默认心智模型不是“谁拿到对象谁 release”，而是“谁终止了原对象的传播，谁 release”。

再更精确一点：

- 队列里未取出的对象，由队列负责。
- Handler `take` 之后未继续透传的对象，由 Handler 负责。
- Handler 只是 `peek` 到的对象，依然由队列负责，除非后续显式 `take`。

### neta-core 常见 Handler

- StringDecoder：消费 ByteBuf，输出 String，释放输入 ByteBuf。
- StringEncoder：消费 String，输出新 ByteBuf，不涉及 release 输入。
- 各类 FrameHandler：输出的新帧 ByteBuf 归下游所有；若通过 `take` 消费输入 ByteBuf，则由当前 Handler 接管其释放；若对象仍留在队列中，则仍由队列生命周期管理。

### neta-codec-http 常见 Handler

- HttpRequestEncoder / HttpResponseEncoder：消息一旦被 encoder 消费，encoder 接管并释放源 HttpObject。
- WebSocketFrameEncoder：frame 一旦被编码，encoder 接管并释放源 frame。
- HttpRequestAggregator / HttpResponseAggregator：消费 staged HTTP 对象，输出 FullHttpRequest / FullHttpResponse，并释放被吸收的中间对象。
- HttpServerAggregatorDuplexe / HttpClientAggregatorDuplexe：双向封装方向明确的 HTTP 聚合器，遵循同样的接管释放规则。
- WebSocketOutboundHandler：消费 `WebSocketMessage` 分片流，输出 `WebSocketFrame`，并接管源消息生命周期；`PING/PONG` 也通过 `PingWebSocketMessage` / `PongWebSocketMessage` 编码，主动 `CLOSE` 仍属于 frame 层。若启用了 `maxFramePayloadLength` 自动分帧，一条源 `WebSocketMessage` 可能被拆成多个 frame，但源消息仍只由 outbound handler 统一接管释放。
- WebSocketInboundHandler：消费 `WebSocketFrame`，输出 `WebSocketMessage` 分片流，并释放被吸收的 frame；入站 `PING` 会自动回复 `PongWebSocketMessage`，入站 `PONG` 会转成 `PongWebSocketMessage`，`CLOSE` 会通过 user event 暴露。若启用了消息聚合，handler 会在内部暂存并拼接多个 frame，最终输出一条聚合后的 `WebSocketMessage`，聚合过程中被吸收的 frame 仍由 inbound handler 负责释放。
- WebSocketMessageDuplexer：内部复用一对 `WebSocketInboundHandler` / `WebSocketOutboundHandler`；若 duplexer 开启了入站聚合或出站自动分帧，其对象所有权规则与对应 handler 完全一致，不会额外改变消息或 frame 的释放边界。
- WebSocketHandshakeRequest：只在服务端 authorizer 阶段作为请求快照存在，不进入 `ProtoRcvQueue` / `ProtoSndQueue`；握手完成后会由握手实现负责释放。
- WebSocketCloseEvent：属于 user event，不进入 `ProtoRcvQueue` / `ProtoSndQueue`；它沿 `fireUserEvent(...)` 事件流传播，不参与消息对象的引用接管规则。

### 关闭路径

关闭时有两段责任：

1. `onClose(...)` 给 Handler 一个收尾机会。
2. `afterClose()` 和队列自身的 `clearAndClose()` 负责清理仍滞留在队列里的对象。

所以关闭阶段不应该假设每个 Handler 都会自行把残留消息释放干净。只要对象还留在 `ProtoQueue` 里，最终就应由队列关闭路径兜底处理。

## 6. 推荐写法

### 先 take，再消费

```java
Object msg = src.takeMessage();
if (msg == null) {
    return ProtoStatus.Next;
}

boolean consumed = false;
try {
    Object out = transform(msg);
    consumed = true;
    dst.offerMessage(out);
    return ProtoStatus.Next;
} finally {
    if (consumed && msg instanceof ReferenceHolder) {
        ((ReferenceHolder) msg).release();
    }
}
```

### 只观察时用 peek，不做伪转移

```java
Object msg = src.peekMessage();
if (msg == null) {
    return ProtoStatus.Next;
}

if (!canHandle(msg)) {
    return ProtoStatus.Next;
}

msg = src.takeMessage();
if (msg == null) {
    return ProtoStatus.Next;
}

// from here, ownership is really transferred
```

推荐理由：

- `peek` 只用于判断。
- 真正要消费时，再用 `take` 完成所有权转移。
- 不要写成 `peek` 后直接把对象传下游，再 `skip` 删除队列项；那不是转移，是丢弃。

## 7. 常见错误

### 错误 1：消费后忘记 release

```text
ByteBuf -> String

结果: 原 ByteBuf 无人持有，但 refCnt 还在，形成泄漏。
```

### 错误 2：透传后又 release

```text
ByteBuf -> same ByteBuf -> downstream
           \---- current handler also release

结果: 下游拿到悬空引用。
```

### 错误 3：共享但没 retain

```text
payload 同时交给两个对象
但只保留了一份 refCnt

结果: 任意一方先 release，另一方立即变成悬空引用。
```

### 错误 4：把 peek + skip 当成转移所有权

```text
peek 到队列里的 ByteBuf
把这个对象交给下游
然后调用 skip 删除原队列项

结果: skip 会按“丢弃队列拥有对象”处理，可能直接 release，
下游拿到的是已释放或即将失效的引用。
```

## 8. 排查建议

- 先看对象出问题时，是还留在队列里，还是已经通过 `take` 转移出去了。
- 看 onMessage 里是否 take 了引用计数对象。
- 看它是否仍然把同一个实例向下游传递。
- 如果代码是 `peek` 后又 `skip`，优先怀疑所有权判断写错了。
- 如果没有透传，而是变成了别的对象，就检查 finally 里是否 release。
- 如果一个 payload 被两个对象共同持有，就检查是否有对等 retain。
