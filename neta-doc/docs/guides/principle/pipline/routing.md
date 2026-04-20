---
id: routing-pipeline
sidebar_position: 5
title: 路由管线
description: 说明 Neta 中路由管线的工作模型，包括静态路由、动态路由、手动切换路由、返回空值时的行为，以及不同模式下的生命周期调用方式。
---

## 路由管线

路由管线是指在一条连接内部，先定义多条固定分支，再在运行时决定“当前数据或事件进入哪一条分支”的协议结构。

```text
Connection In
    |
    v
+-----------------------+
| ProtoRoutingDuplexer  |
+-----------------------+
    |
    +--> branch = http  --> [ http branch ]
    |
    +--> branch = ws    --> [ websocket branch ]
    |
    +--> branch = h2    --> [ http2 branch ]
    |
    v
Connection Out
```

这项能力由 `ProtoRoutingDuplexer` 提供。

## 它解决什么问题

路由管线解决的是“同一连接需要在若干预定义协议路径之间切换”的问题。

典型场景包括：

- 协议探测后在 HTTP、WebSocket、TLS、HTTP/2 等分支间选择一条生效路径
- 握手完成后从当前协议分支切换到另一个已定义分支
- 在嵌套路由中逐层把数据送到更细粒度的协议分支

路由管线的重点是“从固定分支里选一条当前生效分支”。它不负责像分区管线那样同时维护大量并存的局部子链。

## 路由何时发生

根据 `ProtoRoutingDuplexer` 的实际实现，路由决策最多会发生在三个时机：

### 1. onActive 阶段

静态数据路由在连接激活时会先做一次“空输入探测”。

- 这里的 `rcvUp` 是空队列
- 适合只依赖上下文做路由，例如 ALPN、前置协商结果

如果这一步已经算出分支，后续会直接激活该分支。

### 2. 首次接收入站数据时

如果 `onActive` 阶段还没有选出分支，数据路由会在入站消息流到路由双工器时再次计算。

- 静态路由只需要选中一次
- 动态路由会在每次入站消息到达时重新计算

### 3. 网络事件传播到路由双工器时

静态事件路由会在尚未选中分支时，基于当前网络事件做一次路由决策。

这类路由适合“先有控制事件，再决定分支”的协议场景。

## 分支如何选择

分支选择分成两步：

1. 路由选择器返回一个分支名
2. 框架从预先声明的 `branches` 中找到同名分支子管线

如果返回的分支名没有对应分支，当前这轮处理会停止，不会进入任何分支。

## 静态路由

静态路由对应 `nextRouteAsStatic(...)`。

它的特点是：首次成功算出的分支会被缓存，后续继续复用这条当前分支，直到显式切换。

```text
receive 1st message --> selector returns "http"
                       |
                       +--> selectedRoute = http
                       +--> later inbound still use http
```

静态路由适合：

- 握手后连接协议基本固定的场景
- 一次探测后长期沿同一分支工作的场景
- 需要在某个时点手动切换到另一条预定义分支的场景

## 动态路由

动态路由对应 `nextRouteAsRealtime(...)`，运行时模式是 `ProtoRoutingMode.REALTIME`。

它的特点是：每次入站消息流到路由双工器时，都会重新计算当前应进入的分支。

```text
message #1 --> selector returns even --> enter branch even
message #2 --> selector returns odd  --> enter branch odd
message #3 --> selector returns even --> enter branch even
```

动态路由适合：

- 不同消息可能落到不同分支的场景
- 路由结果依赖当前消息内容的场景
- 每条消息都需要重新决定分支的场景

## 手动切换路由

在路由分支内部，处理器可以通过 `ProtoRoutingControl` 主动切换当前分支：

```java
ProtoRoutingControl routing = routingBuilder.control();
branchCtx.addLast("upgrade", new SomeUpgradeDuplexe(routing));
```

### 获取方式

`ProtoRoutingControl` 由当前 `ProtoRoutingBuilder` 提供：

```java
ProtoRoutingBuilder<Object, Object> routingBuilder = ...;
ProtoRoutingControl routing = routingBuilder.control();
```

推荐在组装分支时把它显式传给确实需要发起切换的 handler 或 duplexe，而不是当作 `ProtoContext` 的公共对象传播到所有节点。

### 分支切换数据投递物

分支切换除了可以只切目标分支，也可以携带一个一次性交接物：

```java
routing.switchRoute("h2", promotedRequest);
```

目标分支切入后，可以通过同一个 `ProtoRoutingControl` 读取这个交接物：

```java
if (routing.hasSeed()) {
    Object seed = routing.takeSeed();
}
```

这组方法的语义是：

- `hasSeed()`：当前已切入分支是否收到一次性交接物
- `peekSeed()`：查看交接物但不消费
- `takeSeed()`：取走并清空交接物

约束如下：

- seed 只在路由真正切换到目标分支之后才可见
- seed 是一次性的 handoff 对象，不是长期上下文状态
- 同一时刻只允许存在一个 pending 或 visible seed
- `takeSeed()` 调用后，后续分支轮次不应再看到同一个 seed

### 生效时机

`switchRoute(...)` 不是立即切走当前处理中的分支。

