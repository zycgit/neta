---
id: partition-routing
sidebar_position: 6
title: 分区管线
description: 说明 Neta 中什么是分区管线、它解决的问题、运行原理、典型用法，以及在 HTTP/2 等多路复用协议中的落地方式。
---

## 分区管线

分区管线是指在一条连接内部，按照某个稳定的 key 把消息分流到多条并存的局部子链中处理。

- vs 普通管线，普通管线面向整条连接只有一条固定处理链
- vs 分支管线，分支管线是在若干固定 branch 之间选择 “当前哪条链生效”，并且分支管线不具备分支间状态隔离能力。

```text
Connection In
    |
    v
+--------------------------+
| ProtoPartitionDuplexer   |
+--------------------------+
    |
    +--> key = A --> [ partition A ] --> aggregator A --> handler A
    |
    +--> key = B --> [ partition B ] --> aggregator B --> handler B
    |
    +--> key = C --> [ partition C ] --> aggregator C --> handler C
    |
    v
Connection Out
```

这张图表达的是：一但消息进入分区节点后，会先根据 key 选择目标分区，再进入各自独立的局部子链处理。子链完全相同但是状态是相互隔离的。

Neta 中，这项能力由 `ProtoPartitionDuplexer` 提供。

## 适用场景

分区管线主要解决 “在同一连接上多路消息相互隔离” 的需要。典型场景包括：

- HTTP/2、HTTP/3 多 stream 并发
- 协议提供多路复用能力但没有内置分流机制的场景，例如 MQTT。

如果没有分区管线，所有消息只能共用一条串行管线，结果通常会出现：

- 聚合器把不同请求的片段拼到一起。
- 某个请求积压时拖慢其它请求。
- 局部异常或关闭语义污染整条连接。
- 控制事件和业务消息混在同一层处理，边界不清楚。

## 工作原理

分区管线的核心思想是通过计算分区 Key（**消息**、**事件**）然后为每个分区构建一段状态相互隔离的独立的子管线。
消息最终会被投入对应的子管线中，已完成隔离的目的。

### 1. 分区选择

`ProtoPartitionSelector` 负责从消息或事件中提取分区 key。

例如：HTTP/2 的 `Http2ObjectPartitionSelector` 会把 stream payload 映射到对应 stream 分区，同时把控制事件显式路由到 `PartitionKey.defaultKey()` 对应的默认分区，用默认分区集中治理连接级和控制级生命周期。

这里有一个必须统一遵守的约定：

- selector 返回普通 `PartitionKey`：显式进入对应普通分区
- selector 返回 `PartitionKey.defaultKey()`：显式进入默认分区
- selector 返回 `null`：未命中任何分区，不等价于默认分区

### 2. 懒加载

当某个 key 第一次出现时，`ProtoPartitionDuplexer` 会先用 `ProtoPartitionPolicy` 确定执行策略（Accept/Drop/Reject）。这个策略判断只发生在“分区尚未创建”的首次触发点；分区一旦建立，后续命中该 key 的消息和事件会直接进入已有子链，不会重复执行这一步。

- 当策略为 Accept（默认）时，会通过`byInitializer(...)` 提供的初始化器创建一条新的局部子链。
- 当策略为 Drop 时，会丢弃这个消息或事件，不创建分区。
- 当策略为 Reject 时，会引发拒绝异常，后续会进入异常处理流程。

`PartitionKey.defaultKey()` 表示显式命中默认分区。只有 selector 明确返回这个 key 时，框架才会尝试进入默认分区。selector 返回 `null` 只表示“未命中任何分区”，不会再被自动归一化成默认分区。

