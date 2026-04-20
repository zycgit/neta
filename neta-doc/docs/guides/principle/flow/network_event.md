---
id: network-event
sidebar_position: 5
title: 网络事件
description: 说明 Neta 中网络事件的对象模型、触发入口、传播方向、分支穿透规则，以及它和普通消息流、PlayLoad 订阅的区别。
---

## 网络事件

在 Neta 里，网络事件是一条独立于普通消息流的旁路信号。

普通消息走的是 `ProtoRcvQueue` / `ProtoSndQueue` 这条数据链，网络事件走的是 `SoEvent` + `onEvent(...)` 这条事件链。两者可以在同一条连接上同时存在，但它们解决的问题不同：

- 普通消息负责承载业务数据和协议数据
- 网络事件负责表达状态变化、控制信号和协议侧通知

典型例子包括：

- TLS 握手完成，发布 `SslHandshakeEvent`
- 本地优雅关闭前，发布 `SoCloseEvent`
- WebSocket 收到或发送关闭帧，发布 `WebSocketCloseEvent`
- HTTP/1.x 编解码器切换到透明模式，发布 `HttpThroughEvent`
- HTTP/2 请求主动发一个 PING，发布 `Http2PingEvent`

## 事件对象模型

Neta 的网络事件由三层组成：

```text
SoEvent
  ├── getSource()      事件源通道
  ├── getEventType()   事件类型标记
  └── getData()        事件负载

SoEventData
  └── 可选的负载标记接口

SoEventObject
  └── 默认事件包装实现
```

含义如下：

- `SoEvent` 是传播中的事件包装对象
- `getEventType()` 用来做事件匹配，不要求负载对象本身一定实现某个父接口
- `getData()` 才是真正的事件负载，例如 `SslHandshakeEvent`、`SoCloseEvent`
- `SoEventData` 是可选的标记接口，便于框架分类和管理事件负载
- `SoEventObject` 是当前默认实现，负责把源通道、类型和负载绑在一起

这意味着：

- 事件传播时真正传的是 `SoEvent`
- 业务处理器通常通过 `event.getData()` 读取实际负载
- 事件类型的匹配既可以看 `event.getEventType()`，也可以看 `event.getData()` 的实际类型

## 触发入口

Neta 里有两个常用触发入口。

### 1. 连接级入口

从连接对象直接触发：

```java
channel.fireEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
```

这条入口适合：

- 从业务代码主动向整条连接投递一个事件
- 在当前不处于某个处理器回调内部时，直接从通道层触发事件

它最终会进入 `NetChannel.notifyEvent(...)`，再交给 `SoContextService.notifyRcvEvent(...)` 或 `notifySndEvent(...)` 推进到协议栈中。

### 2. 协议栈内部入口

从 `ProtoContext` 当前栈位触发：

```java
context.fireEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
context.fireEventReverse(HttpThroughEvent.class, HttpThroughEvent.disable());
context.fireEventRcv(WebSocketCloseEvent.class, closeEvent);
context.fireEventSnd(Http2PingEvent.class, pingEvent);
```

这条入口适合：

- 当前处理器要把控制信号交给后续处理器
- 当前处理器要反向通知前一层协议切换状态
- 当前处理器需要显式指定事件按 RCV 或 SND 方向传播

它最终会进入 `ProtoContextService.fireEvent0(...)`，并从当前栈位的下一个节点开始传播，而不是重新从整条链的起点开始。

## 四个触发方法

`ProtoContext` 上有 4 个事件触发方法：

- `fireEvent(...)`：沿当前数据流方向传播
- `fireEventReverse(...)`：沿当前数据流反方向传播
- `fireEventRcv(...)`：强制按 RCV 方向传播
- `fireEventSnd(...)`：强制按 SND 方向传播

规则如下：

| 当前回调所处方向 | `fireEvent(...)` | `fireEventReverse(...)` |
| --- | --- | --- |
| `RCV` | `head -> tail` | `tail -> head` |
| `SND` | `tail -> head` | `head -> tail` |

另外两条强制方向 API 与当前回调方向无关：

- `fireEventRcv(...)` 总是 `head -> tail`
- `fireEventSnd(...)` 总是 `tail -> head`

## 从当前栈位继续传播

`ProtoContext.fireEvent(...)` 不是从整条链重新开始广播。

它会以“当前处理器的下一个节点”为起点继续传播。

主管线示意：

```text
RCV: [A] -> [B*] -> [C]
                fireEvent()
路径: B -> C

SND: [A] <- [B*] <- [C]
                fireEvent()
路径: B -> A
```

这个行为的意义是：

- 当前处理器可以把事件交给后续处理器继续处理
- 当前处理器不会再次收到自己刚刚发出的同一条事件
- 事件传播路径和当前数据处理位置保持一致

## 分支与分区中的跨界传播

网络事件在路由分支和分区子管线里不会被困住。

如果事件在当前分支或分区里已经走到边界，Neta 会把它继续接到父管线中对应的位置上。

### 路由分支

```text
RCV: Main [A] -> [Router] -> [Z]
                    |
          Branch     [B] -> [C*]

fireEvent() 路径: C -> Z
```

```text
SND: Main [A] <- [Router] <- [Z]
                    |
          Branch    [B*] <- [C]

fireEvent() 路径: B -> A
```

### 分区子管线

```text
RCV: Main [A] -> [Partition] -> [Z]
                       |
               Part-1  [B] -> [C*]

fireEvent() 路径: C -> Z
```

```text
SND: Main [A] <- [Partition] <- [Z]
                       |
               Part-1 [B*] <- [C]

fireEvent() 路径: B -> A
```

如果分区 selector 对当前事件返回 `null`，该事件不会进入任何分区子管线，也不会被自动归一化到默认分区；它会继续沿父管线中的分区节点后续位置传播。

