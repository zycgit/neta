# Neta

&emsp;&emsp; Hasor Neta is a network application framework that helps users to develop high performance and high scalability network applications easily.
It provides an abstract asynchronous duplex programming model and works on top of Java AIO.

## Pipeline Model

```text
待补充...
```

## Duplex Model

```text
               PipeLayer(0)                    PipeLayer (1)
        ┏━━━━━━━━━━━━━━━━━━━━━━━━┓     ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
        ┃                        ┃     ┃                        ┃
        ┃             ╭┄┄┄┄┄┄┄┄┄┄┸┄┄┄┄┄┸┄┄┄┄┄┄┄┄┄┄╮             ┃
DATA -> ┃ RCV_UP      ┆ RCV_DOWN   <=>   RCV_UP   ┆    RCV_DOWN ┃  -> ...
        ┃             ┆                           ┆             ┃
...  <- ┃ SND_DOWN    ┆ SND_UP     <=>   SND_DOWN ┆      SND_UP ┃  <- DATA
        ┃             ╰┄┄┄┄┄┄┄┄┄┄┰┄┄┄┄┄┰┄┄┄┄┄┄┄┄┄┄╯             ┃
        ┃                        ┃     ┃                        ┃
        ┗━━━━━━━━━━━━━━━━━━━━━━━━┛     ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
```

## 能力

- 支持 Pipeline
  - 提供 Next、Retry、Again、Restart、Exit、Interrupt 共 6 种流转控制方式
- 支持 单向 Socket 通信
  - shutdownOutput 单方面永久关闭输出通道
  - shutdownInput 单方面永久关闭输入通道
- 支持 EmbeddedChannel 协议开发更加容易
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 监听器挂起，不在接受新的连接直到恢复
- 支持 安全关闭
- TLS/SSL
  - 证书格式：JKS、PEM/CER
  - TLS/SSL 引擎：JDK、OpenSSL（计划中）
  - TLS/SSL 协议：NONE、SSLv2Hello、SSL_v2、SSL_v3、TLS_v1、TLS_v1_1、TLS_v1_2、TLS_v1_3
  - TLS/SSL 客户端验证：NONE、OPTIONAL、REQUIRE
- TLS/SSL 扩展
  - NPN/ALPN，应用层协议协商
 
## 质量

neta-core
- 有效代码行：3.9K
- 代码覆盖率：74%
