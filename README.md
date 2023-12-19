# Neta

&emsp;&emsp; Hasor Neta is a network application framework that helps users to develop high performance and high scalability network applications easily.
It provides an abstract asynchronous duplex programming model and works on top of Java AIO.

## Pipeline Model

```text
待补充...
```

## Duplex Model

```text
         ┏━━━━━━━━━━━━━━━━━━━━━━━━━┓      ┏━━━━━━━━━━━━━━━━━━━━━━━━━┓
 DATA -> ┃ RCV_UP         RCV_DOWN ┃  ->  ┃ RCV_UP         RCV_DOWN ┃  -> ...
         ┃                         ┃      ┃                         ┃
         ┃      PipeLayer (1)      ┃      ┃      PipeLayer (2)      ┃
         ┃                         ┃      ┃                         ┃
  ... <- ┃ SND_DOWN         SND_UP ┃  <-  ┃ SND_DOWN         SND_UP ┃  <- DATA
         ┗━━━━━━━━━━━━━━━━━━━━━━━━━┛      ┗━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

## 能力

- TCP/IP AIO 双工异步模型
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 监听器挂起（监听器暂时失效）
- 支持 EmbeddedChannel
- 支持 Pipeline
- SSL 
  - 证书格式：JKS、PEM/CER
  - SSL 引擎：JDK、OpenSSL（待支持）
  - SSL 协议：NONE、SSLv2Hello、SSL_v2、SSL_v3、TLS_v1、TLS_v1_1、TLS_v1_2、TLS_v1_3
  - SSL 客户端验证：NONE、OPTIONAL、REQUIRE
- TLS 扩展
    - TLS NPN/ALPN，应用层协议协商
    - 重协商/安全重协商（OpenSSL RFC5764，renegotiation_info） 待支持

## 质量

- 有效代码行：3.6K
- 代码覆盖率：54%


高优先

1. SSL Close 的处理，防止尾部攻击
2. SSL Buffer 溢出问题
3. ByteBuf 清理和释放机制，需要部分重构将其池化。思路需要借鉴 Netty
4. IP 白名单机制
5. soReadTimeoutMs 的作用和平时认知有一些偏差，需要进一步拟合这种偏差
6. 场景测试覆盖率不足

次优先

1. 协议路由，用来支持 NPN/ALPN
2. 通过 SSL 参数支持 peerHost 能力
3. ByteBuf 大文件 或输入输出流的 传输
4. PipeRoute
5. 流量控制

## 参考资料

- https://openjdk.org/projects/nio/resources/AsynchronousIo.html
- https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html
- https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html
- https://datatracker.ietf.org/doc/html/rfc6066
- https://halfrost.com/https-extensions/#toc-0