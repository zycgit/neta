---
sidebar_position: 3
title: SSL/TLS
description: 说明 Neta 的 SSL/TLS 装配入口、证书配置、ALPN 与 SNI、握手事件、close_notify 行为边界和 HTTPS 集成方式。
---

## 1. 简介

Neta 在 neta-core 模块中提供基于 SSLEngine 的 SSL/TLS codec，核心入口是 SslDuplexer。它位于传输层和上层协议之间，负责把网络侧的 TLS 密文字节还原成明文 ByteBuf，再把上层输出的明文重新封装为 TLS 记录。

整体上可以分成 4 个关注面：

- 配置层：通过 SslCertConfig 和 SslConfig 描述证书、信任链、ALPN、SNI、协议版本、密码套件与客户端认证
- 双工入口层：通过 SslDuplexer 把 ByteBuf 和 TLS record 互转
- 连接状态层：通过 SslContext 暴露每个连接的握手状态、ALPN 结果、SNI 信息和本地开关
- 事件层：通过 SslHandshakeEvent 和 SslCloseNotifyEvent 向上层广播握手完成和 close_notify 到达

这套实现主要解决四类问题：

- 如何把 TLS 握手和数据收发接入 Neta pipeline
- 如何统一管理证书材料、信任链、SNI 和 ALPN 协商
- 如何让上层协议在握手完成后读取协商结果并继续分流
- 如何区分安全关闭、强制关闭和 TLS 半关闭

## 2. 支持范围与能力清单

### 2.1 已支持的核心能力

- 运行时入口
  - SslDuplexer 负责 ByteBuf 到 TLS record 的双向转换
  - SslContext 挂在 ProtoContext 上，供后续 handler 查询
- 证书材料来源
  - PEM 证书链和 PEM 私钥
  - JKS KeyStore
  - 直接注入 KeyStore、KeyManagerFactory、TrustManagerFactory、TrustManager[]、SSLContext
  - 直接注入 X509Certificate[] 与 PrivateKey
- TLS 配置能力
  - 协议版本筛选，例如 TLSv1.2、TLSv1.3
  - 密码套件筛选
  - 服务端客户端认证级别配置
  - SNI host 配置
  - ALPN 协议列表、默认协议、选择器回调
- 运行时观测能力
  - 通过 SslContext.isReady() 观察握手是否完成
  - 通过 SslContext.getApplicationProtocol() 读取 ALPN 结果
  - 通过 SslContext.getSniHostName() 读取 SNI 信息
  - 通过网络事件接收 SslHandshakeEvent 与 SslCloseNotifyEvent
- 关闭与半关闭能力
  - channel.close() 会先发送 TLS close_notify，再关闭底层连接
  - channel.closeNow() 直接关闭连接，不发送 close_notify
  - SslContext.closeSSL() 可以发送 close_notify 并关闭 TLS 层，同时保持底层 TCP 连接继续存活
- HTTPS 集成能力
  - NetaHttpServer.startSSL() 基于 SslConfig 启动 HTTPS
  - HTTPS 场景可通过 ALPN 自动分流到 h2 或 http/1.1
  - 同一监听端口可以识别 TLS 请求和误打到 HTTPS 端口的明文 HTTP 请求

### 2.2 能力边界

这一套 SSL/TLS 组件负责：

- TLS 握手和数据收发
- 证书材料解析与 SSLEngine 配置
- ALPN 协商、SNI 配置、客户端认证设置
- 把握手完成和 close_notify 作为网络事件暴露给上层
- 为 HTTP/2 over TLS、HTTPS 等上层协议提供安全传输基础

这一套 SSL/TLS 组件不负责：

- 证书签发流程和 CA 生命周期管理
- 浏览器信任库导入和操作系统证书安装
- 路由、Servlet、业务级鉴权和应用层协议切换策略本身
- 通用 STARTTLS 编排框架

有两个边界需要特别看清：

- SslContext.openSSL() 和 SslContext.closeSSL() 是当前连接上 TLS 包装层的本地开关，不是完整的 STARTTLS 协议管理器
- SslConfig.provider 当前只有 JSSE 路径真正落地，其他 provider 会在 SslDuplexer 初始化时直接抛出 UnsupportedOperationException

### 2.3 当前明确限制

