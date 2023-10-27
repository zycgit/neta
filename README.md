# Neta

&emsp;&emsp; Hasor Neta is a network application framework that helps users to develop high performance and high scalability network applications easily.
It provides an abstract asynchronous duplex programming model and works on top of Java AIO.

## Pipeline Model

```text
         /-------------------------\      /-------------------------\
 DATA -> | RCV_UP         RCV_DOWN |  ->  | RCV_UP         RCV_DOWN |  -> ...
         |                         |      |                         |
         |      PipeLayer (1)      |      |      PipeLayer (2)      |
         |                         |      |                         |
  ... <- | SND_DOWN         SND_UP |  <-  | SND_DOWN         SND_UP |  <- DATA
         \-------------------------/      \-------------------------/
```

## 能力

- AIO 模型
- 异步模型
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 KeepAlive
- 支持 TCP
- 支持 监听器挂起（监听器暂时失效）
- 支持 EmbeddedChannel 方便开发协议栈
- SSL 证书格式
    - JKS 格式
    - PEM/CER 格式
- SSL 引擎
    - JDK
    - OpenSSL（待支持）
- SSL 协议
    - NONE、SSLv2Hello、SSL_v2、SSL_v3、TLS_v1、TLS_v1_1、TLS_v1_2、TLS_v1_3
- SSL 客户端验证
    - NONE
    - OPTIONAL
    - REQUIRE
- TLS 扩展
    - TLS NPN/ALPN，应用层协议协商
- Pipeline
    - 反压机制
    - 双工模式

## 质量

- 代码覆盖率：50%

资料

- https://openjdk.org/projects/nio/resources/AsynchronousIo.html

TODO

高优先

1. 低延迟 ExecutorService
2. ByteBuf 清理和释放机制
3. soReadTimeoutMs 的作用和平时认知有一些偏差，需要进一步拟合这种偏差
4. SSL Close 的处理，防止尾部攻击
5. SSL Buffer 溢出问题
6. 场景测试覆盖率不足
7. IP 白名单机制

次优先

1. 协议路由，用来支持 NPN/ALPN
2. 通过 SSL 参数支持 peerHost 能力
3. ByteBuf 大文件 或输入输出流的 传输
4. PipeRoute
5. 流量控制
6.

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#ex6
https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html
https://blog.yeskery.com/archives/SSL_engine_combined_with_NIO_to_realize_asynchronous_socket.html#menu_index_3
https://www.cnblogs.com/flydean/p/15419443.html#npn%E5%92%8Calpn
https://halfrost.com/https-extensions/#toc-0

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#RunningSSLEngineSimpleDemo

## 功能和特性

- 支持 `动态容量` 和 `固定容量` 两种模式
- 支持 读/写模式一体化
- 支持 `ByteBuf` 零拷贝扩缩容
- 支持 `Direct 内存`
- 支持 `大端/小端` 字节序
- 支持 `InputStream` 和 `OutputStream` 串联

## 同类对比

| 特性                                 | Netty | Cobble | JDK |
|------------------------------------|-------|--------|-----|
| 动态扩容                               | ✅     | ✅      | ✅   |
| 支持 Direct 内存                       | ✅     | ✅      | ✅   |
| 大、小端 字节序                           | ✅     | ✅      | ✅   |
| copy 方法                            | ✅     | ✅      | ✅   |
| duplicate 方法                       | ✅     | ❌      | ✅   |
| slice 方法                           | ✅     | ❌      | ✅   |
| readOnly 方法                        | ✅     | ❌      | ✅   |
| 零拷贝                                | ✅     | 扩容时    | ❌   |
| 读/写模式一体化                           | ✅     | ✅      | ❌   |
| ByteBuf 转为 输出流                     | ✅     | ✅      | ❌   |
| ByteBuf 转为 输入流                     | ✅     | ✅      | ❌   |