- selector 返回 `null` 时，未命中任何分区的数据会直接透传给分区节点后面的下游节点。
- selector 返回 `null` 时，未命中任何分区的事件不会进入任何分区子链，而是继续沿父管线向后传播。
- selector 返回 `PartitionKey.defaultKey()` 但未配置 `byDefault(...)` 时，行为仍然是透传。
- selector 返回 `PartitionKey.defaultKey()` 且配置了 `byDefault(...)`，但初始化结果为空链时，行为仍然是透传，不会创建一个空默认分区实例。
- selector 返回 `PartitionKey.defaultKey()` 且默认子链有效时，数据才会进入默认分区子链处理。

### 3. 隔离执行

同一分区内的消息仍然按原顺序串行处理，但不同分区之间可以交错推进。这意味着：

- 分区 A 的半包聚合不会污染分区 B。
- 分区 A 的局部积压由 recovery 机制单独补跑。
- 分区关闭只影响自己的局部链。

### 4. 控制和策略

在创建分区管线时 `ProtoPartitionBuilder` 接口提供了两个方法：

- `control()`：返回 `ProtoPartitionControl`，该接口可用于管理分区操作，如关闭分区、关闭全部分区、查询分区是否存在等。
- `policy(...)`：注册 `ProtoPartitionPolicy`，在分区转发之前拦截消息或网络事件。

### 5. 分区子管线的生命周期

分区子管线的生命周期由 `ProtoPartitionDuplexer` 在运行时维护，管理 API 会直接影响这些子管线的创建、复用和关闭行为。

#### 5.1 创建时机

当消息或事件命中某个 `PartitionKey`，并且当前连接上还没有这个 key 对应的分区实例时，框架会按下面顺序执行：

1. 先调用 `ProtoPartitionPolicy`。
2. 策略返回 `Accept` 后，创建新的分区子管线。
3. 对这个新分区子管线执行 `onInit`。
4. 紧接着执行 `onActive`。
5. 当前这次消息或事件再进入这个新分区子管线处理。

这意味着分区子管线是懒创建的。只有第一次命中某个分区 key 时，才会真正创建对应的分区实例。

默认分区也遵循同样的懒创建模型，但有两个额外约束：

- 只有 selector 明确返回 `PartitionKey.defaultKey()` 时，才会进入默认分区判定。
- 只有 `byDefault(...)` 构建出了非空子链时，默认分区才会真正创建。

#### 5.2 复用时机

当后续消息或事件再次命中同一个 `PartitionKey` 时，框架会直接复用已有的分区子管线：

- 不会重新执行 `ProtoPartitionPolicy`
- 不会重新执行 `onInit`
- 不会重新执行 `onActive`

同一个分区实例会持续保留自己的上下文、局部缓存和处理状态，直到被显式关闭或连接关闭。

#### 5.3 closePartition(key)

调用 `ProtoPartitionControl.closePartition(key)` 后，框架会立即执行该分区子管线的 `onClose`，并把这个分区实例从活动分区表中移除。

```text
message/event -> key=1 -> [ partition-1 active ]
                             |
                             +--> closePartition(1)
                                      |
                                      +--> partition-1.onClose()
                                      +--> remove partition-1
```

该分区关闭后：

- 后续再命中同一个 key 时，会被视为一次新的分区创建流程
- 如果此时分区创建已冻结，或者策略返回 `Drop/Reject`，这个分区不会被重新建立

### 5.4 closeAllPartitions()

调用 `ProtoPartitionControl.closeAllPartitions()` 后，所有活动分区子管线都会立即执行各自的 `onClose`，随后从活动分区表中清空。

```text
closeAllPartitions()
    |
    +--> partition-1.onClose()
    +--> partition-2.onClose()
    +--> partition-3.onClose()
    +--> clear all partitions
```

执行完成后，连接上的分区状态会回到“尚未创建任何分区”的状态。后续新命中的 key 会重新走首次创建流程。

#### 5.5 lockCreation() / unlockCreation()

`lockCreation()` 只影响“新分区是否允许创建”，不影响已经存在的分区实例。

锁定创建后：

