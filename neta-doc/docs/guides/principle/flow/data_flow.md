---
id: data-flow
sidebar_position: 2
title: 数据流
description: 说明 Neta 协议栈中的数据流如何在处理器之间传播，以及 ProtoRcvData、ProtoRcvQueue、ProtoRcvQueueView、ProtoSndData、ProtoSndQueue、ProtoSndQueueView 在其中分别承担什么职责。
---

## 数据流

在 Neta 里，数据流不是一句“消息从上游走到下游”就能讲清楚的东西。

它真正描述的是下面这组协作关系：

- 数据什么时候进入协议栈。
- 数据在每一层处理器之间以什么形式移动。
- 数据是在原地被观察，还是被真正取走。
- 数据暂时离开主管线进入带有名称的局部容器后，为什么仍然要继续占用容量。
- 当这一整套容器体系快满了以后，背压是怎么成立的。

如果只看 Handler 回调，很容易误以为数据流只是 `onMessage(...)` 的调用顺序。

但从协议栈运行机制看，真正的主线其实是：

```text
network read / app write
          |
          v
  queue-based data staging
          |
          v
    handler onMessage(...)
          |
          v
  next queue / named view / transport
```

也就是说，Handler 只是处理逻辑的承载体，真正让数据流稳定可控的基础，是那一整套接收容器与发送容器模型。

## 为什么数据流一定要先看容器

Neta 的协议栈不是“处理器拿到一个对象，处理完再直接交给下一个处理器”这种临时回调串联模型。

它更接近下面这种结构：

```text
       upstream side                         downstream side

   ProtoRcvQueue / View  --->  Handler  --->  ProtoSndQueue / View
           ^                                    |
           |                                    v
      readable data                       writable data
```

这意味着数据在每个处理器边界上，都不是以“裸对象”存在，而是先进入一个容器，再由处理器决定怎么读、怎么转、怎么写。

这样设计有三个直接收益：

- 所有权边界清楚：还在容器里的数据归容器；取走后才归调用方。
- 容量边界清楚：到底还能不能继续写，由容器体系统一判断。
- 恢复和背压有抓手：当下游写不进去时，协议栈知道问题发生在容器层，而不是某个随意散落的局部变量里。

所以理解数据流，先不要急着背接口方法，而是先建立一个判断：

- `ProtoRcv*` 负责“当前层能读到什么”。
- `ProtoSnd*` 负责“当前层能写出什么”。
- `Data` 是最基础的读写能力。
- `Queue` 是主容器。
- `QueueView` 是从主容器派生出来、带有名称的局部容器视图。

## 六个接口在体系里的位置

从结构上看，六个接口刚好分成两组，且每组都是三层。

### 接收侧

```text
ProtoRcvData<T>
    |
    +-- 定义最基础的读取语义
    |      queueSize / hasMore / take / peek / skip
    |
ProtoRcvQueue<T>
    |
    +-- 在读取语义上增加主队列能力
    |      getCapacity / drainToQueue / queueNames / queueView
    |
ProtoRcvQueueView<T>
           |
           +-- 表示从接收主队列派生出来的带有名称的局部视图
                  getKey / discard / returnToHead / returnToTail
```

### 发送侧

```text
ProtoSndData<T>
    |
    +-- 定义最基础的写入语义
    |      slotSize / hasSlot / offer
    |
ProtoSndQueue<T>
    |
    +-- 在写入语义上增加主队列能力
    |      getCapacity / newSub / subKeys / hasSub
    |
ProtoSndQueueView<T>
           |
           +-- 表示从发送主队列派生出来的带有名称的局部视图
                  getKey / discard / push
```

如果只看接口名，可以把它们理解成两条平行数据链：

```text
RCV side                               SND side

ProtoRcvData      <---- read ----      ProtoSndData
ProtoRcvQueue                         ProtoSndQueue
ProtoRcvQueueView                     ProtoSndQueueView
```

但更准确的理解方式是：

- 接收侧容器负责承接“当前层已经能看到的数据”。
- 发送侧容器负责承接“当前层已经决定往下游送出的数据”。
- 当前层处理器只是在两边之间做搬运、转换、拆分、聚合或调度。

## 主容器和带有名称的容器是什么关系

这部分是理解整个数据流机制的核心。

Neta 不是简单地把主队列再复制一份子队列出来，而是允许主容器派生出带有名称的局部容器视图。

可以先看接收侧：

