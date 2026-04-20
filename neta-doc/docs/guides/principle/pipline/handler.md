---
id: handler
sidebar_position: 2
title: 单工器
description: 本文将会介绍 Neta 中 ProtoStack 的数据处理流程。
---
在 Neta 中处理数据需要先明确以下几点：

- 处理数据时遵循上游、下游概念。数据就像河水一样从上游流到下游，即：UP -&gt; DOWN。
- 根据事件的传播方向可以分为上行数据和下行数据，分别对应接收和发送。即：RCV、SND。
- Handler 依据能够同时处理数据方向的能力被分为：单工器 和 双工器。

## 单工器

常规的 Handler 在处理上行数据和下行数据时只能承担一种角色这就是单工模式，在协议数据处理并不复杂的情况下是非常好的实践方法。

一个单工器只有两个节点 UP、DOWN，它的工作方式就是不断的消费 UP 数据并将产生的新数据放入 DOWN。
一个单工器的基本定义代码如下：

```java
public class DemoProtoHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    @Override
    public ProtoStatus onMessage(ProtoContext context, 
            ProtoRcvQueue<ByteBuf> up, ProtoSndQueue<String> down) {
        // process Event from up to down
        return ProtoStatus.Next;
    }
}
```