- 当前对外主入口是基于 JDK JSSE 的 SSLEngine 实现
- 示例、测试和 NetaHttpServer 的公开用法都集中在 TLS 场景
- 文档讨论的重点是 TCP 流式连接上的 TLS，不覆盖 QUIC 内部的专用 TLS 实现细节
- ALPN 依赖 JDK 的相关能力，JdkAlpnSslUtils 通过反射接入 JDK 9+ API，并兼容部分 8u 版本
- TLS 协议版本是否真正可用，最终还受当前 JDK 安全策略控制
  - 单测对 SSLv3、TLSv1、TLSv1.1 采用按环境跳过的方式处理
- close_notify 的接收和传播发生在 TLS 层正常收包路径上，直接强制断连不会产生这个事件

## 3. 组件分层

### 3.1 整体分层

```text
network ciphertext
  -> SslDuplexer
  -> plaintext ByteBuf

plaintext ByteBuf
  -> HTTP / WebSocket / 自定义协议 handler

ProtoContext
  -> SslContext
  -> handshake / ALPN / SNI / peer 信息

SoEvent
  -> SslHandshakeEvent / SslCloseNotifyEvent
```

如果把 HTTPS 场景也算进去，常见链路可以压缩成这样：

```text
TCP Socket
  -> SslDuplexer
  -> ALPN Router
  -> Http2FrameDuplexe + Http2ObjectDuplexe 或 HttpServerDuplexe
  -> HttpObject / FullHttpRequest
```

### 3.2 推荐入口

常规场景优先选择这些公开入口：

- 纯 TLS 字节加解密：SslDuplexer
- 需要读取当前连接 TLS 状态：ProtoContext.context(SslContext.class)
- HTTPS 服务：NetaHttpServer.ssl(sslConfig) + startSSL(...)
- 需要基于 ALPN 分流协议：在 SslDuplexer 后挂路由器，读取 SslContext.getApplicationProtocol()
- 需要同端口区分 TLS 和明文：在最外层先按首字节探测，再把 TLS 分支接到 SslDuplexer

只有在这些场景才建议直接依赖更底层部件：

- 需要覆盖 KeyStore、TrustManagerFactory、SSLContext 的构造方式
- 需要验证 close_notify、SNI、ALPN 的底层行为
- 需要把 TLS 放入自定义多协议路由链路

### 3.3 关键组件职责

| 组件 | 作用 | 典型使用场景 |
| --- | --- | --- |
| SslCertConfig | 证书、信任链、ALPN、SNI 的共享配置基类 | 统一描述 TLS 材料和协商策略 |
| SslConfig | 扩展 provider、clientAuth、ciphers、protocols | 面向 SSLEngine 的实际 TLS 配置 |
| SslDuplexer | ByteBuf 和 TLS record 互转 | TCP、HTTPS、自定义 TLS 通道入口 |
| SslContext | 每连接 TLS 状态视图 | 读取握手状态、ALPN、SNI、peer 信息 |
| JdkSslContext | JSSE 驱动的 SslContext 实现 | 当前默认运行时实现 |
| SslHandshakeEvent | 握手完成事件 | 握手后触发协议分流或业务初始化 |
| SslCloseNotifyEvent | 收到对端 close_notify 事件 | 区分 TLS 正常关闭和强制断开 |
| SslUtils | 证书、私钥、KeyStore 辅助工具 | 从 PEM/JKS 加载材料，拼装 KeyStore |

### 3.4 选型结论

- 常规 TLS 接入优先直接使用 SslDuplexer，而不是手写 SSLEngine 生命周期
- 业务层只需要 HTTPS 时，优先交给 NetaHttpServer.ssl(...) 和 startSSL(...) 统一装配
- 需要 h2 和 http/1.1 自动协商时，把 ALPN 配置写进 SslConfig，再在 SslDuplexer 后挂路由
- 需要区分安全关闭和强制关闭时，必须同时关注 close_notify 事件和 channel close 行为

## 4. 使用方式

### 4.1 最小 TLS Pipeline

适用范围：

- 原始 TCP 连接需要 TLS 加密
- 上层协议仍然按明文 ByteBuf 或自定义对象工作

推荐装配：

```java
SslConfig sslConfig = new SslConfig();
sslConfig.setAuthType(SslAuthKeyType.PEM);
sslConfig.setPemCertChain("ssl/ca/server.crt");
sslConfig.setPemPrivate("ssl/ca/server.pem");
sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });

ctx.addLast("ssl", new SslDuplexer(sslConfig));
ctx.addLast("app", appHandler);
```

关键点：

- SslDuplexer 应该靠近传输层放置
- 上层 handler 收到的是解密后的明文数据
- 客户端连接激活时会主动发送一个空 ByteBuf，用来立刻触发握手
- 服务端在收到第一批 TLS 字节后开始握手

