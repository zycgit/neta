---
sidebar_position: 9
title: SCTP
description: 说明 Neta 的 SCTP 传输支持范围、对象模型、运行机制、配置项和服务端客户端的使用方式。
---

## 简介

SCTP 是一种面向消息的传输协议。它按消息发送和接收具备清晰的消息边界；同一条关联内可以带多个 stream，并且协议本身支持多宿主地址。

Neta 在 `neta-core` 的 `net.hasor.neta.channel.sctp` 包中提供 SCTP 传输支持，重点在于把 JDK `com.sun.nio.sctp` 的底层能力适配到 Neta 统一的 `NetManager`、`NetListen`、`NetChannel` 和 pipeline 模型里。

整体上可以分成 4 层：

- Provider 层：`SctpProvider` 负责创建客户端和服务端底层通道
- 传输层：`SctpAsyncChannel` 与 `SctpAsyncServerChannel` 负责连接、监听、收包和发包
- 通道层：`SctpChannel` 负责把底层通道接到 Neta 的协议栈和事件体系
- 对象层：`SctpMessage`、`SctpNotificationEvent`、`SctpSocketAddress` 负责把 SCTP 专有信息暴露给上层

这套实现主要覆盖四类能力：

- 使用统一 API 建立 SCTP 服务端和客户端连接
- 保留 SCTP 的消息边界和 `MessageInfo` 元数据
- 把 SCTP 协议通知映射成 Neta 的网络事件
- 在平台支持时把接收缓冲、发送缓冲、保活和重试等参数应用到底层 socket

## 范围与能力

- 传输入口
  - `SctpProvider` 作为 `AsyncChannelProvider` 接入 `NetManager`
  - 支持 `bind(...)` 创建 SCTP 监听器
  - 支持 `connectSync(...)` 和 `connectAsync(...)` 创建 SCTP 客户端连接
- 消息模型
  - 入站数据会被包装为 `SctpMessage`
  - `SctpMessage` 同时携带 `MessageInfo` 与 `ByteBuf`
  - 业务直接发送 `ByteBuf` 时，发送任务会自动包装成默认 stream 0 的 `SctpMessage`
- 事件模型
  - 支持把 Association 变化、对端地址变化、发送失败、优雅关闭等通知转换成 `SctpNotificationEvent`
  - 这些通知通过网络事件管线传播，而不是作为普通 payload 消息传播
- 地址模型
  - 服务端 accept 到的连接使用 `SctpSocketAddress` 暴露 `Association` 和地址集合
  - 框架层可以保留 SCTP 多地址信息，而不是强制压平成单一 `InetSocketAddress`
- 配置能力
  - 支持 `swapRcvBuf` 和 `swapSndBuf` 两个交换缓冲区参数
  - 支持 `soRcvBuf` 和 `soSndBuf` 落到底层 SCTP socket
  - 支持 `soKeepAlive`、`soKeepIdleSec`、`soKeepIntervalSec`、`soKeepCount` 在平台支持时应用到底层 socket
  - 支持 `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs` 控制发送超时后的重试行为（框架能力）
- 运行模式
  - 客户端模式由 `SctpAsyncChannel` 自己持有 selector 并驱动 connect 和 read
  - 服务端模式由 `SctpAsyncServerChannel` 统一 accept 并驱动所有已接入连接的 read

## 使用限制

- 不支持多宿主地址编排策略本身，例如动态增删地址、主路径切换策略管理
- Neta 负责传输接入，不负责把 SCTP 再封装成类似 TCP 的流式接口
- `SctpMessage` 是对底层消息的原样包装，不会自动解释 stream、PPID 或无序标记的业务语义
- 保活相关参数只有在当前 JDK 和当前内核同时暴露对应 socket 选项时才会真正生效
- 依赖 `com.sun.nio.sctp`，因此只在 JDK 与操作系统都提供 SCTP 支持时可用
  - 在 macOS 等默认不提供 SCTP 内核支持的环境里，通常只能完成编译，无法真正跑通 SCTP 测试
- 客户端模式下的本地地址不是当前对外 API 的重点暴露对象，服务端 accept 场景对多地址信息的呈现更完整
- 发送交换缓冲区用于初始化发送暂存区，但当待发送消息更大时仍会按需扩容，不是硬性上限

## 3. 核心对象

### 3.1 整体对象分层

