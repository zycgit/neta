# Cobble Net

&emsp;&emsp; 基于 AIO 的网络通信框架

## 能力

- AIO 模型
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

资料

- https://openjdk.org/projects/nio/resources/AsynchronousIo.html

TODO

- ReadSocketTimeout 的感觉和实际预期还是有一些偏差，即便没有传入数据 ReadSocketTimeout 仍然会触发超时
- NetChannel 和外围 API
- 低延迟 ExecutorService
- SSL
    - SSL 缓冲区溢出,需要额外考虑，有些SSL 实现并没有使用固定大小

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#ex6
https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/tls.html
https://blog.yeskery.com/archives/SSL_engine_combined_with_NIO_to_realize_asynchronous_socket.html#menu_index_3
https://www.cnblogs.com/flydean/p/15419443.html#npn%E5%92%8Calpn
https://halfrost.com/https-extensions/#toc-0

https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#RunningSSLEngineSimpleDemo