### 4.2 证书材料的几种装配方式

适用范围：

- 需要按部署方式选择证书来源

常用方式：

- PEM

```java
SslConfig sslConfig = new SslConfig();
sslConfig.setAuthType(SslAuthKeyType.PEM);
sslConfig.setPemCertChain("ssl/ca/server.crt");
sslConfig.setPemPrivate("ssl/ca/server.pem");
sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
```

- JKS

```java
SslConfig sslConfig = new SslConfig();
sslConfig.setAuthType(SslAuthKeyType.JKS);
sslConfig.setJksResource("ssl/jks/keystore.jks");
sslConfig.setKeyPassword("123456");
sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
```

- 直接注入 KeyStore

```java
KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
SslUtils.loadKeyStore(keyStore, certChain, privateKey, new char[0]);

SslConfig sslConfig = new SslConfig();
sslConfig.setKeyStore(keyStore);
sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_3, SslProtocol.TLS_v1_2 });
```

关键点：

- 单测已经覆盖 PEM 和 JKS 两条主路径
- 如果应用已经自行构建了 SSLContext、KeyManagerFactory 或 TrustManagerFactory，可以直接注入，避免重复拼装
- 配置项越靠近 SSLContext 成品，Neta 参与的构建步骤越少

### 4.3 HTTPS 与 ALPN

适用范围：

- 需要同时支持 https 上的 h2 与 http/1.1
- 需要把 TLS 配置和 HTTP 服务器装配到一起

示例入口：

```java
SslConfig sslConfig = new SslConfig();
sslConfig.setKeyStore(keyStore);
sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_3, SslProtocol.TLS_v1_2 });

NetaHttpServer server = new NetaHttpServer();
server.ssl(sslConfig)
        .http2(true)
        .http3(true);

server.startSSL(8443);
```

关键点：

- NetaHttpServer.startSSL() 会先把 ALPN 协议列表写入 SslConfig
- 当前默认优先级是 h2，然后回落到 http/1.1
- HTTPS 入口会先探测首字节，TLS 流量进入 SslDuplexer，明文 HTTP 流量进入重定向分支

### 4.4 ALPN 路由与端口复用

适用范围：

- 一个端口下同时接收 TLS 和明文协议
- TLS 握手完成后还要继续按 ALPN 结果分流

推荐思路：

```java
[首字节路由] -> "tls" -> [SslDuplexer] -> [按 ALPN 路由]
             -> "plain" -> [明文协议 handler]
```

关键点：

- SslRoutingTest 展示了两级路由
  - 第一级按首字节区分 TLS 和 plain
  - 第二级在 TLS 分支内根据 SslContext.getApplicationProtocol() 选择 http/2 或 http/1.1
- 这条链路很适合做 port unification 或协议探测型网关

## 5. 内部机制

### 5.1 握手启动方式

- 客户端侧
  - SslDuplexer.onActive() 会发送一个空 ByteBuf
  - 这一步会立即触发首轮 wrap，握手在连接建立后马上开始
- 服务端侧
  - 握手在第一批 TLS 字节到达后触发

这个设计让客户端和服务端都不需要在业务层手动拉起握手。

### 5.2 JSSE 配置路径

当前默认运行时实现是 JdkSslContext，主要流程如下：

- 如果用户已经提供 SSLContext，直接复用
- 否则按 KeyStore、KeyManagerFactory、TrustManagerFactory 组装新的 SSLContext
- 根据 SslConfig.protocols 设置启用协议
- 根据 SslConfig.ciphers 和当前 JDK 支持列表筛选密码套件
- 服务端根据 SslClientAuth 配置 needClientAuth 或 wantClientAuth
- 客户端侧写入 SNI server_name
- 服务端侧根据配置生成 SNI matcher
- 通过 JdkAlpnSslUtils 写入应用层协议列表和选择器

### 5.3 ALPN 选择顺序

SslCertConfig 内置统一的 ALPN 选择顺序：

- appProtocolSelector
- defaultAppProtocol
- appProtocol 与 peerProtocols 的第一个交集
- null

这意味着：

- 业务层可以完全自定义选择结果
- 不写 selector 时，仍然可以通过默认协议和交集顺序得到稳定结果
- HTTPS 场景下，NetaHttpServer 已经提供了一套默认的 h2 优先策略

### 5.4 事件与状态暴露

- 握手成功后，SslHandle 会向 channel 触发 SslHandshakeEvent
- 收到对端 close_notify 后，SslHandle 会触发 SslCloseNotifyEvent
- 上层 handler 可以通过事件得知 TLS 生命周期节点
- 上层 handler 也可以随时从 ProtoContext 读取 SslContext，获取当前 isReady、ALPN、SNI、peer host、peer port

