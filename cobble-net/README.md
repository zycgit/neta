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