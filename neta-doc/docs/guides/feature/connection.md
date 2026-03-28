---
sidebar_position: 1
title: 连接管理
description: 说明 Neta 的连接管理入口、监听与连接对象、上下文与订阅模型、关闭语义、传输差异和使用注意事项。
---

本文是 Neta 连接管理能力的正式说明文档，面向两类读者：

- 需要使用 NetManager 创建监听器、发起连接、查找通道和关闭资源的使用者
- 需要理解 SoContext、NetListen、NetChannel 和 provider 生命周期的维护者

正文按“先边界、再装配、再机制、最后参考”的顺序展开：

- 第 1 章说明连接管理在 Neta 中的整体定位
- 第 2 章说明已支持能力、能力边界和当前限制
- 第 3 章到第 4 章说明组件分层、推荐入口和典型装配方式
- 第 5 章到第 6 章说明内部机制以及数据流、异常流、事件流
- 第 7 章到第 8 章提供组件参考和实际使用注意事项

阅读时可以直接按目标进入对应章节：

- 关注当前支持哪些连接管理能力，读取第 2 章
- 关注如何选入口、如何理解对象分工，读取第 3 章
- 关注 bind、connect、subscribe、close 的具体接法，读取第 4 章
- 关注创建、关闭、异常与事件传播，读取第 5 章和第 6 章
- 关注构造器、参数和误用点，读取第 7 章和第 8 章

## 1. 简介

Neta 在 neta-core 的 channel 包中提供统一的连接管理模型。它把不同传输层的建链、监听、通道查找、关闭、事件订阅和运行时上下文收敛到一套公共 API 上，入口类是 NetManager。

整体上可以分成 5 层：

- 管理器层：NetManager 负责 bind、connect、查找和 shutdown
- 运行时上下文层：SoContextService 持有全局线程池、定时器、ByteBufAllocator、活动通道表和订阅器
- 公共通道层：SoChannel 抽象监听器和活动连接，NetListen 与 NetChannel 是主要实现
- 传输适配层：AsyncChannelProvider、AsyncServerChannel、AsyncChannel 负责把公共模型落到 TCP、UDP、QUIC、SCTP 和虚拟通道
- 协议栈层：ProtoInitializer、ProtoStackChain 和 ProtoContextService 在连接创建完成后挂接应用协议

这套实现主要解决四类问题：

- 如何用同一套 API 管理多种传输的监听与连接
- 如何在连接建立后自动安装协议栈并进入 active 状态
- 如何统一处理查找、订阅、超时、关闭和异常传播
- 如何让业务层只依赖 NetListen、NetChannel 和 SoContext 这些稳定抽象

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- 管理入口
  - NetManager.bind(...) 创建监听器
  - NetManager.connectSync(...) 发起同步连接
  - NetManager.connectAsync(...) 发起异步连接
  - NetManager.findChannel(...) 与 findListen(...) 查找运行中的通道对象
  - NetManager.shutdown() 关闭当前管理器下的全部资源
- 通道抽象
  - SoChannel 统一抽象 NetListen 和 NetChannel
  - NetListen 表示监听端口或监听端点
  - NetChannel 表示已建链且已挂协议栈的活动连接
- 生命周期管理
  - 连接创建后自动执行协议栈 onInit 与 onActive
  - 监听器支持 onAccept、waitAnyAccept、waitAnyNewAccept、waitIdle
  - 所有通道支持 onClose、close、closeNow、isClose
- 运行时上下文
  - SoContext 提供全局配置、ByteBufAllocator、通道查找和订阅入口
  - SoContextService 管理通道表、监听表、事件订阅、线程池和定时器
- 订阅与观测
  - 支持按 channelId 订阅
  - 支持按 `Predicate<PlayLoad>` 订阅
  - 支持同步和异步两种 SubscribeMode
  - 支持从 NetManager 或单个 SoChannel 两个层级订阅事件
- 关闭语义
  - close() 走安全关闭路径，先等待发送队列清空，再触发 SoCloseEvent，最后关闭底层通道
  - closeNow() 直接关闭底层通道，不等待发送队列
  - shutdown() 会统一关闭监听器和活动连接，再释放 provider 和线程资源