```text
ProtoRcvQueue(main)
   |
   +-- queueView("A")
   |
   +-- queueView("B")
   |
   `-- queueView("C")
```

发送侧同理：

```text
ProtoSndQueue(main)
   |
   +-- newSub("A")
   |
   +-- newSub("B")
   |
   `-- newSub("C")
```

这些带有名称的容器有两个非常重要的特征。

### 1. 它们不是独立容量空间

主容器和带有名称的容器共享同一份总容量。

也就是说：

- 数据还在主容器里，占用总容量。
- 数据被转移到带有名称的容器里，仍然占用同一份总容量。
- 数据只有在真正被取走、发送完成、丢弃或释放后，容量才会被腾出来。

这意味着把数据挪到视图中不是“藏起来”，也不是“绕开容量限制”，而只是改变它当前所在的位置和后续处理路径。

### 2. 它们是局部调度空间，不是旁路缓存

带有名称的容器存在的意义，不是让处理器偷偷保留一部分内部消息，而是让协议栈在容器体系内部显式表达“这批数据暂时进入某个局部分支”。

这类局部视图特别适合这些场景：

- 某一批消息需要暂时从主管线拿出来，等条件满足后再回放。
- 某个分区或某个路由分支需要维护自己的局部顺序。
- 某个发送分组需要先在局部组织完成，再一次性推回主发送队列。

从设计上看，它更像“在同一套容量账本上的局部工作区”，而不是新的背压边界。

## 共享容量为什么是背压的基础

如果主容器和带有名称的容器各自拥有独立容量，那么协议栈就会出现一个严重问题：

- 主队列满了，处理器把数据转移到视图中。
- 视图又被当成独立容量空间继续塞数据。
- 整个系统表面上还在运行，但真实持有的数据已经超出最初设定的上限。

这样背压就会失效。

Neta 现在采用的是另一种模型：

```text
shared capacity = main queue data + all named view data
```

用图表示就是：

```text
             one shared capacity budget

    +------------------------------------------------+
    |                                                |
    |   main queue   +   view A   +   view B   + ... |
    |                                                |
    +------------------------------------------------+
```

因此真正的背压判断不是：

- “主队列里还有多少元素？”

而是：

- “整套接收容器或发送容器体系当前总共持有了多少数据？”

这就是为什么文档里要反复强调：

- 带有名称的数据容器和主容器共享同一个数据容量。
- 这是容量背压成立的基础。

只要这一点成立，处理器就不能通过把数据挪来挪去绕开背压。

## 数据流在协议栈里是怎么跑的

把接口分工和共享容量模型放在一起后，数据流的典型运行过程就比较容易理解了。

### 入站主线

```text
socket read
   |
   v
NetChannel / transport
   |
   v
tail ProtoRcvQueue
   |
   v
Handler-1 onMessage(...)
   |
   +--> take from rcv
   +--> process / transform / route
   +--> offer into snd
   |
   v
next handler rcv side
```

如果拆开看单个处理器边界，逻辑更接近：

```text
ProtoRcvQueue or View
      |
      | take / peek / skip
      v
  current handler
      |
      | offer
      v
ProtoSndQueue or View
```

这里最重要的不是“处理器做了什么转换”，而是“它对容器做了哪一种动作”。

### 取走

```text
src.takeMessage(...)
```

含义：

- 数据离开当前接收容器。
- 所有权转移给当前处理器。
- 之后如果它没有继续向下游透传，就应由当前处理器负责后续生命周期。

### 观察

```text
src.peekMessage(...)
```

含义：

- 只观察，不移除。
- 所有权仍在容器里。
- 这种写法适合“先判断，再决定要不要真正取走”。

### 丢弃

```text
src.skipMessage(...)
```

含义：

- 这是容器直接丢弃自己还持有的数据。
- 容器负责释放资源。
- 它不是“假装处理完了”的替代写法。

### 转移到带有名称的接收容器

```text
src.drainToQueue("partition-A", cnt)
```

含义：

- 数据从主接收队列进入某个带有名称的接收视图。
- 位置改变了，但共享容量关系不变。
- 这批数据后续可以在局部视图里继续读取，也可以再回放到主队列。

### 写入发送主队列或带有名称的发送容器

```text
dst.offerMessage(...)
dst.newSub("partition-A").offerMessage(...)
```

含义：

- 数据进入发送容器体系。
- 写入成功后所有权转移给发送容器。
- 无论写进主发送队列还是带有名称的发送视图，消耗的都是同一份共享容量。

