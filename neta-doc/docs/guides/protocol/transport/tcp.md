---
sidebar_position: 10
title: TCP
description: 说明 Neta 的 TCP 传输支持范围、对象模型、运行机制、配置项以及服务端和客户端的使用方式。
---

## 简介

TCP 是一种面向连接、面向字节流的传输协议。TCP 本身不保留消息边界，应用层收到的是连续字节流，因此通常需要在协议栈里自己完成拆包、粘包处理和帧边界识别。

Neta 在 `neta-core` 的 `net.hasor.neta.channel.tcp` 包中提供 TCP 传输支持，目标是把 JDK AIO 的 `AsynchronousSocketChannel` 和 `AsynchronousServerSocketChannel` 适配到统一的 `NetManager`、`NetListen`、`NetChannel` 和 pipeline 模型。

整体上可以分成 4 层：

- Provider 层：`TcpProvider` 负责创建客户端和服务端底层通道，以及共享的 `AsynchronousChannelGroup`
- 传输层：`TcpAsyncChannel` 与 `TcpAsyncServerChannel` 负责 connect、bind、accept、read 和 write
- 通道层：`TcpChannel` 负责把底层连接接入 Neta 的协议栈、订阅机制和生命周期管理
- 处理器层：`TcpConnectCompletionHandler`、`TcpAcceptCompletionHandler`、`TcpRcvCompletionHandler`、`TcpSndCompletionHandler` 负责异步连接、接收和发送循环

这套实现主要覆盖四类能力：

- 使用统一 API 建立 TCP 服务端监听与客户端连接
- 把底层字节流接入 Neta pipeline，让上层通过编解码器或协议处理器定义帧边界
- 提供单写者发送模型和持续读循环，屏蔽 AIO 回调细节
- 在平台支持时应用接收缓冲、发送缓冲、地址复用和 keepalive 等 socket 参数

## 范围与能力

- 传输入口
  - `TcpProvider` 作为 `AsyncChannelProvider` 接入 `NetManager`
  - 支持 `bind(...)` 创建 TCP 监听器
  - 支持 `connectSync(...)` 和 `connectAsync(...)` 创建 TCP 客户端连接
- 连接模型
  - 服务端使用一个监听 socket 接收连接
  - 每个已接入连接都会创建独立的 `TcpAsyncChannel` 和 `TcpChannel`
  - 客户端连接与服务端 accepted 连接共用同一套读写处理器模型
- 数据模型
  - 入站数据以 `ByteBuf` 形式进入 Neta pipeline
  - TCP 不保留消息边界，因此帧划分由上层协议栈负责
  - 出站数据通过 `SoSndContext` 排队，再由 `TcpSndCompletionHandler` 按顺序刷出
- 配置能力
  - 支持 `swapRcvBuf` 和 `swapSndBuf` 两个交换缓冲区参数
  - 支持 `soRcvBuf` 和 `soSndBuf` 落到底层 TCP socket
  - 支持 `soKeepAlive`、`soKeepIdleSec`、`soKeepIntervalSec`、`soKeepCount` 在平台支持时应用到底层 socket
  - 支持基础连接超时、读超时、写超时等通用 `SoConfig` 能力
- 生命周期能力
  - 支持连接建立回调、收包异常回调、发包异常回调和通道关闭通知
  - 支持 `shutdownInput()` 关闭读方向，而不立即关闭整个连接

## 使用限制

- TCP 是字节流协议，Neta 不会自动为业务定义消息边界
- 如果上层协议需要按行、按长度字段或按固定帧切分，需要在 pipeline 中自行加入对应编解码器或处理逻辑
- `swapRcvBuf` 和 `swapSndBuf` 是框架内部交换缓冲区大小，不是底层 socket 缓冲区上限
- keepalive 相关参数只有在当前 JDK 和当前操作系统都暴露对应 socket 选项时才会真正生效
- 服务端 accepted 连接和客户端连接虽然都表现为 `TcpChannel`，但前者由监听器派生，后者由主动 connect 建立，生命周期起点不同
- 发送模型是单写者模型，同一连接上的写操作会按队列顺序推进，而不是并行直接写底层 socket

## 3. 核心对象

### 3.1 整体对象分层

```text
Application
  -> NetManager
  -> bind / connect

TcpProvider
  -> TcpAsyncServerChannel / TcpAsyncChannel

TcpChannel
  -> ProtoInitializer / ProtoStackChain

TcpRcvCompletionHandler
  -> read loop

TcpSndCompletionHandler
  -> write loop
```

