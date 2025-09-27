# Neta

&emsp;&emsp; Neta is a network application framework that helps users to develop high performance and high scalability network applications easily.
It provides an abstract asynchronous duplex programming model and works on top of Java AIO.

## Pipeline Model

```text
       PipeLayer 0     PipeLayer 1     PipeLayer 2
      ┏━━━━━━━━━━━┓   ┏━━━━━━━━━━━┓   ┏━━━━━━━━━━━┓
      ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃
Net → ┃  ┆ RCV ┆  ┃ → ┃  ┆ RCV ┆  ┃ → ┃  ┆ RCV ┆  ┃ → ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮
      ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃   ┆             ┆
      ┃     ↓     ┃   ┃     ↓     ┃   ┃     ↓     ┃   ┆ Application ┆
      ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃   ┆             ┆
Net ← ┃  ┆ SND ┆  ┃ ← ┃  ┆ SND ┆  ┃ ← ┃  ┆ SND ┆  ┃ ← ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
      ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃
      ┗━━━━━━━━━━━┛   ┗━━━━━━━━━━━┛   ┗━━━━━━━━━━━┛
```

## Duplex Model

```text
           PipeLayer(0)              PipeLayer (1)
      ┏━━━━━━━━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━━━━━━━━┓
Net → ┃ RCV_UP     RCV_DOWN ┃ → ┃ RCV_UP     RCV_DOWN ┃ → ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮
      ┃                     ┃   ┃                     ┃   ┆ Application ┆
Net ← ┃ SND_DOWN     SND_UP ┃ ← ┃ SND_DOWN     SND_UP ┃ ← ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
      ┗━━━━━━━━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━━━━━━━━┛
```

## 能力

- 支持 在统一 API 风格下开发 TCP/UDP 协议应用程序
- 支持 SSL/TLS/DTLS （SSLv3、TLSv1、TLSv1.1、TLSv1.2、TLSv1.3、DTLSv1.0、DTLSv1.2）
- 支持 NPN/ALPN，SSL/TLS 扩展应用层协议协商
- 
- 支持 Pipeline 多种流转控制方式
- 支持 单向 Socket 通信，支持在 TCP 模式下关闭输入通道。
- 支持 EmbeddedChannel 协议开发更加容易
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 监听器挂起，不在接受新的连接直到恢复
- 支持 安全关闭
  - 证书格式：JKS、PEM/CER
  - TLS/SSL 客户端验证：NONE、OPTIONAL、REQUIRE
  - 

## 质量

neta-core
- 有效代码行：4.1K
- 代码覆盖率：76%

## Hello Word

```xml
<dependency>
    <groupId>net.hasor</groupId>
    <artifactId>neta-all</artifactId>
    <version>1.0.4</version>
</dependency>
```

```java
//       ┏━━ nextToDecoder(..) ━┓   ┏━━ StringPipeLayer ━━┓
//       ┃  ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮  ┃
// Net → ┃  ┆ LineBasedFrame ┆  ┃ → ┃  ┆ StringDecoder ┆  ┃ → ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮
//       ┃  ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯  ┃   ┆             ┆
//       ┃                      ┃   ┃                     ┃   ┆ echo + data ┆
//       ┃                      ┃   ┃  ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮  ┃   ┆             ┆
// Net ← ┃                      ┃ ← ┃  ┆ StringEncoder ┆  ┃ ← ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
//       ┃                      ┃   ┃  ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯  ┃
//       ┗━━━━━━━━━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━━━━━━━━┛

PipeInitializer initializer = ctx -> {
    return PipeHelper.builder()
      //split according to \r\n, max line is 4K
      .nextDecoder("max length", new LineBasedFrameHandler(4096, false))
      // encoder/decoder string
      .nextDuplex("string", new StringPipeLayer())
      // echo any message to client
      .nextDecoder("echo", new TelnetEchoPipeDuplex())
      // build pipline
      .build();
};

NetaSocket socket = new NetaSocket(new SoConfig());
socket.listen("127.0.0.1", 5567, initializer);
```

use the telnet command as follows:

```text
telnet 127.0.0.1 5567
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
Hello Word.
echo Hello Word.
```


## 开发和构建

release versions and prepare next SNAPSHOT version
- `mvn release:clean release:prepare -Dmaven.test.skip -Prelease -Pdev`

push to center maven repository (version tag must be RELEASE)
- `mvn clean package install deploy -Prelease`