## 一个典型处理器到底在做什么

如果忽略具体协议细节，一个典型处理器的本质工作通常只有 4 类。

### 1. 透传

```text
rcv.take -> same object -> snd.offer
```

适合：

- 当前层只是做顺序推进。
- 当前对象已经是下游能直接处理的类型。

### 2. 转换

```text
ByteBuf -> frame
frame   -> protocol object
protocol object -> business message
```

适合：

- codec 层。
- 聚合器。
- 协议桥接层。

### 3. 拆分或聚合

```text
one input  -> many outputs
many input -> one output
```

适合：

- 帧拆分。
- chunk 聚合。
- HTTP、WebSocket、Mail 一类协议对象组装。

### 4. 调度

```text
main queue <-> named view
```

适合：

- 路由。
- 分区。
- 暂存后重排。
- 延后处理与回放。

这里要特别注意：

调度不是“额外存一份”，而是“在同一套共享容量容器体系里重新安排位置”。

## 为什么文档不建议把这六个接口当作方法表来背

如果把这六个接口拆成 20 多个方法逐个记，最后往往会得到一堆零散记忆：

- `takeMessage` 是做什么的。
- `queueView` 是做什么的。
- `newSub` 是做什么的。

但这并不能帮助真正理解协议栈。

更有效的理解方式是按下面三层来记。

### 第一层：方向

- `ProtoRcv*` 看读。
- `ProtoSnd*` 看写。

### 第二层：层级

- `*Data` 是基础读写语义。
- `*Queue` 是主容器。
- `*QueueView` 是从主容器派生出来、带有名称的局部视图。

### 第三层：边界

- 还在容器里，所有权归容器。
- 进入视图，不等于脱离容量约束。
- 主容器和带有名称的容器共享同一份总容量。
- 这份共享容量是背压判断的基础。

当这三层建立起来以后，具体方法名就只是这套机制的操作入口，而不是孤立知识点。

## 一个更接近真实运行时的总图

可以把 Neta 的数据流总结构理解成下面这样：

```text
                            inbound

transport
   |
   v
ProtoRcvQueue(main)
   |
   +--> queueView("A")
   |
   +--> queueView("B")
   |
   v
current handler
   |
   v
ProtoSndQueue(main)
   |
   +--> newSub("A")
   |
   +--> newSub("B")
   |
   v
next handler / transport


shared capacity rule

RCV total = rcv main + all rcv views
SND total = snd main + all snd views
```

这张图对应的含义是：

- 处理器不是孤立拿对象，而是在两侧容器之间搬运数据。
- 接收侧和发送侧都可以有主容器与带有名称的局部视图。
- 这些局部视图不是额外容量池，而是同一套容量账本里的局部工作区。

## Handler 主动发起发送时看什么

前面讲的是“数据已经进入当前层以后，怎么在容器之间流动”。

但在真实协议实现里，还有另一类很常见的动作：

- 当前层在 `onMessage(...)` 里主动回包。
- 当前层在 `onEvent(...)` 里生成控制帧。
- 当前层在 `onError(...)` 里发送协议级错误响应。

这时候经常会碰到两个方法：

- `ProtoContext.sendData(...)`
- `ProtoContext.sendEncoded(...)`

它们都能把数据送进 SND 方向，但起点不同，语义也不同。

### `sendData(...)` 是“把当前层的上游对象重新送进当前层”

可以把它理解成：

- 我手上的对象，仍然是“当前层应该处理的输入类型”。
- 所以发送应该从当前层重新开始。
- 当前层自己要再次参与这次 SND 处理。

例如：

- HTTP 业务层手里拿的是 `HttpResponse`，需要交给 HTTP 编码器继续转成字节。
- WebSocket 业务层手里拿的是 `WebSocketMessage`，需要交给 WebSocket 帧编码器继续处理。

这类情况应该用 `sendData(...)`。

### `sendEncoded(...)` 是“当前层已经编码完了，直接交给前一层继续往外送”

可以把它理解成：

- 我手上的对象，已经是当前层的下游类型。
- 如果再走 `sendData(...)`，当前层会把它当成输入再处理一遍。
- 因此这次发送要跳过当前层，直接从前一个 SND 节点继续向外传播。

例如：

- `Http2ObjectEncoder` 在 `onEvent(...)` 中直接生成了 `Http2Frame`。
- `Http2ObjectDecoder` 在 `onError(...)` 中直接构造了 `RST_STREAM` 或 `GOAWAY`。