### 3.2 关键对象职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| `TcpProvider` | 创建 TCP 客户端与服务端底层通道，以及共享的 `AsynchronousChannelGroup` | `NetManager` 根据 `TcpSoConfig` 选择 provider |
| `TcpAsyncServerChannel` | 绑定监听地址并维持 accept 循环 | 服务端 `bind(...)` |
| `TcpAsyncChannel` | 封装单个底层 socket 的读写、connect 和地址视图 | 客户端连接、服务端 accepted channel |
| `TcpChannel` | 框架层活动连接对象，挂接协议栈和生命周期 | 业务 handler 收发数据 |
| `TcpAcceptCompletionHandler` | 处理服务端 accept 完成后的建链、配置和读循环启动 | 服务端接入新连接 |
| `TcpConnectCompletionHandler` | 处理客户端 connect 完成后的初始化 | 客户端建链 |
| `TcpRcvCompletionHandler` | 持续驱动单连接读循环 | 所有活动连接 |
| `TcpSndCompletionHandler` | 维护单连接发送队列与单写者写循环 | 所有活动连接 |
| `TcpSoConfig` | TCP 传输配置对象 | 调整 buffer、keepalive、超时 |

### 3.3 `TcpChannel` 的定位

`TcpChannel` 是框架层暴露给业务的活动连接对象。它不直接实现底层 AIO 读写，而是把以下几个角色绑定在一起：

- 一个 `TcpAsyncChannel`，作为真正的底层 socket 适配器
- 一个 `TcpRcvCompletionHandler`，负责持续读取底层字节流
- 一个 `TcpSndCompletionHandler`，负责有序刷新发送队列
- 一个协议栈初始化结果，也就是业务配置的 `ProtoInitializer`

这套模型体现出以下特征：

- 业务面对的是统一的 `NetChannel` API，而不是 AIO 回调接口
- 连接关闭时，读处理器和写处理器会跟随 `TcpChannel` 一起释放
- 接收路径可以走快速通道，直接把 `ByteBuf` 投递到通道对象，减少额外查找开销

### 3.4 `TcpSoConfig` 的定位

`TcpSoConfig` 是 TCP 传输的专用配置对象，主要负责两类参数：

- 框架内部交换缓冲区大小
  - `swapRcvBuf`：接收处理器使用的直接内存中转缓冲区大小
  - `swapSndBuf`：发送处理器使用的直接内存中转缓冲区大小
- 底层 socket 选项
  - `soRcvBuf` / `soSndBuf`
  - `soKeepAlive`
  - `soKeepIdleSec` / `soKeepIntervalSec` / `soKeepCount`

它与通用 `SoConfig` 一起使用，用于统一描述 TCP 连接的连接超时、读超时、写超时和 socket 行为。

## 4. 使用方式

### 4.1 服务端监听

最常见的服务端用法是直接创建 `TcpSoConfig`，然后通过 `NetManager.bind(...)` 绑定地址。

```java
NetManager neta = new NetManager();

TcpSoConfig tcp = new TcpSoConfig();
tcp.setSoRcvBuf(128 * 1024);
tcp.setSoSndBuf(128 * 1024);
tcp.setSwapRcvBuf(64 * 1024);
tcp.setSwapSndBuf(64 * 1024);
tcp.setSoKeepAlive(true);

ProtoInitializer initializer = ctx -> {
    ctx.addLast("app", appHandler);
};

NetListen listen = neta.bind(new InetSocketAddress("127.0.0.1", 18080), initializer, tcp);
```

关键点：

- `NetManager` 会根据 `TcpSoConfig` 的协议名称选择 `TcpProvider`
- 服务端监听通道会创建 `TcpAsyncServerChannel`
- 每个新的客户端连接在 accept 成功后，都会创建新的 `TcpChannel`
- 每个新连接完成创建后，都会自动执行你给出的 `ProtoInitializer`

### 4.2 客户端连接

客户端侧同样直接使用 `TcpSoConfig`：

```java
NetManager neta = new NetManager();

TcpSoConfig tcp = new TcpSoConfig();
tcp.setConnectTimeoutMs(5000);
tcp.setSoReadTimeoutMs(3000);
tcp.setSoWriteTimeoutMs(1000);
tcp.setSoKeepAlive(true);

NetChannel channel = neta.connectSync(
        new InetSocketAddress("127.0.0.1", 18080),
        ctx -> ctx.addLast("app", appHandler),
        tcp);
```

关键点：