当前实现会先登记一次待执行切换，等当前分支的挂起输出和 recovery 流程处理完之后，再真正把 `selectedRoute` 切换到目标分支。

当前分支返回不同 `ProtoStatus` 时，时机会进一步细分：

- `Next`：当前分支继续跑完这一轮，路由器在本轮结束后再尝试切到目标分支
- `Stop`：当前分支立刻结束，路由器可以在本轮内把 `selectedRoute` 切到目标分支；下一次进入该路由器时会按新分支继续处理
- `Abort`：本轮禁止切换，待切换目标会保留下来，等下一次重新进入路由器时再尝试执行

这里要注意，`Stop` 的“同轮切换”说的是路由控制权和 `selectedRoute` 的切换，不表示当前这条消息会在同一轮被目标分支重放处理。

### 生命周期行为

- 如果目标分支此前从未激活过，第一次切入时会执行一次 `onActive`
- 如果目标分支此前已经激活过，再次切回时不会重复执行 `onActive`
- 再次切回已激活分支时，框架会向该分支发送一个 `ProtoRouteEvent`

### 作用范围

这个切换只影响当前连接上的当前这个路由双工器实例。

它不会影响：

- 其他连接
- 同一连接里的其他路由双工器
- 主管线本身的生命周期

## 路由计算返回空值时会发生什么

### 静态数据路由返回空值

静态数据路由返回 `null` 时，表示“本轮继续延后决策”。

实际行为如下：

- 当前这轮不会选中任何分支
- 当前这轮不会把数据继续送入任意分支
- `onMessage` 会在路由双工器处停止当前处理

是否会保留当前数据，取决于选择器自己有没有消费 `rcvUp`：

- 如果选择器只 `peek` 不消费，数据会留在队列里，下次继续参与路由计算
- 如果选择器已经 `take` 走数据，这部分数据不会自动放回

这正是握手探测场景可用的基础：

- 可以先积累若干条数据，等条件满足后再选中分支
- 也可以自己消费握手包，等握手完成后再让后续数据进入目标分支

### 动态数据路由返回空值

动态路由返回 `null` 时，当前这条入站消息不会进入任何分支，当前这轮处理会停止。

下一条入站消息到来时，框架会重新执行一次路由计算。

### 事件路由返回空值

静态事件路由返回 `null` 时，当前事件不会进入任何路由分支。

路由双工器本身不会因此选中分支。后续事件仍可继续触发新的路由尝试。

### 发送方向在未选中分支时的行为

当前实现里，如果发送方向的数据先于路由决策到达，而且此时 `selectedRoute` 仍然为空，发送数据会被直接丢弃，并记录告警日志。

这意味着：

- 路由尚未确定前，不应向该路由双工器后面的发送链发送业务数据
- 如果需要在发送前先确定分支，应通过上下文探测、首包探测或手动切换先完成路由决策

## 生命周期调用模型

### 所有模式共通的规则

无论静态还是动态路由，以下两点都成立：

- `onInit` 会对全部已注册分支执行一次
- 连接关闭时，`onClose` 会对全部分支执行一次

```text
router.onInit()
    +--> branch-A.onInit()
    +--> branch-B.onInit()
    +--> branch-C.onInit()

router.onClose()
    +--> branch-A.onClose()
    +--> branch-B.onClose()
    +--> branch-C.onClose()
```

### 静态路由的生命周期

静态路由下，`onActive` 只会发给当前选中的分支。

```text
static route selected = http
    |
    +--> http.onActive()
```

如果某个分支从未被选中过，它不会收到 `onActive`。

如果后续手动切换到一个从未激活过的分支，该分支会在首次切入时收到一次 `onActive`。

如果切回一个已经激活过的分支，该分支不会再次收到 `onActive`，而是收到 `ProtoRouteEvent`。

### 动态路由的生命周期

动态路由下，`onActive` 会在连接激活阶段一次性发给全部分支。

```text
router.onActive()
    +--> branch-A.onActive()
    +--> branch-B.onActive()
    +--> branch-C.onActive()
```

之后每条入站消息再根据实时计算结果进入当前分支。

这意味着动态路由的分支是“全部预热、按条分发”的模型。

### 事件路由的生命周期

事件路由属于静态路由的一种。

- 首次命中分支后，会缓存当前分支
- 首次激活目标分支时会执行 `onActive`
- 后续继续复用该分支，直到显式切换

## 使用建议

可以按下面的判断顺序选择路由模式：

1. 一次选中后长期固定，选择静态路由
2. 每条消息都可能进入不同分支，选择动态路由
3. 需要在分支内部显式迁移协议阶段，使用静态路由加 `ProtoRoutingControl.switchRoute(...)`
4. 需要先等握手条件成熟，再选中分支，使用静态数据路由并在选择器中决定是否保留或消费输入

## 小结

路由管线的关键点可以概括为：

- 路由分支必须在初始化期全部定义完成
- 静态路由缓存首次成功结果，动态路由每次重新计算
- 手动切换会延后到当前轮处理安全结束后生效
- 路由返回空值表示当前这轮暂时不进入任何分支，具体数据是否保留取决于选择器是否消费输入
- `onInit` 和 `onClose` 始终面向全部分支，`onActive` 的调用范围取决于路由模式