## 6. 数据流、事件流与关闭流

### 6.1 常规数据流

```text
network ciphertext
  -> SslDuplexer.onMessage(RCV)
  -> SSLEngine.unwrap(...)
  -> plaintext ByteBuf
  -> upper handlers

upper handlers
  -> plaintext ByteBuf
  -> SslDuplexer.onMessage(SND)
  -> SSLEngine.wrap(...)
  -> network ciphertext
```

### 6.2 握手完成后的事件流

```text
握手完成
  -> SslHandle 更新状态
  -> fireEvent(SslHandshakeEvent)
  -> 上层 handler 读取 SslContext
  -> 决定是否继续做 ALPN 路由或业务初始化
```

### 6.3 close、closeNow、closeSSL 的差异

这一节最容易误用。

- channel.close()
  - 会沿发送方向传播 SoCloseEvent
  - SslDuplexer 收到后调用 signalCloseNotify()
  - TLS 层先发送 close_notify
  - 之后底层 TCP 再关闭
- channel.closeNow()
  - 直接关闭底层连接
  - 不发送 close_notify
- SslContext.closeSSL()
  - 发送 close_notify
  - 当前 TLS 层进入关闭状态
  - 底层 TCP 连接可以继续保持

TcpSslCloseNotifyTest 已经验证了三件事：

- 安全关闭时，服务端会先收到 close_notify，再进入 channel onClose
- 强制关闭时，服务端 onClose 触发前 TLS 层仍然是 active
- 只关闭 SSL 层时，服务端会收到 SslCloseNotifyEvent，TLS 状态关闭，但 TCP 通道仍保持打开

## 7. 组件参考

### 7.1 SslConfig 常用配置项

| 配置项 | 作用 | 说明 |
| --- | --- | --- |
| provider | SSL 提供者 | 当前只落地 JSSE |
| clientAuth | 客户端证书策略 | NONE、OPTIONAL、REQUIRE |
| ciphers | 启用密码套件列表 | 使用 JSSE 标准名称 |
| protocols | 启用协议版本列表 | 例如 TLSv1.2、TLSv1.3 |
| keyStore | 预构建 KeyStore | 适合程序内组装证书 |
| keyManagerFactory | 预构建 KeyManagerFactory | 覆盖默认密钥管理构造 |
| trustManagers | 自定义信任管理器 | 适合证书钉扎或特殊校验 |
| trustManagerFactory | 预构建 TrustManagerFactory | 覆盖默认信任链构造 |
| sniHostName | SNI 主机名 | 客户端发送，服务端匹配 |
| appProtocol | ALPN 协议列表 | 例如 h2、http/1.1 |
| defaultAppProtocol | 默认 ALPN 协议 | 交集失败时作为回落 |
| appProtocolSelector | 自定义 ALPN 选择器 | 优先级最高 |

### 7.2 运行时状态接口

| 接口 | 作用 |
| --- | --- |
| SslContext.isReady() | 判断 TLS 是否已握手完成且当前启用 |
| SslContext.getApplicationProtocol() | 获取协商出的应用层协议 |
| SslContext.getSniHostName() | 获取当前连接的 SNI host |
| SslContext.getPeerHost() / getPeerPort() | 获取 peer 信息 |
| SslContext.closeSSL() | 关闭 TLS 层并发送 close_notify |
| SslContext.openSSL() | 重新打开本地 TLS 包装开关 |

### 7.3 相关资料

- [Oracle JSSE Reference Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
- [Oracle JSSE TLS Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html)

## 8. 使用注意事项

- SslDuplexer 要尽量靠近传输层放置，避免明文数据先流入错误的上层 decoder
- ALPN 路由必须建立在握手完成之后，读取协议前要先检查 SslContext.isReady()
- 旧版本 TLS 是否可用取决于当前 JDK 安全策略，测试通过不代表所有部署环境都开放这些协议
- 强制关闭不会发送 close_notify。依赖对端感知正常 TLS 关闭时，不要用 closeNow()
- 只关闭 TLS 层时，底层 TCP 仍然可能保持打开。业务层要自己定义后续的纯明文阶段是否允许存在
- 如果应用已经自己维护证书材料或信任链，优先直接注入成品对象，减少重复解析和配置分叉
- NetaHttpServer.startSSL() 依赖事先调用 ssl(sslConfig)。遗漏这一步会直接抛出异常