- 客户端模式下 `TcpAsyncChannel` 负责主动发起 `connect(...)`
- 连接建立完成后，`TcpConnectCompletionHandler` 会初始化 `TcpChannel` 并启动读循环
- `connectSync(...)` 与 `connectAsync(...)` 的主要差别在于 future 等待方式，而不是底层传输模型

### 4.3 发送与接收 `ByteBuf`

TCP 在 Neta 里默认按 `ByteBuf` 处理连续字节流。最简单的协议处理方式是直接在 handler 中读取和写回字节：

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

- 业务拿到的是字节流分段，而不是天然一条一条的完整消息
- 如果你的协议有固定帧格式，应先在 pipeline 前面加入拆包处理器，再把完整帧交给业务逻辑

### 4.4 关闭读方向

当业务需要停止接收但保留连接其他状态时，可以使用 `TcpChannel.shutdownInput()`：

```java
TcpChannel tcpChannel = (TcpChannel) channel;
tcpChannel.shutdownInput();
```

该操作会调用底层 `AsynchronousSocketChannel.shutdownInput()`，后续接收路径会感知这一状态，并按输入关闭场景进行通知。

### 4.5 典型配置项

常用配置项示例如下：

```java
TcpSoConfig tcp = new TcpSoConfig();
tcp.setConnectTimeoutMs(5000);
tcp.setSoReadTimeoutMs(3000);
tcp.setSoWriteTimeoutMs(1000);
tcp.setSoRcvBuf(128 * 1024);
tcp.setSoSndBuf(128 * 1024);
tcp.setSwapRcvBuf(64 * 1024);
tcp.setSwapSndBuf(64 * 1024);
tcp.setSoKeepAlive(true);
tcp.setSoKeepIdleSec(60);
tcp.setSoKeepIntervalSec(10);
tcp.setSoKeepCount(3);
```

配置落点包括：

- `swapRcvBuf`：`TcpRcvCompletionHandler` 初始化接收交换缓冲区
- `swapSndBuf`：`TcpSndCompletionHandler` 初始化发送交换缓冲区
- `soRcvBuf` / `soSndBuf`：`TcpSoConfigUtils.configListen(...)` 与 `configSocket(...)`
- `soKeepAlive` 等 keepalive 参数：`TcpSoConfigUtils.configSocket(...)`
- `connectTimeoutMs`、`soReadTimeoutMs`、`soWriteTimeoutMs`：连接、读、写处理器的超时路径

## 5. 内部工作原理

### 5.1 服务端接入路径

服务端关键链路如下：

```text
AsynchronousServerSocketChannel
  -> bind(listenAddr)
  -> accept(...)
  -> TcpAcceptCompletionHandler.completed(...)
  -> 创建 TcpAsyncChannel
  -> 创建 TcpChannel
  -> initChannel(...)
  -> TcpRcvCompletionHandler.read()
```

具体过程分成 5 步：

1. `TcpAsyncServerChannel` 创建监听 socket，并绑定本地地址
2. `accept(...)` 成功后交由 `TcpAcceptCompletionHandler` 处理
3. 处理器会先重新挂起下一次 accept，保证 accept 循环持续存在
4. 框架校验当前连接是否允许接入，并对 accepted socket 应用 TCP 配置
5. 创建 `TcpChannel`，初始化协议栈，然后启动读循环

这里最重要的特点是：

- 监听器和活动连接严格分离
- 每个 accepted 连接都拥有独立的读写处理器
- accept 循环会尽量提前挂起，减少连接接入间隙

### 5.2 客户端运行路径

客户端模式的关键链路如下：

```text
TcpAsyncChannel.connectTo(...)
  -> configSocket(...)
  -> AsynchronousSocketChannel.connect(...)
  -> TcpConnectCompletionHandler.completed(...)
  -> initChannel(...)
  -> TcpRcvCompletionHandler.read()
```

客户端模式下：

- `TcpAsyncChannel` 负责发起底层连接
- `TcpConnectCompletionHandler` 在连接成功后完成框架通道初始化
- 连接建立后，读循环和写循环都转入普通活动连接模型

因此，客户端与服务端 accepted 连接在真正进入工作态之后，内部读写路径基本一致。

### 5.3 接收路径

接收路径由 `TcpRcvCompletionHandler` 持续驱动：

```text
TcpAsyncChannel.read(rcvSwapBuffer, ...)
  -> completed(bytesRead, ctx)
  -> swapBuffer 复制到 ByteBuf
  -> 更新接收统计
  -> notifyRcvSingle(...) / notifyRcvChannelData(...)
  -> 继续下一次 read()
```