- 传输覆盖范围
  - TCP
  - UDP
  - QUIC
  - SCTP
  - VIRTUAL

### 2.2 能力边界

这一套连接管理组件负责：

- 多传输的监听与连接创建
- 通道对象和全局上下文的生命周期管理
- 协议栈初始化、激活、关闭回调的触发
- 查找、订阅、超时、关闭和异常的统一入口
- 把不同传输适配到统一的 SoChannel 模型

这一套连接管理组件不负责：

- 业务协议本身的编解码和状态机定义
- 自动重连、连接池、负载均衡和服务发现
- 上层应用协议的路由策略与会话模型
- 操作系统层面的网络诊断和端口管理工具

有三个边界需要明确：

- NetManager 负责传输和通道生命周期，协议语义仍然落在 ProtoInitializer 及后续 handler 上
- NetListen 本身不参与协议上下文读取，findProtoContext 在监听器上会直接抛出异常
- SoContext 只管理当前 NetManager 作用域内的对象，不跨多个 NetManager 共享通道表

### 2.3 当前明确限制

- provider 的选择由 SoConfig.getProtocol() 决定，当前实现通过 NetManager 内部的固定映射创建 provider
- findListen(int port) 只返回第一个命中的活动监听器，不提供同端口多监听器的高级选择逻辑
- NetListen.getRemoteAddr() 不适用，调用会抛出 UnsupportedOperationException
- 安全关闭依赖 SoCloseTask 的异步流程，close() 返回的是 Future，最终关闭完成时间取决于发送队列和协议栈收尾动作
- channel.sendData() 和 flush() 不能在当前通道自身的 pipeline 调用链内重入调用，NetChannel 会直接报错
- 订阅观察到的 PlayLoad 数据仍然属于 pipeline 或 transport。需要跨回调持有引用时，业务层要自行 retain 或 copy
- QUIC、UDP、虚拟通道虽然都复用了统一入口，但底层连接语义与 TCP 不同，文档后续章节只说明公共管理模型和显式差异

## 3. 组件分层

### 3.1 整体分层

```text
Application
  -> NetManager
  -> bind / connect / shutdown / find

NetManager
  -> SoContextService
  -> channelMap / listenList / timer / executors / subscriptions

SoContextService
  -> AsyncChannelProvider
  -> TCP / UDP / QUIC / SCTP / VIRTUAL

Provider
  -> AsyncServerChannel / AsyncChannel
  -> NetListen / NetChannel

NetChannel
  -> ProtoInitializer / ProtoStackChain
  -> onInit / onActive / onClose / send / receive
```

### 3.2 推荐入口

常规场景优先使用这些公开入口：

- 监听服务端连接：NetManager.bind(...)
- 发起客户端连接：NetManager.connectSync(...) 或 connectAsync(...)
- 查找活动对象：NetManager.findChannel(...)、NetManager.findListen(...)
- 监听新连接：NetListen.onAccept(...)
- 等待接入或空闲：NetListen.waitAnyAccept()、waitIdle()
- 关闭连接：NetChannel.close() 或 closeNow()
- 关闭全局资源：NetManager.shutdown()

只有在这些场景才需要感知更底层部件：

- 需要理解不同传输创建过程时，关注 AsyncChannelProvider
- 需要理解连接是如何落地到 transport 时，关注 AsyncServerChannel 和 AsyncChannel
- 需要调优底层 socket 参数时，关注 SoConfig 及其传输子类

### 3.3 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| NetManager | 统一的 bind、connect、shutdown、查找入口 | 应用启动、建链、全局资源关闭 |
| SoContextService | 当前 NetManager 的运行时容器 | 管理线程池、定时器、订阅器、活动通道 |
| SoChannel | 监听器与活动连接的公共抽象 | 通用查找、订阅、关闭与属性访问 |
| NetListen | 监听端点与 accept 生命周期对象 | 服务端绑定端口、等待新连接、挂起监听 |
| NetChannel | 活动连接对象 | sendData、flush、close、读取上下文、订阅消息 |
| SoConfig | 通用通道配置 | 选择协议、设置超时、buffer、slot 和 suspend |
| NetConfig | 当前 NetManager 的全局配置 | 调整 IO 线程、任务线程、日志和分配器 |
| AsyncChannelProvider | 传输 provider SPI | 把公共模型落地到具体 transport |
| AsyncServerChannel | 监听侧底层通道 | bind 后返回 NetListen |
| AsyncChannel | 连接侧底层通道 | connectTo 后返回 NetChannel |

