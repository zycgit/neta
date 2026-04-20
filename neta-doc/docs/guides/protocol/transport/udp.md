---
sidebar_position: 11
title: UDP
description: 说明 Neta 的 UDP 传输支持范围、对象模型、运行机制、配置项以及服务端和客户端的使用方式。
---

## 简介

UDP 是一种无连接、面向数据报的传输协议。它保留单个数据报的边界，但不保证可靠送达、不保证到达顺序，也不提供像 TCP 那样的连接建立与流控语义。
因此，应用层通常需要自己决定是否做重传、顺序恢复、会话管理或帧级确认。

Neta 在 `neta-core` 的 `net.hasor.neta.channel.udp` 包中提供 UDP 传输支持，目标是把 JDK `DatagramChannel` 的底层能力适配到统一的 `NetManager`、`NetListen`、`NetChannel` 和 pipeline 模型。

整体上可以分成 4 层：

- Provider 层：`UdpProvider` 负责创建客户端和服务端底层通道
- 传输层：`UdpAsyncClientChannel`、`UdpAsyncServerChannel` 与 `UdpTransport` 负责 connect、bind、接收轮询和发送调度
- 通道层：`UdpChannel` 负责把远端地址视图接到 Neta 的协议栈和生命周期体系
- 任务层：`AbstractUdpWriteTask` 与 `UdpWriteTask` 负责发送队列推进、超时重试和 UDP 写出适配

这套实现主要覆盖四类能力：

- 使用统一 API 建立 UDP 服务端监听与客户端连接
- 保留 UDP 单个数据报的边界，并以 `ByteBuf` 形式投递到 pipeline
- 在服务端基于远端 `host:port` 维度懒创建逻辑通道
- 提供基础 socket 配置、接收包大小控制以及发送超时重试能力

## 范围与能力

- 传输入口
  - `UdpProvider` 作为 `AsyncChannelProvider` 接入 `NetManager`
  - 支持 `bind(...)` 创建 UDP 监听器
  - 支持 `connectSync(...)` 和 `connectAsync(...)` 创建 UDP 客户端通道
- 通道模型
  - 客户端模式使用一个已 connect 的 `DatagramChannel`
  - 服务端模式使用一个已 bind 的 `DatagramChannel`
  - 服务端会按远端 `host:port` 懒创建多个逻辑 `UdpChannel`
- 数据模型
  - 每个入站 UDP 数据报会被转换成一个独立的 `ByteBuf`
  - UDP 保留数据报边界，不会像 TCP 那样把多次接收结果拼成连续字节流
  - 出站数据通过 `SoSndContext` 排队，再由 `UdpWriteTask` 推进到底层通道
- 配置能力
  - 支持 `rcvPacketSize` 控制逻辑接收包大小
  - 支持 `soRcvBuf` 和 `soSndBuf` 落到底层 UDP socket
  - 支持 `rcvRemoteOnly` 控制客户端是否忽略非目标远端发来的数据报
  - 支持 `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs` 控制发送无法立即推进时的重试行为
- 生命周期能力
  - 支持服务端监听器生命周期
  - 支持客户端和服务端逻辑通道的关闭通知
  - 支持接收异常和发送异常上报

## 使用限制

- UDP 本身不提供可靠送达、顺序保证和连接状态维护，Neta 也不会自动补上这些能力
- 服务端的 `UdpChannel` 是逻辑通道，而不是独占底层 socket 的真实连接
- 同一个服务端监听器下的多个 `UdpChannel` 共享同一个 `DatagramChannel`
- `rcvPacketSize` 决定内部接收缓冲区容量，超过该大小的数据报会被操作系统静默截断
- `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs` 只处理框架发送推进层面的超时重试，不等价于应用层可靠重传协议
- 客户端模式下如果启用 `rcvRemoteOnly`，来自非目标远端的数据报会被直接丢弃

## 3. 核心对象

### 3.1 整体对象分层

```text
Application
  -> NetManager
  -> bind / connect

UdpProvider
  -> UdpAsyncServerChannel / UdpAsyncClientChannel

UdpTransport
  -> receive loop

UdpChannel
  -> ProtoInitializer / ProtoStackChain

UdpWriteTask
  -> datagram send
```