这个行为来自 `ProtoContextService.fireEventUpward(...)`：

- 在 `RCV` 方向，事件会回到父管线中当前路由器或分区器之后的位置
- 在 `SND` 方向，事件会回到父管线中当前路由器或分区器之前的位置
- 如果父管线本身也是嵌套分支，事件会继续逐层向上穿透

这也是为什么网络事件可以在复杂的嵌套路由或分区结构中仍然保持完整传播。

## 与普通消息流的区别

网络事件和普通消息有 4 个关键差异。

### 1. 不进入消息队列

网络事件不进入 `ProtoRcvQueue` / `ProtoSndQueue`。

这意味着：

- 它不占用普通消息槽位
- 它不参与 `offerMessage(...)` / `takeMessage(...)` 这一套所有权转移
- 它不属于消息背压控制的一部分

### 2. 不走 `onMessage(...)`

普通消息进入 `onMessage(...)`，网络事件进入 `onEvent(...)`。

这意味着协议处理器可以把：

- 数据处理逻辑留在 `onMessage(...)`
- 状态变化和控制信号处理留在 `onEvent(...)`

### 3. 不会自动出现在 `PlayLoad` 订阅总线里

`PlayLoad` 订阅总线主要描述消息和错误。

`SoEvent` 走的是协议栈内部事件传播链，处理器通过 `onEvent(...)` 感知，不通过 `PlayLoadListener` 订阅。

### 4. 触发目标是“感兴趣的协议层”

事件的设计目标不是向业务广播一条消息，而是让特定协议层响应一个状态变化。

例如：

- `HttpThroughEvent` 让 HTTP codec 切换工作模式
- `SoCloseEvent` 让下游协议层在优雅关闭前发最后的告别报文
- `Http2PingEvent` 让 HTTP/2 编码层发出 PING frame

## 框架自己也在使用网络事件

网络事件不是只给业务代码用的接口。Neta 自己就在大量使用它来驱动协议层行为。

### SoCloseEvent

`SoCloseEvent` 是一个典型例子。

在本地调用优雅关闭时，`SoCloseTask` 会先等待发送队列排空，然后沿 SND 方向发布 `SoCloseEvent`，让协议层有机会补发收尾报文，例如：

- TLS `close_notify`
- WebSocket close frame

等这些收尾数据再次排空后，框架才执行真正关闭。

### SslHandshakeEvent

TLS 握手完成后，`SslHandle` 会发布 `SslHandshakeEvent`。

上层处理器可以通过它读取：

- 当前连接的 `SslContext`
- ALPN 协商结果
- 当前是 client 还是 server
- 证书与对端信息

### HttpThroughEvent

`HttpThroughEvent` 用来切换 HTTP/1.x codec 的透明传输模式。

这类事件本质上不是业务消息，而是协议层配置切换命令，因此更适合走事件链，而不是消息链。

### WebSocketCloseEvent

`WebSocketCloseEvent` 用来表达 WebSocket close control frame 对应的关闭语义。

它不会进入消息队列，而是沿事件链传播，便于上层把关闭码和原因文本当成控制信号处理。

### Http2PingEvent

`Http2PingEvent` 表达“请求发一个 HTTP/2 PING frame”。

它本质上是在要求编码层发一个控制帧，也属于典型的网络事件场景。

## 什么时候应该用网络事件

适合用网络事件的场景：

- 某个协议层要通知前后层发生了状态变化
- 某个协议层要请求另一层切换工作模式
- 某个控制信号不应该进入普通消息队列
- 某个通知只对协议栈有意义，不是业务正文的一部分

不适合用网络事件的场景：

- 业务正文消息本身
- 需要参与正常编解码和聚合的数据
- 需要进入 `ProtoRcvQueue` / `ProtoSndQueue` 管理的数据对象
- 需要通过 `PlayLoad` 订阅总线暴露给外部监听器的普通数据

一个简单判断方法是：

- 如果它像“报文正文”，用消息流
- 如果它像“控制信号”或“状态变化”，用事件流

## 推荐写法

### 在处理器里接收事件

```java
ctx.addLast("app", new ProtoHandler<ByteBuf, ByteBuf>() {
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) throws Throwable {
        if (event.getData() instanceof SslHandshakeEvent) {
            SslHandshakeEvent handshake = (SslHandshakeEvent) event.getData();
            System.out.println(handshake.getContext().getApplicationProtocol());
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context,
            ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
        return ProtoStatus.Next;
    }
});
```

### 在当前栈位向后传播事件

```java
context.fireEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
```

### 在当前栈位反向通知前一层

```java
context.fireEventReverse(HttpThroughEvent.class, HttpThroughEvent.disable());
```

### 强制从发送方向传播

```java
context.fireEventSnd(Http2PingEvent.class, new Http2PingEvent(1));
```

## 和其他原理文档的关系

- 处理器生命周期与 `onEvent(...)` 的位置关系，见 [handler.md](../handler/about.md)
- 事件在路由分支里的传播边界，见 [routing_pipeline.md](../pipline/routing.md)
- 事件不进入消息队列这一点和对象所有权边界的关系，见 [ownership.md](../handler/ownership.md)

## 小结

Neta 的网络事件是一套独立于消息流的协议控制通道。

它的核心特征是：

- 用 `SoEvent` 表达事件包装对象
- 用 `onEvent(...)` 接收事件
- 用 `fireEvent(...)` 系列方法在当前栈位触发传播
- 在分支和分区里可以继续跨回父管线
- 不进入消息队列，也不参与普通消息所有权转移

当你需要在协议层之间传递控制信号、状态变化和协议通知时，应该优先考虑这条事件链。