### 3.4 选型结论

- 常规业务直接使用 NetManager、NetListen、NetChannel 三个层次
- 需要统一管理多个连接和监听器时，优先依赖 SoContext 和 NetManager 的查找与订阅能力
- 需要做端口监听控制时，优先使用 NetListen 的 suspend、resume、waitAnyAccept、waitIdle
- 需要做 transport 级调优时，再下沉到 SoConfig 的传输子类

## 4. 使用方式

### 4.1 服务端监听与接入

适用范围：

- 需要绑定本地地址并接受新连接
- 需要在 accept 后为每个连接自动挂上协议栈

推荐装配：

```java
NetManager neta = new NetManager();
SoConfig tcp = SoConfig.TCP();

ProtoInitializer initializer = ctx -> {
    ctx.addLast("app", appHandler);
};

NetListen listen = neta.bind(new InetSocketAddress(8080), initializer, tcp);
listen.onAccept(ch -> {
    NetChannel channel = (NetChannel) ch;
    System.out.println("accept channel = " + channel.getChannelId());
});
```

关键点：

- bind(...) 时会先选定 provider，再创建 AsyncServerChannel 和 NetListen
- 每个 accepted 连接都会自动创建自己的 NetChannel 和协议栈上下文
- 如果需要阻塞等待首个连接，可调用 waitAnyAccept()

### 4.2 客户端连接

适用范围：

- 需要主动连向远端地址
- 需要同步或异步两种建链方式

推荐装配：

```java
NetManager neta = new NetManager();
SoConfig tcp = SoConfig.TCP();

ProtoInitializer initializer = ctx -> {
    ctx.addLast("app", appHandler);
};

NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", 8080), initializer, tcp);
channel.sendData("hello\n");
```

异步写法：

```java
Future<NetChannel> future = neta.connectAsync(address, initializer, tcp);
NetChannel channel = future.get();
```

关键点：

- connectSync(...) 是对 connectAsync(...).get() 的封装
- 连接成功后返回的已经是协议栈初始化完成的 NetChannel
- 初始化失败会回滚 channelMap 和监听列表里的注册对象

### 4.3 查找、订阅与状态观测

适用范围：

- 需要按 channelId 或端口查找对象
- 需要从业务侧旁路观察消息流，而不改 protocol stack

推荐装配：

```java
NetChannel channel = (NetChannel) neta.findChannel(channelId);
NetListen listen = neta.findListen(8080);

channel.subscribe(PlayLoad::isInbound, payload -> {
    System.out.println(payload.getData());
});

neta.getContext().subscribe(p -> true, payload -> {
    System.out.println(payload.getSource().getChannelId());
});
```

关键点：

- 单通道订阅会自动按当前 channelId 过滤
- SoContext 级订阅会收到当前 NetManager 管理下的所有匹配事件
- SubscribeMode.SYNC 和 ASYNC 的差异由 SoContextService 内部派发器处理

### 4.4 监听器控制与等待 API

适用范围：

- 需要暂停接入、等待新连接或等待监听器空闲

推荐装配：

```java
listen.suspend();
listen.resume();
listen.waitAnyAccept();
listen.waitIdle();
```

关键点：

- suspend() 只影响后续 accept，不会主动关闭当前已接入连接
- waitAnyAccept() 在当前已有连接时立即返回
- waitAnyNewAccept() 总是等待下一次 accept
- waitIdle() 会一直等到 acceptCount 归零

### 4.5 虚拟通道场景

适用范围：

- 需要在单 JVM 内模拟连接生命周期
- 需要无真实网络依赖的测试场景

推荐装配：

```java
NetManager neta = new NetManager();
VrtSoConfig vrt = VrtSoConfig.asDefault();

NetChannel channel = neta.connectSync(new VrtSocketAddress(1), initializer, vrt);
```

关键点：

- 虚拟通道同样走 NetManager、NetChannel、NetListen 这一套公共接口
- VrtProvider 维护进程内 listenPool，用地址号模拟监听端口
- 这条链路很适合测试连接生命周期、订阅和协议栈回调

