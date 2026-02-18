---
id: handler
sidebar_position: 2
title: 数据处理
description: 本文将会介绍 Neta 中 ProtoStack 的数据处理流程。
---

# 数据处理

在 Neta 中处理数据你需要了解：

- 处理数据时遵循上游、下游概念。数据就像河水一样从上游流到下游，即：UP -&gt; DOWN。
- 根据事件的传播方向可以分为上行数据和下行数据，分别对应接收和发送。即：RCV、SND。
- Handler 依据能够同时处理数据方向的能力被分为：单工器 和 双工器。

## 单工器

常规的 Handler 在处理上行数据和下行数据时只能承担一种角色这就是单工模式，在协议数据处理并不复杂的情况下是非常好的实践方法。

一个单工器只有两个节点 SRC、DST，它的工作方式就是不断的消费 SRC 数据并将产生的新数据放入 DST。
一个单工器的基本定义代码如下：

```java
public class DemoProtoHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, 
            ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<String> dst) {
        // process Event from src to dst
        return ProtoStatus.Next;
    }
}
```

## 双工器

双工器特点在于它使用同一个方法来处理上行/下行事件，这在实现一些复杂的协议中可以带来非常大的便利性。

一个双工器结构中包含四个端点分别是：RCV_UP、RCV_DOWN、SND_DOWN、SND_UP。

- RCV_UP：是 Handler 的上行传入事件，它是在处理上行数据时，用于获取来自当前 Handler 的上游 Handler 的传出事件。
- RCV_DOWN：是当前 Handler 的上行传出事件，它会向下游 Handler 输送传入事件。
- SND_UP：是 Handler 的下行传入事件，它是在处理下行数据时，用于获取来自当前 Handler 的上游 Handler 的传出事件。
- SND_DOWN：是当前 Handler 的下行传出事件，它会向下游 Handler 输送传入事件。

通过下面这张图可以充分理解双工器端点之间的关系

![](../../../static/docs/duplex_endpoints.png)

一个双工器的基本定义代码如下：

```java
public class DemoProtoDuplex implements ProtoDuplex<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,
          ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown,
          ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
        if (isRcv) {
            // process Event from rcvUp to rcvDown
        } else {
            // process Event from sndUp to sndDown
        }
        return ProtoStatus.Next;
    }
}
```

## 生命周期

无论使用的是单工器还是双工器，它们都遵循相同的生命周期：

![](../../../static/docs/handler_lifecycle.png)

- `onInit`：每个 Socket 链接在建立之初都会触发，此时 Channel 刚刚被创建出来链接建立还在进行中并不一定可以用来发送和接收数据。
- `onActive`：当 Channel 可用时触发，在此阶段可以向远程机器发送数据但不能接收数据。
- `onMessage`：用于处理接收或发送的网络数据，一般来说就是编写编码器和解码器的地方。
- `onError`：当 onMessage 发生错误后会触发它，并且 ProtoStack 的当前状态会被设置成异常。如果当前 Handler 没有清除异常标记，
  在下一个 Handler 执行时候会自动跳过 onMessage 进入 onError 继续传递异常。直到通过 ProtoExceptionHolder 清除异常标记后才会回归正常。
- `onClose`：Channel 在被正式 close 之前触发。在这个阶段链接被关闭已经无法挽回，不应该做任何发送数据的动作。正确的用法是作为清理程序。
