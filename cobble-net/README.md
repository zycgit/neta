# Cobble Net

&emsp;&emsp; 基于 AIO 异步无阻塞网络通信框架

## 能力

- AIO 模型
- 异步模型
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 KeepAlive
- 支持 TCP
- 支持 监听器挂起（监听器暂时失效）
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

资料

- https://openjdk.org/projects/nio/resources/AsynchronousIo.html

TODO

1. soReadTimeoutMs 的作用和平时认知有一些偏差，需要进一步拟合这种偏差
2. NetChannel 和外围 API
3. 低延迟 ExecutorService

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#ex6
https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html
https://blog.yeskery.com/archives/SSL_engine_combined_with_NIO_to_realize_asynchronous_socket.html#menu_index_3
https://www.cnblogs.com/flydean/p/15419443.html#npn%E5%92%8Calpn
https://halfrost.com/https-extensions/#toc-0

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#RunningSSLEngineSimpleDemo




# Cobble ByteBuf

&emsp;&emsp; Cobble ByteBuf 是一款增强的 ByteBuf 框架，用于替代 JDK ByteBuf，相比较于 Netty ByteBuf 多了并发特性且更小更轻量。

## 功能和特性

- 支持 `动态容量` 和 `固定容量` 两种模式
- 支持 读/写模式一体化
- 支持 `ByteBuf` 零拷贝扩缩容
- 支持 `多线程` 场景下 `ByteBuf` 并发操作
- 支持 `Direct 内存`
- 支持 `大端/小端` 字节序
- 支持 `InputStream` 和 `OutputStream` 串联

## 同类对比

| 特性                                 | Cobble | Netty | JDK |
|------------------------------------|--------|-------|-----|
| 动态扩容                               | ✅      | ✅     | ✅   |
| 支持 Direct 内存                       | ✅      | ✅     | ✅   |
| 大、小端 字节序                           | ✅      | ✅     | ✅   |
| copy 方法                            | ✅      | ✅     | ✅   |
| duplicate 方法                       | ❌      | ✅     | ✅   |
| slice 方法                           | ❌      | ✅     | ✅   |
| readOnly 方法                        | ❌      | ✅     | ✅   |
| 零拷贝                                | 扩容时    | ✅     | ❌   |
| 读/写模式一体化                           | ✅      | ✅     | ❌   |
| ByteBuf 转为 输出流                     | ✅      | ✅     | ❌   |
| ByteBuf 转为 输入流                     | ✅      | ✅     | ❌   |
| 管道模式（`Input -> ByteBuf -> Output`） | ✅      | ❌     | ❌   |
| 并发互斥锁                              | ✅      | ❌     | ❌   |
| 多线程并发 读/写                          | ✅      | ❌     | ❌   |


## 引入依赖

```xml
<dependency>
    <groupId>net.hasor</groupId>
    <artifactId>cobble-bytebuf</artifactId>
    <version>4.6.1</version>
</dependency>
```

## 软件质量

- 行测试覆盖率：70%
- 有效代码行：1264