- 已存在分区继续工作
- 已存在分区仍可接收消息和事件
- 新 key 不会创建新分区
- 命中新 key 的消息不会进入新分区；这批消息会先被封装成 `PartitionUnmatchedEvent`，向分区节点后面的下游节点传播，然后再被丢弃
- 命中新 key 的事件不会创建分区，也不会继续按原事件透传
- 如果 selector 显式返回 `PartitionKey.defaultKey()`，但默认分区尚未创建且此时创建锁生效，这批原本要进入默认分区的消息同样会先发出 `PartitionUnmatchedEvent`，然后被丢弃

`unlockCreation()` 会恢复新分区创建能力。之后首次命中新 key 时，会重新按正常流程执行策略判断和分区创建。

`PartitionUnmatchedEvent` 中会携带两部分信息：

- `partitionKey`：这批消息原本准备进入的分区 key；默认分区场景下是 `PartitionKey.defaultKey()`
- `messages`：本次被丢弃的同分区消息批次快照

#### 5.6 连接关闭时

当连接关闭、`ProtoPartitionDuplexer.onClose(...)` 被调用时，框架会关闭当前连接上的全部活动分区子管线。

这意味着：

- 每个仍然存活的分区实例都会收到一次 `onClose`
- 分区子管线不会脱离所属连接独立存活

#### 5.7 管理 API 的作用范围

`ProtoPartitionControl` 作用于当前连接上的当前这个分区双工器实例。

它只管理这一层分区节点创建出来的分区子管线，不会影响：

- 其他连接上的分区实例
- 同一连接里的其他分区双工器
- 主管线本身的生命周期

## 如何使用

### 基础用法

```java
ProtoInitializer initializer = ProtoHelper.typed(Message.class, Message.class)
    .nextPartition("partition", new ProtoPartitionSelector<Message>() {
        @Override
        public PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data) {
            if (kind == PartitionDataKind.Message) {
                Message msg = (Message) data;
                return PartitionKey.newKey(msg.getStreamId());
            }
            return null;
        }
    }, p -> {
        // biz pipline
        p.byInitializer(ctx -> {
            ctx.addLastDecoder("handler", new BusinessHandler());
        });
    }).build();
```

- 先用 ProtoPartitionSelector 算出 Key。
- 为每个 Key 创建独立的子链。
- 子链内部像普通管线一样，唯一区别是它们之间是隔离的。

### 默认分区

如果需要一个显式的默认子链，可以让 selector 在需要时返回 `PartitionKey.defaultKey()`，再通过 `byDefault(...)` 提供默认子链：

```java
ProtoInitializer initializer = ProtoHelper.typed(Message.class, Message.class)
    .nextPartition("partition", new BusinessPartitionSelector<Message>(), p -> {
        p.byInitializer(ctx -> {
            ctx.addLastDecoder("handler", new BusinessHandler());
        });

        p.byDefault(ctx -> {
            ctx.addLastDecoder("default-handler", new DefaultHandler());
        });
    }).build();
```

这里有三种语义需要区分：

- selector 返回普通 key：进入普通分区。
- selector 返回 `PartitionKey.defaultKey()` 且配置了有效 `byDefault(...)`：进入默认分区，当前分区 key 为 `PartitionKey.defaultKey()`。
- selector 返回 `PartitionKey.defaultKey()` 但没有默认分区，或者默认分区初始化后为空链：直接透传到分区节点后面的下游节点。
- selector 返回 `null`：表示未命中任何分区，不会因为配置了 `byDefault(...)` 就自动转成默认分区。

### 分区管理

