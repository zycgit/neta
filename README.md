# Neta

&emsp;&emsp; Neta is a network application framework that helps users to develop high performance and high scalability network applications easily.
It provides an abstract asynchronous duplex programming model and works on top of Java AIO.

## Programming Model

```text
            Decoder/Encoder            Event and API
      ┏━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮
Net → ┃ ╭┄┄┄┄╮ ╭┄┄┄┄╮ ╭┄┄┄┄╮     ┃ → ┃ RCV: subscribe ┃ → ┆             ┆
      ┃ ┆ L1 ┆ ┆ L2 ┆ ┆ L3 ┆ ... ┃   ┠┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┨   ┆ Application ┆
Net ← ┃ ╰┄┄┄┄╯ ╰┄┄┄┄╯ ╰┄┄┄┄╯     ┃ ← ┃ SND: sendData  ┃ ← ┆             ┆
      ┗━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
```

## Duplex Model

```text
       PipeLayer 0     PipeLayer 1     PipeLayer 2   
      ┏━━━━━━━━━━━┓   ┏━━━━━━━━━━━┓   ┏━━━━━━━━━━━┓  
      ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃  
Net → ┃  ┆ RCV ┆  ┃ → ┃  ┆ RCV ┆  ┃ → ┃  ┆ RCV ┆  ┃ →  ╭┄┄┄┄┄╮
      ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃    ┆     ┆
      ┃     ↓     ┃   ┃     ↓     ┃   ┃     ↓     ┃    ┆ ... ┆
      ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃   ┃  ╭┄┄┄┄┄╮  ┃    ┆     ┆
Net ← ┃  ┆ SND ┆  ┃ ← ┃  ┆ SND ┆  ┃ ← ┃  ┆ SND ┆  ┃ ←  ╰┄┄┄┄┄╯
      ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃   ┃  ╰┄┄┄┄┄╯  ┃  
      ┗━━━━━━━━━━━┛   ┗━━━━━━━━━━━┛   ┗━━━━━━━━━━━┛
```

```text
      ProtoDuplexer                  ProtoDuplexer
 ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮          ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮
 ┆ RCV_UP     RCV_DOWN ┆          ┆ rcv ProtoHandler ┆
 ┆                     ┆    OR    ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤
 ┆ SND_DOWN     SND_UP ┆          ┆ SND ProtoHandler ┆
 ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯          ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯
```

## Features

### Design
- Unified API for developing UDP/TCP programs.
- Adapt to high-concurrency scenarios using the SEDA thread model.

### Security
- Support SSL/TLS/DTLS (include SSLv3、TLSv1、TLSv1.1、TLSv1.2、TLSv1.3、DTLSv1.0、DTLSv1.2)

### Functions
- 支持 NPN/ALPN 扩展应用层协议协商
- TLS/SSL 客户端验证：NONE、OPTIONAL、REQUIRE
- 支持 Pipeline 多种流转控制方式
- 支持 虚拟通道（可模拟 端口监听、广播、、丢包、粘包等情况）
- 支持 ReadSocketTimeout、WriteSocketTimeout
- 支持 监听器挂起，不在接受新的连接直到恢复
- 支持 安全关闭
    - 证书格式：JKS、PEM/CER

## 质量

neta-core
- 有效代码行：4.1K
- 代码覆盖率：76%

## Hello Word

```xml
<dependency>
    <groupId>net.hasor</groupId>
    <artifactId>neta-all</artifactId>
    <version>1.0.6</version>
</dependency>
```

```java
//       ┏━ nextDecoder(..) ━━┓   ┏━ nextDuplex(..) ━━┓
//       ┃ ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮ ┃   ┃ ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮ ┃
// Net → ┃ ┆ LineBasedFrame ┆ ┃ → ┃ ┆ StringDecoder ┆ ┃ → ╭┄┄┄┄┄┄┄┄┄┄┄╮
//       ┃ ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯ ┃   ┃ ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤ ┃   ┆ echo data ┆
// Net ← ┃                    ┃ ← ┃ ┆ StringEncoder ┆ ┃ ← ╰┄┄┄┄┄┄┄┄┄┄┄╯
//       ┃                    ┃   ┃ ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯ ┃
//       ┗━━━━━━━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━━━━━━┛

PipeInitializer initializer = ctx -> {
    return PipeHelper.standard()
            //split according to \r\n, max line is 4K
            .nextDecoder("max length", new LineBasedFrameHandler(4096, false))
            // encoder/decoder string
            .nextDuplex("string", new StringPipeLayer())
            // build pipline
            .build();
};

// create neta
NetManager neta = new NetManager(new SoConfig());

// echo any message to client                                                      
neta.subscribe(PlayLoad::isInbound, playload -> {
NetChannel channel = (NetChannel) playload.getSource();
    String str = (String) playload.getData();
    channel.sendData("echo " + str + "\n");
});

// telnet start
neta.bind(new InetSocketAddress(5567), initializer, SoConfig.TCP());
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
