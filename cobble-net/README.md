# Cobble Net

&emsp;&emsp; 基于 AIO 的网络通信框架

## 能力

- AIO 模型
- 支持 ReadSocketTimeout/WriteSocketTimeout
- 支持 KeepAlive
- 支持 TCP
- 接收部分需要考虑 连接建立后立刻关闭的情况

资料

- https://openjdk.org/projects/nio/resources/AsynchronousIo.html

TODO

- NetChannel 和外围 API
- 低延迟 ExecutorService
- SSL
    - SSL 缓冲区溢出,需要额外考虑，有些SSL 实现并没有使用固定大小
- TcpClient/TcpServer
    - Close 的时候需要 close 里面的 canal


https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#ex6
https://blog.yeskery.com/archives/SSL_engine_combined_with_NIO_to_realize_asynchronous_socket.html#menu_index_3