```text
Application
  -> NetManager
  -> bind / connect

SctpProvider
  -> SctpAsyncServerChannel / SctpAsyncChannel

SctpChannel
  -> ProtoInitializer / ProtoStackChain

SctpMessage
  -> MessageInfo + ByteBuf

SctpNotificationEvent
  -> SCTP Notification
```

### 3.2 关键对象职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| `SctpProvider` | 创建 SCTP 客户端与服务端底层通道 | `NetManager` 根据 `SctpSoConfig` 选择 provider |
| `SctpAsyncServerChannel` | 监听端 selector、accept 与服务端收包循环 | 服务端 `bind(...)` |
| `SctpAsyncChannel` | 客户端 connect/read 循环，或服务端已接入连接的写通道包装 | 客户端连接、服务端 accepted channel |
| `SctpChannel` | 框架层活动连接对象，挂接协议栈和通知处理器 | 业务 handler 收发数据 |
| `SctpMessage` | 暴露一条完整 SCTP 消息及其元数据 | 需要读取 stream id、PPID、无序标记 |
| `SctpNotificationEvent` | 暴露协议级通知事件 | 需要感知 association 变化、关闭、发送失败 |
| `SctpSocketAddress` | 包装 `Association` 与地址集合 | 服务端需要读取多宿主地址信息 |
| `SctpSoConfig` | SCTP 传输配置对象 | 调整 buffer、keepalive、发送重试 |

### 3.3 `SctpMessage` 的定位

SCTP 在 Neta 里不是裸 `ByteBuf` 优先，而是 `SctpMessage` 优先。

这套模型体现出以下特征：

- 入站时，业务收到的是一条消息，而不是某段无边界字节流
- 出站时，需要显式控制 stream、PPID 或发送属性时，应主动构造 `SctpMessage`
- 只关注 payload 时，也可以直接发送 `ByteBuf`，框架会生成默认 `MessageInfo`

典型读取方式如下：

```java
ctx.addLast("app", new ProtoDuplexer<SctpMessage, SctpMessage, SctpMessage, SctpMessage>() {
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,
            ProtoRcvQueue<SctpMessage> rcvUp, ProtoSndQueue<SctpMessage> rcvDown,
            ProtoRcvQueue<SctpMessage> sndUp, ProtoSndQueue<SctpMessage> sndDown) throws Throwable {

        if (isRcv && rcvUp.hasMore()) {
            SctpMessage msg = rcvUp.takeMessage();
            int streamNo = msg.getInfo().streamNumber();
            int readable = msg.getByteBuf().readableBytes();
            System.out.println("stream=" + streamNo + ", bytes=" + readable);
        }
        return ProtoStatus.Next;
    }
});
```

### 3.4 `SctpNotificationEvent` 的定位

`SctpNotificationEvent` 不是普通业务消息，而是 SCTP 协议层事件。

常见通知包括：

- `AssociationChangeNotification`
- `PeerAddressChangeNotification`
- `SendFailedNotification`
- `ShutdownNotification`

业务需要感知底层关联状态变化时，可以在通道上监听网络事件，而不是在消息 handler 里硬编码底层通知。

## 4. 使用方式

### 4.1 服务端监听

最常见的服务端用法是直接创建 `SctpSoConfig`，然后通过 `NetManager.bind(...)` 绑定地址。

```java
NetManager neta = new NetManager();

SctpSoConfig sctp = new SctpSoConfig();
sctp.setSoRcvBuf(128 * 1024);
sctp.setSoSndBuf(128 * 1024);
sctp.setSwapRcvBuf(64 * 1024);
sctp.setSwapSndBuf(64 * 1024);

ProtoInitializer initializer = ctx -> {
    ctx.addLast("app", appHandler);
};

NetListen listen = neta.bind(new InetSocketAddress("127.0.0.1", 18080), initializer, sctp);
```

关键点：

- `NetManager` 会根据 `SctpSoConfig` 的协议名称选择 `SctpProvider`
- 服务端监听通道会先创建 `SctpAsyncServerChannel`
- 真正的活动连接 `SctpChannel` 会在有可读事件、且框架确认 accept 成功后按需创建
- 每个新连接创建完成后，都会自动执行你给出的 `ProtoInitializer`

### 4.2 客户端连接

客户端侧同样直接使用 `SctpSoConfig` 即可：

