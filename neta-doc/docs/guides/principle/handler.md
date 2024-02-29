---
sidebar_position: 2
title: 处理器
description: 本文将会介绍 Neta 中 Pipeline 的数据处理流程。
---

# 处理器

在 Neta 中通过 Pipeline 处理数据流你需要了解：

- Handler 处理数据时遵循上游、下游概念。数据就像河水一样从上游流到下游，即：UP -&gt; DOWN。
- 根据事件的传播方向可以分为上行数据和下行数据，分别对应接收和发送。即：RCV、SND。

常规的 Handler 在处理上行数据和下行数据时只能承担一种角色这是一种单工模式，在协议数据处理并不复杂的情况下是非常好的实践方法。
一个单工器结构和代码代码如下：

```text
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ UP  ->  onMessage  ->  DOWN ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

```java
public class DemoPipeDuplex implements PipeHandler<ByteBuf, ByteBuf> {
    @Override
    public PipeStatus onMessage(PipeContext context, 
            PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
        // process Event from src to dst
    }
}
```

在实现较为复杂的协议数据交换中为了避免多环节协作中会遭遇事件类型转换和传递问题，需要借助 PipeDuplex 双工器件。
一个双工器结构和代码代码如下：

```text
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ RCV_UP             RCV_DOWN ┃
┃                             ┃
┃          onMessage          ┃
┃                             ┃
┃ SND_DOWN             SND_UP ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

```java
public class DemoPipeDuplex implements PipeDuplex<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    @Override
    public PipeStatus onMessage(PipeContext context, boolean isRcv, 
                                PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<String> rcvDown,
                                PipeRcvQueue<String> sndUp, PipeSndQueue<ByteBuf> sndDown) {
        if (isRcv) {
            // process Event from rcvUp to rcvDown
        } else {
            // process Event from sndUp to sndDown
        }
    }
}
```

双工器最大特点在于它可以在一次处理过程中同时处理上行/下行数据，这在实现一些复杂的协议中可以带来非常大的便利性。
Pipeline 的组成可以完全由双工器组成也和常规的 Handler 联合使用。

一个双工器有四个端点分别是：RCV_UP、RCV_DOWN、SND_DOWN、SND_UP。

- RCV_UP：是 Handler 的上行传入事件，它是在处理上行数据时，用于获取来自当前 Handler 的上游 Handler 的传出事件。
- RCV_DOWN：是当前 Handler 的上行传出事件，它会向下游 Handler 输送传入事件。
- SND_UP：是 Handler 的下行传入事件，它是在处理下行数据时，用于获取来自当前 Handler 的上游 Handler 的传出事件。
- SND_DOWN：是当前 Handler 的下行传出事件，它会向下游 Handler 输送传入事件。

通过下面这张图可以充分理解双工器端点之间的关系

![](../../../static/docs/duplex-endpoints.png)