### 3.2 关键对象职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| `UdpProvider` | 创建 UDP 客户端与服务端底层通道 | `NetManager` 根据 `UdpSoConfig` 选择 provider |
| `UdpAsyncServerChannel` | 绑定监听地址、启动接收循环、按远端地址分流逻辑通道 | 服务端 `bind(...)` |
| `UdpAsyncClientChannel` | 管理已连接 UDP 客户端、接收循环和远端过滤 | 客户端连接 |
| `UdpAsyncChannel` | 封装单个 UDP 对端视图、地址信息和单写者发送门控 | 客户端通道、服务端逻辑通道底层适配 |
| `UdpTransport` | 持有 `DatagramChannel`、`Selector` 和共享接收缓冲区，驱动接收轮询 | 客户端和服务端共用 |
| `UdpChannel` | 框架层活动逻辑通道，挂接协议栈 | 业务 handler 收发数据 |
| `AbstractUdpWriteTask` | 封装发送主循环、重试和异常处理骨架 | 所有 UDP 风格发送任务 |
| `UdpWriteTask` | 普通 UDP 的具体发送实现 | 客户端发包、服务端逻辑通道回包 |
| `UdpSoConfig` | UDP 传输配置对象 | 调整接收包大小、远端过滤和重试参数 |

### 3.3 `UdpChannel` 的定位

`UdpChannel` 是框架层暴露给业务的活动通道对象，但它在 UDP 服务端模式下并不对应一个真实独占 socket，而是一个绑定到远端地址视图的逻辑通道。

这套模型体现出以下特征：

- 客户端模式下，一个 `UdpChannel` 对应一个已 connect 的 UDP 通道视图
- 服务端模式下，多个 `UdpChannel` 可以共享同一个底层 `DatagramChannel`
- 业务面对的是统一的 `NetChannel` API，不需要直接处理 `DatagramChannel` 和 `Selector`

### 3.4 `UdpSoConfig` 的定位

`UdpSoConfig` 是 UDP 传输的专用配置对象，主要负责三类参数：

- 接收包控制
  - `rcvPacketSize`：内部单次接收包大小
  - `rcvRemoteOnly`：客户端是否只接受配置远端发来的数据报
- 底层 socket 缓冲区
  - `soRcvBuf` / `soSndBuf`
- 发送推进策略
  - `sndWriteRetryCount`
  - `sndWriteRetryIntervalMs`

它与通用 `SoConfig` 一起使用，统一描述 UDP 通道的 socket 行为和发送重试策略。

## 4. 使用方式

### 4.1 服务端监听

最常见的服务端用法是直接创建 `UdpSoConfig`，然后通过 `NetManager.bind(...)` 绑定地址。

```java
NetManager neta = new NetManager();

UdpSoConfig udp = new UdpSoConfig();
udp.setSoRcvBuf(128 * 1024);
udp.setSoSndBuf(128 * 1024);
udp.setRcvPacketSize(64 * 1024);

ProtoInitializer initializer = ctx -> {
    ctx.addLast("app", appHandler);
};

NetListen listen = neta.bind(new InetSocketAddress("0.0.0.0", 18080), initializer, udp);
```

关键点：

- `NetManager` 会根据 `UdpSoConfig` 的协议名称选择 `UdpProvider`
- 服务端监听通道会创建 `UdpAsyncServerChannel`
- 真正的逻辑 `UdpChannel` 会在收到来自某个远端地址的数据报后按需创建
- 每个新的逻辑通道创建完成后，都会自动执行你给出的 `ProtoInitializer`

### 4.2 客户端连接

客户端侧可以把 UDP 作为已连接模式使用：

```java
NetManager neta = new NetManager();

UdpSoConfig udp = new UdpSoConfig();
udp.setRcvPacketSize(64 * 1024);
udp.setRcvRemoteOnly(true);
udp.setSoWriteTimeoutMs(1000);
udp.setSndWriteRetryCount(3);
udp.setSndWriteRetryIntervalMs(100);

NetChannel channel = neta.connectSync(
        new InetSocketAddress("127.0.0.1", 18080),
        ctx -> ctx.addLast("app", appHandler),
        udp);
```

关键点：

- 客户端模式下 `UdpAsyncClientChannel` 会先对底层 `DatagramChannel` 执行 `connect(...)`
- 建链成功后会创建框架层 `UdpChannel` 并启动接收循环
- 当 `rcvRemoteOnly` 开启时，客户端会忽略并非目标远端发送来的数据报