接收处理的几个关键点：

- 底层先把数据读入可复用的直接内存交换缓冲区
- 然后复制到新的 `ByteBuf` 再投递到 pipeline
- 当读取结果为 0 时，框架会投递 `ByteBuf.EMPTY` 并继续下一次读取
- 当读取结果小于 0 时，会根据是否本地 `shutdownInput()` 或远端关闭，进入不同关闭分支

### 5.4 发送路径

发送路径由 `TcpSndCompletionHandler` 维护单写者模型：

```text
SoSndContext
  -> peekData()
  -> transferTo(sndSwapBuf)
  -> TcpAsyncChannel.write(...)
  -> completed(bytesWritten, ctx)
  -> 推进队列或继续写剩余数据
```

发送处理的几个关键点：

- 每个连接只允许一个挂起的底层 `write(...)` 调用
- 某次底层写如果只写出部分字节，会继续写交换缓冲区剩余部分
- 当队列暂时为空时，处理器会重置写标记，并再次检查是否有并发新数据进入队列
- 异常路径会把发送失败统一转换为 `SoWriteTimeoutException`、`SoCloseException` 或 `SoSndException`

## 6. 数据流与异常流

### 6.1 数据流

入站数据流：

- 原生 `AsynchronousSocketChannel.read(...)`
- `TcpRcvCompletionHandler`
- `ByteBuf`
- Neta pipeline

出站数据流：

- 业务调用 `channel.sendData(...)`
- `SoSndContext` 入队
- `TcpSndCompletionHandler`
- 原生 `AsynchronousSocketChannel.write(...)`

### 6.2 异常流

当前实现重点处理几类异常：

- 连接未完成：在连接超时窗口内延迟重试，否则转成 `SoConnectTimeoutException`
- 读取超时：转成 `SoReadTimeoutException`，并继续后续读循环
- 写入超时：转成 `SoWriteTimeoutException`，并安排再次发送
- 通道关闭：转成 `SoCloseException`，并清理未完成发送数据
- 其他读写异常：分别转成 `SoRcvException` 或 `SoSndException`

## 7. 配置与选型建议

### 7.1 常用配置建议

- 只做基础 TCP 接入：
  - 使用默认 `TcpSoConfig`
- 数据量较大，且希望降低复制次数带来的频繁扩容：
  - 适当调大 `swapRcvBuf` 和 `swapSndBuf`
- 需要更稳定的长连接探活：
  - 开启 `soKeepAlive`
  - 在平台支持时配置 `soKeepIdleSec`、`soKeepIntervalSec`、`soKeepCount`
- 需要显式控制超时行为：
  - 配置 `connectTimeoutMs`
  - 配置 `soReadTimeoutMs`
  - 配置 `soWriteTimeoutMs`

### 7.2 适用场景

更适合 TCP 的场景通常是：

- 业务本身以连续字节流或自定义帧协议组织数据
- 不需要 SCTP 那样的多 stream 和协议通知模型
- 需要在各类操作系统和部署环境里获得最通用的传输兼容性
- 希望基于成熟的 keepalive、超时和 socket 调优手段做稳定连接管理

如果你的业务天然按消息组织，并且需要保留消息边界或底层关联事件，那么 SCTP 会更贴近协议语义；如果你需要最广泛的兼容性和最常见的网络栈支持，TCP 通常是默认选择。

## 8. 使用注意事项

- 不要把一次 `read(...)` 回调拿到的 `ByteBuf` 直接理解为完整业务包
- 协议边界应由 codec 或上层协议处理器定义，而不是依赖底层 TCP 读回调的切分结果
- `swapRcvBuf` 和 `swapSndBuf` 是内部交换缓冲区，不等同于底层 socket 缓冲区
- keepalive 细粒度参数是否可用，取决于当前 JDK 和平台支持的 socket 选项集合
- 服务端和客户端都复用统一的 `TcpChannel` 模型，因此业务 handler 通常不需要关心连接来自 accept 还是 connect

## 9. 总结

Neta 对 TCP 的支持定位很明确：

- 提供统一的监听、建链和协议栈装配入口
- 把底层 AIO connect、accept、read、write 细节封装成稳定的通道模型
- 提供持续读循环和单写者发送循环
- 提供基础的 socket 配置、超时控制和 keepalive 支持

它不是一套替你定义应用层协议边界的高层框架，但作为 Neta 的通用传输基础，已经能够稳定承接绝大多数基于 TCP 的上层协议实现。