```java
NetManager neta = new NetManager();

SctpSoConfig sctp = new SctpSoConfig();
sctp.setConnectTimeoutMs(5000);
sctp.setSoWriteTimeoutMs(1000);
sctp.setSndWriteRetryCount(3);
sctp.setSndWriteRetryIntervalMs(100);

NetChannel channel = neta.connectSync(
        new InetSocketAddress("127.0.0.1", 18080),
        ctx -> ctx.addLast("app", appHandler),
        sctp);
```

关键点：

- 客户端模式下 `SctpAsyncChannel` 自己持有 selector
- `connectSync(...)` 最终仍然会先创建底层 SCTP 通道，再在连接建立后创建框架层 `SctpChannel`
- 如果底层 `connect(...)` 立即成功，框架会直接进入后续初始化；否则会注册 `OP_CONNECT` 等待完成

### 4.3 发送 `SctpMessage`

需要显式控制 stream 时，可以主动构造 `SctpMessage`：

```java
ByteBuf body = ByteBuf.wrap("hello sctp".getBytes("UTF-8"));
MessageInfo info = MessageInfo.createOutgoing(null, 2);
info.payloadProtocolID(1001);

SctpMessage msg = SctpMessage.of(info, body);
channel.sendData(msg);
channel.flush();
```

直接发送原始 `ByteBuf` 时：

```java
channel.sendData(ByteBuf.wrap(new byte[] {1, 2, 3}));
channel.flush();
```

那么 `SctpWriteTask` 会把它自动包装成默认出站 `MessageInfo`，使用 stream 0 发出。

### 4.4 监听 SCTP 通知事件

需要观察 Association 变化、对端地址变化或优雅关闭等通知时，可以订阅网络事件：

```java
channel.subscribe(payload -> {
    Object data = payload.getData();
    return data instanceof SctpNotificationEvent;
}, event -> {
    SctpNotificationEvent data = (SctpNotificationEvent) event.getData();
    System.out.println("notification = " + data.getNotification().getClass().getSimpleName());
});
```

实际业务里更常见的做法是把这类事件转成你自己的状态通知，而不是把 JDK 的通知类型直接扩散到所有上层模块。

### 4.5 开发与测试环境

SCTP 的关键前提不是 Java 代码本身，而是运行环境真的提供 SCTP 能力。

对于当前仓库，建议优先使用：

- `neta/.devcontainer/java-8`

这个 devcontainer 会安装 `lksctp-tools`，并以 privileged 模式运行，更适合执行 `neta-core` 里的 SCTP 测试。

宿主机为 macOS 时，常见情况如下：

- 本机可以完成源码编辑和普通编译
- 但实际 SCTP 测试要放到 Linux 容器里执行

## 5. 内部工作原理

### 5.1 服务端收包路径

服务端的关键链路如下：

```text
SctpServerChannel
  -> accept 原生 SctpChannel
  -> register OP_READ
  -> receive MessageInfo + payload
  -> wrap SctpMessage
  -> notifyRcvChannelData(channelId, message)
  -> 进入 Neta pipeline
```

具体过程分成 5 步：

1. `SctpAsyncServerChannel` 创建监听 socket，并绑定本地地址
2. selector 监听 `OP_ACCEPT`，accept 出原生 SCTP 通道
3. 当通道变成可读时，`receive(...)` 读出 `MessageInfo` 和 payload
4. 框架把接收到的内容包装成 `SctpMessage`
5. 通过 `SoContextService.notifyRcvChannelData(...)` 投递进协议栈

这里最重要的特点是：

- Neta 不会把 SCTP 消息拆成无边界字节流
- 一条消息从底层进入到 pipeline 时，`MessageInfo` 与 payload 始终绑定在一起
- 消息分片没有一次收完时，框架会继续读取，直到 `MessageInfo.isComplete()` 为 true

### 5.2 客户端运行路径

客户端模式和服务端模式最大的差别在于 selector 归属。

客户端模式下：

- `SctpAsyncChannel` 自己创建 selector
- `connectTo(...)` 负责注册 `OP_CONNECT` 和 `OP_READ`
- 连接建立后，后续读事件仍由当前对象自己驱动

服务端 accepted 模式下：

- `SctpAsyncChannel` 只是底层写通道适配器
- 读事件由 `SctpAsyncServerChannel` 的服务端 selector 统一驱动

因此，同一个 `SctpAsyncChannel` 类型同时服务于客户端和服务端 accepted 连接。

### 5.3 发送路径

发送路径由 `SctpWriteTask` 驱动：