### 4.3 发送与接收 `ByteBuf`

UDP 在 Neta 里按单个数据报处理 `ByteBuf`。每次入站回调对应一个完整的数据报片段，最简单的处理方式如下：

```java
ctx.addLast("echo", new ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf>() {
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown,
            ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {

        if (isRcv && rcvUp.hasMore()) {
            ByteBuf body = rcvUp.takeMessage();
            sndDown.addMessage(body);
        }
        return ProtoStatus.Next;
    }
});
```

这里要注意两点：

- 一次回调对应的是一个收到的数据报，而不是连续字节流的一段
- 如果单个数据报超过内部接收缓冲区大小，内容可能已经在操作系统层被截断

### 4.4 服务端按远端懒建通道

UDP 服务端没有 TCP 式 accept，因此 Neta 的服务端模式是按远端地址懒创建逻辑通道：

- 监听器先启动统一的 `UdpTransport` 接收循环
- 每收到一个数据报，就根据远端 `host:port` 查找 `channelMap`
- 如果该远端还没有对应逻辑通道，则创建新的 `UdpAsyncChannel` 和 `UdpChannel`
- 后续来自同一远端的数据报会复用该逻辑通道

这种模式适合需要按远端地址维护会话视图，但又不希望自己重复管理地址映射的场景。

### 4.5 典型配置项

常用配置项示例如下：

```java
UdpSoConfig udp = new UdpSoConfig();
udp.setSoRcvBuf(128 * 1024);
udp.setSoSndBuf(128 * 1024);
udp.setRcvPacketSize(64 * 1024);
udp.setRcvRemoteOnly(true);
udp.setSoWriteTimeoutMs(1000);
udp.setSndWriteRetryCount(3);
udp.setSndWriteRetryIntervalMs(100);
```

配置落点包括：

- `rcvPacketSize`：`UdpTransport` 内部接收缓冲区容量
- `soRcvBuf` / `soSndBuf`：`UdpSoConfigUtils.configListen(...)` 与 `configSocket(...)`
- `rcvRemoteOnly`：`UdpAsyncClientChannel.onDatagram(...)`
- `sndWriteRetryCount` / `sndWriteRetryIntervalMs`：`AbstractUdpWriteTask` 的异常与重试路径

## 5. 内部工作原理

### 5.1 服务端接收与分流路径

服务端关键链路如下：

```text
DatagramChannel
  -> bind(listenAddr)
  -> UdpTransport.startReceiveLoop(...)
  -> onDatagram(...)
  -> findOrCreateChannel(...)
  -> ByteBuffer -> ByteBuf
  -> notifyRcvChannelData(channelId, byteBuf)
```

具体过程分成 5 步：

1. `UdpAsyncServerChannel` 创建监听 socket，并绑定本地地址
2. `UdpTransport` 持续轮询底层 `Selector`，接收数据报
3. 收到数据报后，按远端 `host:port` 查找或创建逻辑 `UdpChannel`
4. 把共享接收缓冲区中的数据复制到新的 `ByteBuf`
5. 通过 `SoContextService.notifyRcvChannelData(...)` 投递到协议栈

这里最重要的特点是：

- 服务端只有一个底层 `DatagramChannel`
- 多个远端地址通过 `channelMap` 投影成多个逻辑 `UdpChannel`
- 通道创建时机由第一包数据驱动，而不是显式 accept 驱动

### 5.2 客户端运行路径

客户端模式的关键链路如下：

```text
UdpAsyncClientChannel.connectTo(...)
  -> transport.connect(remoteAddr)
  -> 创建 UdpChannel
  -> initChannel(...)
  -> UdpTransport.startReceiveLoop(...)
  -> onDatagram(...)
```

客户端模式下：

- `UdpAsyncClientChannel` 会先把底层 `DatagramChannel` 连接到目标远端
- 接收循环仍由 `UdpTransport` 驱动，而不是由 `UdpAsyncChannel` 自己读取
- 当启用 `rcvRemoteOnly` 时，只有来自已配置远端的数据报才会进入 pipeline

### 5.3 接收路径

接收路径由 `UdpTransport` 统一驱动：