## 5. 内部工作机制

### 5.1 连接创建方式

连接创建分成三段：

- NetManager 根据 SoConfig.protocol 选择 provider
- provider 创建 AsyncServerChannel 或 AsyncChannel
- SoContextService.initChannel(...) 把对象注册进 channelMap，并在 NetChannel 上触发 protoStack.onInit(...) 与 onActive(...)

服务端 accept 成功后，如果该连接来自某个 NetListen，SoContextService 会在初始化完成后回调 listen.notifyAccept(channel)。

### 5.2 管理器与上下文的关系

每个 NetManager 都持有一个 SoContextService。这个上下文对象统一管理：

- channelId 的递增分配
- 活动监听器与连接的注册表
- IO 线程池与事件线程池
- 全局定时器
- ByteBufAllocator
- 订阅器列表

这使得不同 transport 的通道实例最终都能落回同一个运行时容器。

### 5.3 provider 选择方式

NetManager.findProvider(...) 当前按协议名做固定映射：

- TCP -> TcpProvider
- UDP -> UdpProvider
- VIRTUAL -> VrtProvider
- SCTP -> SctpProvider
- QUIC -> QuicProvider

provider 只负责传输级 bootstrap：

- TcpProvider 持有共享的 AsynchronousChannelGroup
- UdpProvider 和 QuicProvider 直接创建 DatagramChannel
- VrtProvider 管理虚拟监听池并做 connect-mode 查找

### 5.4 安全关闭方式

close() 走 SoCloseTask，处理顺序是固定的：

- 等待发送队列清空
- 向发送方向投递 SoCloseEvent
- 让协议栈有机会补发收尾数据
- 再次等待发送队列清空
- 最后执行实际关闭

这条路径允许 WebSocket close frame、TLS close_notify 这类收尾数据在关闭前排队发送。

### 5.5 强制关闭方式

closeNow() 会直接创建 forceNow = true 的 SoCloseTask，随后：

- 跳过发送队列排空等待
- 不触发安全关闭收尾路径
- 立即关闭底层 transport

这条路径更适合故障切断，不适合需要对端感知完整关闭语义的场景。

### 5.6 订阅派发方式

SoContextService 内部维护 SubscriptionEntry 列表。派发流程如下：

- 先按 Predicate 过滤事件
- SYNC 模式直接在当前线程回调 listener
- ASYNC 模式进入内部 eventQueue，再由 SoEventExecutor 取出执行
- unSubscribe() 会移除注册项并清空异步队列

### 5.7 异常与关闭协作方式

SoContextService 会把 bind、connect、接收和发送阶段的异常统一路由到：

- notifyBindChannelException(...)
- notifyConnectChannelException(...)
- notifyRcvChannelException(...)
- notifySndChannelException(...)

如果异常无法被协议栈消费，最终会落到 doCloseChannel(...)，清理底层通道、发送队列和注册表。

## 6. 关键流

### 6.1 数据流

```text
Application
  -> NetManager.bind / connect
  -> Provider
  -> AsyncServerChannel / AsyncChannel
  -> NetListen / NetChannel
  -> ProtoStackChain.onInit / onActive
  -> sendData / notifyRcv
```

排错线索：

- connect 成功返回后，NetChannel 已经进入协议栈 active 阶段
- 服务端连接只有在 init 成功后才会触发 NetListen.onAccept
- NetChannel.sendData(...) 会先经过 SND pipeline，再进入底层发送队列

### 6.2 异常流

```text
bind/connect/rcv/snd exception
  -> SoContextService.notify*Exception
  -> NetChannel.notifyError
  -> ProtoStackChain.onRcv / onSnd error path
  -> doCloseChannel
  -> purge queue / remove registry / complete closeFuture
```

排错线索：

- 初始化失败时，通道会从 channelMap 回滚移除
- 接收或发送异常可以先进入协议栈错误路径，再决定是否关闭
- 关闭后的查找结果可能立即变成 null，这属于正常行为

### 6.3 事件流

```text
accept success
  -> NetListen.notifyAccept
  -> onAccept listeners / waitAnyAccept

channel close
  -> closeFuture completed
  -> onClose listeners

payload emitted
  -> SoContextService subscription dispatch
  -> SYNC or ASYNC listener callback
```