```text
SoSndContext
  -> peekData()
  -> SctpMessage / ByteBuf
  -> 写入发送交换缓冲区
  -> SctpChannel.send(buffer, messageInfo)
  -> 成功则推进队列，失败则走重试或异常上报
```

发送时有三个实现细节要注意：

- `swapSndBuf` 用于初始化发送交换缓冲区大小
- 某条消息比当前交换缓冲区更大时，缓冲区会按需扩容
- `InterruptedByTimeoutException` 会触发 `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs` 对应的重试逻辑

### 5.4 配置落点

`SctpSoConfig` 中的主要参数当前落在这些地方：

| 配置项 | 落点 |
| --- | --- |
| `swapRcvBuf` | `SctpAsyncChannel` 和 `SctpAsyncServerChannel` 的接收交换缓冲区初始化 |
| `swapSndBuf` | `SctpWriteTask` 的发送交换缓冲区初始化 |
| `soRcvBuf` / `soSndBuf` | `SctpSoConfigUtils.configListen(...)` 与 `configSocket(...)` |
| `soKeepAlive` / `soKeepIdleSec` / `soKeepIntervalSec` / `soKeepCount` | `SctpSoConfigUtils.configSocket(...)`，前提是当前平台支持对应选项 |
| `sndWriteRetryCount` / `sndWriteRetryIntervalMs` | `SctpWriteTask.handleException(...)` |

## 6. 数据流、事件流与异常流

### 6.1 数据流

入站数据流：

- 原生 `SctpChannel.receive(...)`
- `MessageInfo + ByteBuffer`
- `SctpMessage`
- Neta pipeline

出站数据流：

- 业务调用 `channel.sendData(...)`
- `SoSndContext` 入队
- `SctpWriteTask`
- 原生 `SctpChannel.send(...)`

### 6.2 事件流

SCTP 的通知事件不会混入普通消息流，而是单独走网络事件流：

- Association 变化
- 对端地址变化
- 发送失败
- 优雅关闭

其中 `ShutdownNotification` 除了投递 `SctpNotificationEvent`，还会同步触发通道关闭通知。

### 6.3 异常流

当前实现重点处理三类异常：

- 发送超时：进入重试，或在重试耗尽后抛出 `SoWriteTimeoutException`
- 通道关闭：把未发送完成的数据清理掉，并转成关闭相关异常
- 平台不支持：在探测 socket 选项或初始化 SCTP 通道时记录告警，实际是否可运行取决于运行环境

## 7. 配置与选型建议

### 7.1 常用配置建议

- 只做基础接入：
  - 使用默认 `SctpSoConfig`
- 需要控制消息较大：
  - 适当调大 `swapRcvBuf` 和 `swapSndBuf`
- 需要更稳妥的发送超时行为：
  - 配置 `soWriteTimeoutMs`
  - 配置 `sndWriteRetryCount` 和 `sndWriteRetryIntervalMs`
- 需要长连接保活：
  - 配置 `soKeepAlive`
  - 根据平台能力补充 `soKeepIdleSec`、`soKeepIntervalSec`、`soKeepCount`

### 7.2 适用场景

更适合 SCTP 的场景通常是：

- 业务天然按消息组织，而不是按字节流组织
- 希望在一条关联里区分多个 stream
- 对底层关联状态、路径变化、发送失败等协议事件有感知需求

协议本质上属于单条字节流，且不需要 SCTP 的多 stream 与通知模型时，TCP 往往更简单。

## 8. 使用注意事项

- 优先把 `SctpMessage` 当成主消息类型来设计协议栈，不要强行把 SCTP 当 TCP 用
- 只有在平台支持时，keepalive 相关参数才会真正生效
- 不要把 `swapSndBuf` 理解成发送上限，它只是初始交换缓冲区大小
- 在 macOS 宿主机上做开发时，建议把真实 SCTP 测试放进 `neta/.devcontainer/java-8` 对应的 Linux 容器里执行
- 如果在容器里测试 `neta-core`，还需要保证 `cobble-all` 依赖可解析；当前仓库的常见做法是挂载宿主机 Maven 仓库

## 9. 总结

Neta 对 SCTP 的支持定位很明确：

- 提供统一的建链、监听和协议栈装配入口
- 保留 SCTP 的消息边界和元数据
- 暴露底层通知事件
- 提供基本的 socket 配置和发送重试能力

它不是一套独立的高层 SCTP 应用框架，但已经足够作为 Neta 中的稳定传输层基础，承接你自己的上层协议实现。