```text
Selector.select(...)
  -> DatagramChannel.receive(receiveBuffer)
  -> flip(receiveBuffer)
  -> DatagramReceiver.onDatagram(remoteAddr, data)
  -> ByteBuffer -> ByteBuf
  -> notifyRcvChannelData(...)
```

接收处理的几个关键点：

- 内部使用一个可复用的直接内存 `ByteBuffer` 作为共享接收缓冲区
- 回调方必须在返回前消费或复制数据，因为缓冲区会被下一次接收复用
- `ByteBuf` 是在更高一层的客户端或服务端通道中创建的，而不是在 `UdpTransport` 内直接暴露给业务

### 5.4 发送路径

发送路径由 `UdpAsyncChannel` 和 `UdpWriteTask` 共同推进：

```text
NetChannel.sendData(...) / flush()
  -> SoSndContext 入队
  -> UdpAsyncChannel.write(...)
  -> 单写者门控 CAS
  -> UdpWriteTask.doWork(...)
  -> DatagramChannel.send(...) / write(...)
```

发送处理的几个关键点：

- `UdpAsyncChannel` 通过 `AtomicBoolean writing` 保证同一时刻只有一个发送任务在刷新底层通道
- `AbstractUdpWriteTask` 负责主循环、超时重试和异常归一化
- `UdpWriteTask` 根据当前通道是服务端逻辑通道还是客户端通道，选择 `send(...)` 或 `write(...)`

## 6. 数据流与异常流

### 6.1 数据流

入站数据流：

- 原生 `DatagramChannel.receive(...)`
- `UdpTransport`
- `ByteBuffer -> ByteBuf`
- Neta pipeline

出站数据流：

- 业务调用 `channel.sendData(...)`
- `SoSndContext` 入队
- `UdpWriteTask`
- 原生 `DatagramChannel.send(...)` 或 `write(...)`

### 6.2 异常流

当前实现重点处理几类异常：

- 发送超时：进入延迟重试，或在重试耗尽后抛出 `SoWriteTimeoutException`
- 通道关闭：转成 `SoCloseException` 或 `SoUnfinishedSndException`，并清理剩余待发数据
- 接收异常：转成 `SoRcvException`，由上下文统一上报
- 绑定或建链异常：分别转成 `SoBindException` 或 `SoConnectException`

## 7. 配置与选型建议

### 7.1 常用配置建议

- 只做基础 UDP 接入：
  - 使用默认 `UdpSoConfig`
- 需要接收较大单包：
  - 同时评估并调大 `rcvPacketSize` 和 `soRcvBuf`
- 需要客户端只接受目标远端的数据报：
  - 开启 `rcvRemoteOnly`
- 需要更稳妥的发送超时行为：
  - 配置 `soWriteTimeoutMs`
  - 配置 `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs`

### 7.2 适用场景

更适合 UDP 的场景通常是：

- 业务天然按单个数据报组织数据
- 需要低开销、低延迟，而不是严格的可靠性和顺序保证
- 希望按远端地址维护轻量会话视图，但不想自己维护地址到通道的映射
- 应用层已经有自己的确认、重传或会话管理协议

如果你的业务需要稳定可靠的连续字节流，或者不希望自己处理重传、顺序和连接状态，那么 TCP 往往更合适；如果你需要低延迟的数据报语义或者要在 UDP 之上封装自己的上层协议，UDP 更贴近目标场景。

## 8. 使用注意事项

- 不要把 UDP 服务端的 `UdpChannel` 理解成真实独占 socket
- 不要忽略 `rcvPacketSize` 对单包截断的影响
- 当接收回调拿到共享 `ByteBuffer` 时，应在返回前完成消费或拷贝
- `sndWriteRetryCount` 解决的是框架发送推进问题，不是业务语义上的可靠重传
- 客户端模式和服务端模式共用部分发送模型，但接收路径的通道建立时机不同

## 9. 总结

Neta 对 UDP 的支持定位很明确：

- 提供统一的监听、建链和协议栈装配入口
- 保留 UDP 数据报边界，并把它们转换成 `ByteBuf` 进入 pipeline
- 在服务端按远端地址懒创建逻辑通道
- 提供基础的 socket 配置、接收包控制和发送重试能力

它不是一套替你补足 UDP 可靠语义的高层框架，但已经足够作为 Neta 中的轻量数据报传输基础，承接普通 UDP 协议或你自定义的 UDP 上层协议实现。