排错线索：

- waitAnyAccept() 等的是 acceptCount，不等协议业务数据
- onClose 监听器通过 closeFuture 触发，只会跟随关闭完成回调
- 异步订阅有内部排队，回调时序可能落后于当前线程里的 send 或 receive 调用

## 7. 组件参考

### 7.1 NetManager

- 作用：统一的连接管理入口
- 关键方法：
  - bind(listenAddr, initializer, soConfig)：创建监听器
  - connectSync(remoteAddr, initializer, soConfig)：同步建链
  - connectAsync(remoteAddr, initializer, soConfig)：异步建链
  - findChannel(channelId)：查找活动通道
  - findListen(port)：按端口查找监听器
  - shutdown()：关闭当前管理器下的所有资源
- 何时使用：所有连接管理场景都从这里进入

### 7.2 NetListen

- 作用：表示监听端点和 accept 生命周期
- 关键方法：
  - onAccept(listener)：注册 accept 回调
  - suspend() / resume()：暂停或恢复接入
  - waitAnyAccept()：等待已有或新的接入
  - waitAnyNewAccept()：等待下一次新的接入
  - waitIdle()：等待所有已接入连接断开
  - findChannel(channelId)：在当前监听器作用域内查找连接
- 何时使用：服务端需要感知连接接入、接入数量和监听器空闲状态时

### 7.3 NetChannel

- 作用：表示活动连接和协议栈承载体
- 关键方法：
  - sendData(...)：发送业务对象
  - flush()：冲刷发送链路
  - close() / closeNow()：关闭连接
  - findProtoContext(type)：读取根协议上下文附件
  - subscribe(...)：订阅当前连接的 PlayLoad
  - setReadTimeout() / waitReceive()：设置或等待读超时
- 何时使用：业务收发、读取上下文、观测消息和关闭连接时

### 7.4 SoConfig 与传输子类

- 作用：定义 per-channel 连接参数
- 通用参数：
  - protocol：选择 provider
  - rcvSlotSize / sndSlotSize：协议栈槽位数
  - soRcvBuf / soSndBuf：socket buffer hint
  - soReadTimeoutMs / soWriteTimeoutMs：读写超时
  - connectTimeoutMs：建链超时
  - suspend：监听器初始挂起状态
- 传输子类：
  - TcpSoConfig：额外提供 swap buffer 和 keepalive 相关选项
  - UdpSoConfig：额外提供 rcvPacketSize、remoteOnly 和发送重试参数
  - QuicSoConfig：额外提供 QUIC transport parameter、TLS 配置和连接监听器
  - VrtSoConfig：额外提供虚拟链路模式、批量、丢包和同步方式

### 7.5 NetConfig

- 作用：定义当前 NetManager 的全局运行时参数
- 关键参数：
  - ioThreads：IO 线程数
  - taskThreads：任务线程数
  - retryIntervalMs：内部延迟任务重试间隔
  - bufAllocator：全局 ByteBufAllocator
  - threadFactory：内部线程工厂
  - printLog：底层网络日志开关
- 何时使用：需要调优当前 NetManager 作用域下的整体执行环境时

## 8. 使用注意事项

- NetListen 和 NetChannel 共用 SoChannel 抽象，但两者职责不同。监听器没有 remote address，也不支持 findProtoContext
- close() 和 closeNow() 的语义不同。需要发送收尾数据时使用 close()，需要立刻切断时使用 closeNow()
- 在当前通道自身的 pipeline 调用链内重入 sendData() 或 flush() 会被 NetChannel 拦截并报错
- PlayLoad 订阅看到的是观察视图，不自动转移对象所有权。对 ByteBuf 这类引用计数对象要自行 retain 或 copy
- waitAnyAccept() 在 acceptCount 已经大于 0 时会立即返回。需要强制等待下一次新连接时使用 waitAnyNewAccept()
- suspend() 只阻止新的 accept，不会清理已有连接
- shutdown() 会关闭当前 NetManager 下的所有监听器与连接。多个 NetManager 之间互不影响
- connectSync() 是阻塞调用。高并发建链场景优先使用 connectAsync() 配合 Future 统一调度