这时当前层已经完成了“对象到下游对象”的编码动作，应该用 `sendEncoded(...)`，而不是再把 `Http2Frame` 送回 `Http2ObjectEncoder` 自己。

### 两者最简单的判断方法

可以直接用下面这个问题判断：

- “我手上的对象，是否还需要当前层自己再编码一次？”

如果答案是：

- 是，那么用 `sendData(...)`。
- 否，它已经是当前层产出的下游类型，那么用 `sendEncoded(...)`。

也可以记成一条更短的规则：

- 发送当前层的输入类型，用 `sendData(...)`。
- 发送当前层的输出类型，用 `sendEncoded(...)`。

### 什么时候应该优先用 `sendEncoded(...)`

在下面这些场景里，更适合优先考虑 `sendEncoded(...)`：

- 当前回调不在常规业务发送路径里，而是在 `onEvent(...)`、`onError(...)`、`onActive(...)` 中临时生成下游对象。
- 当前层生成的是控制帧、协议 ACK、协议错误帧这类“已经完成编码”的对象。
- 你明确知道如果走 `sendData(...)`，当前层会再次吃到自己刚刚产出的对象。

### 什么时候不应该用 `sendEncoded(...)`

下面这些情况通常还是应该用 `sendData(...)`：

- 你手上的对象仍然是业务对象、协议对象或当前层输入对象。
- 你仍然希望当前层参与一次完整的发送处理，比如补头、聚合、分片、状态推进。
- 你只是想主动发一条正常业务消息，而不是发送当前层已经完成编码的结果。

### 它和 `flush()` 的关系

这里有一个容易混淆的点：

- `sendData(...)` / `sendEncoded(...)` 讨论的是“对象从哪里开始进入 SND 数据流”。
- `flush()` 讨论的是“从当前层开始触发一次空输入排空”。

`flush()` 本身没有“对象类型已经编码或尚未编码”的歧义，所以通常不需要再拆出一个对应的 `flushEncoded()`。

也就是说：

- 数据对象的发送起点，需要区分 `sendData(...)` 和 `sendEncoded(...)`。
- 排空动作仍然保留现有 `flush()` 语义即可。

## 使用时最重要的 6 条判断

### 1. 先判断当前要读还是要写

- 读当前层已有数据，看 `ProtoRcv*`。
- 写当前层产出数据，看 `ProtoSnd*`。

### 2. 再判断是在主容器里处理，还是在带有名称的视图里处理

- 主路径处理，用 `Queue`。
- 局部调度、暂存、回放，用 `QueueView`。

### 3. 不要把视图当成独立容量空间

- 数据进入视图后仍然继续占用共享容量。
- 不存在“挪进视图就释放主队列容量”的语义。

### 4. 不要把 `peek + skip` 当成假的所有权转移

- `peek` 只是看。
- `skip` 是容器直接丢弃。
- 真正转移所有权还是要靠 `take` 或成功 `offer`。

### 5. 理解背压时要看整套容器体系

- 背压不是只看主队列的长度。
- 要看主容器和全部带有名称的容器合起来持有了多少数据。

### 6. 主动发送时先判断对象是不是“当前层已经编码完成的输出”

- 如果对象还要交给当前层继续处理，用 `sendData(...)`。
- 如果对象已经是当前层产出的下游类型，用 `sendEncoded(...)`。
- 如果拿不准，就先问自己：当前层是否应该再吃这条消息一次。

## 小结

Neta 的数据流本质上是一套基于容器边界、共享容量和显式所有权转移的协议栈运行模型。

其中六个接口并不是零散工具，而是两套对称结构：

- 接收侧：`ProtoRcvData`、`ProtoRcvQueue`、`ProtoRcvQueueView`
- 发送侧：`ProtoSndData`、`ProtoSndQueue`、`ProtoSndQueueView`

它们共同解决的是同一个问题：

- 数据怎样被读取和写入。
- 数据怎样在主路径和局部路径之间调度。
- 数据的所有权什么时候转移。
- 容量为什么必须被整个容器体系共享。
- 背压为什么不能被局部视图绕开。

如果把这一层机制想明白，再去看具体 codec、聚合器、路由器和分区器，就会发现它们做的其实都是同一件事：

- 在共享容量约束下，按协议需要重新组织数据流。

和这篇文档配套阅读时，建议再看：

- [三大流](about.md)
- [网络事件](network_event.md)
- [引用所有权](../handler/ownership.md)