```java
// 管线装配
ProtoInitializer initializer = ProtoHelper.typed(Message.class, Message.class)
    .nextPartition("partition", new BusinessPartitionSelector<Message>(), p -> {
        // biz pipline
        ProtoPartitionControl control = p.control();
        
        p.byInitializer(ctx -> {
            ctx.addLastDecoder("handler", new BusinessHandler(control));
        });
    }).build();

// 业务处理器
class BusinessHandler implements ProtoHandler<Message, Message> {
    private ProtoPartitionControl control;
    
    public BusinessHandler(ProtoPartitionControl control){
        this.control = control;
    }

    public ProtoStatus onMessage(ProtoContext context,
                                 ProtoRcvQueue<Message> src,
                                 ProtoSndQueue<Message> dst) {
        while (src.hasMore()) {
            Message item = src.takeMessage();
            if (item.isEnd()) {
                this.control.closePartition(PartitionKey.findKey(context));
            } else {
                dst.offerMessage(item);
            }
        }
        return ProtoStatus.Next;
    }
}
```

`ProtoPartitionControl` 是构建期拿到的句柄，显式传给分区内 handler、状态机或协调器。可做的事情包括：

- `hasPartition(key)`：判断分区是否存在。
- `closePartition(key)`：关闭指定分区。
- `closeAllPartitions()`：关闭全部分区。
- `partitionSize()`：当前有多少分区。

### 分区策略

```java
ProtoInitializer initializer = ProtoHelper.typed(Message.class, Message.class)
        .nextPartition("partition", new BusinessPartitionSelector<Message>(), p -> {
            // biz pipline
            p.policy(new BusinessPartitionPolicy());

            p.byInitializer(ctx -> {
                ctx.addLastDecoder("handler", new BusinessHandler(control));
            });
        }).build();

// 分区策略
class BusinessPartitionPolicy implements ProtoPartitionPolicy {
    @Override
    public ReceivePolicy newPartition(ProtoContext context, ProtoPartitionControl control,
                                      PartitionKey key, PartitionDataKind kind, Object data) {
        return ReceivePolicy.Accept;
    }
}
```

`ProtoPartitionPolicy` 只会在目标分区尚未创建时执行。已存在分区的后续消息和事件会直接进入已有子管线。

当前 `ProtoPartitionControl` 已支持以下分区管理动作：

- `lockCreation()`：冻结新分区创建
- `unlockCreation()`：恢复新分区创建
- `hasPartition(key)`：查询某个分区是否存在
- `closePartition(key)`：关闭单个分区
- `closeAllPartitions()`：关闭全部分区
- `partitionSize()`：查看当前活动分区数量

这里的 `Http2PartitionPolicy` 负责把 HTTP/2 控制事件挡在分区边界：

- `GOAWAY` 不再泄漏给普通业务 handler。
- `RST_STREAM` 会关闭对应 stream 分区。
- `PING`、`PONG`、`PRIORITY`、`PUSH_PROMISE` 保持在协议控制层。

### 处理创建锁下的未命中消息

当新分区创建被冻结时，如果希望在丢弃消息前做审计、统计或诊断，可以在分区节点后面监听 `PartitionUnmatchedEvent`：

```java
ProtoInitializer initializer = ProtoHelper.typed(Message.class, Message.class)
    .nextPartition("partition", new BusinessPartitionSelector<Message>(), p -> {
        p.byInitializer(ctx -> {
            ctx.addLastDecoder("handler", new BusinessHandler());
        });

        p.byDefault(ctx -> {
            ctx.addLastDecoder("default-handler", new DefaultHandler());
        });
    })
    .nextDecoder("unmatched-observer", new ProtoHandler<Message, Message>() {
        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event.getData();
            if (eventData instanceof PartitionUnmatchedEvent) {
                PartitionUnmatchedEvent unmatched = (PartitionUnmatchedEvent) eventData;
                // 这里可以记录日志、打指标、做限流观察等
            }
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Message> src, ProtoSndQueue<Message> dst) {
            if (src.hasMore() && dst.hasSlot()) {
                dst.offerMessage(src.takeMessage(1));
            }
            return ProtoStatus.Next;
        }
    })
    .build();
```

这个事件只描述“因为无法新建分区而被丢弃的消息”，并不会替代这些消息继续进入业务处理链。
