# Neta Copilot 指南（中文）

## 语言要求
- 请在交流中使用中文。
- 生成代码、注释、Javadoc、测试名时，优先沿用目标文件已有语言和风格；不要在同一文件里混用中英文术语。

## 项目概览
- `neta/` 是基于 Java AIO 的异步网络框架，核心是双向管线模型，不是 Servlet、Spring MVC 或 Netty API 的简单映射。
- 主要模块：
  - `neta-core/`：通道、事件、管线、分区、线程模型等基础设施。
  - `neta-codec-http/`：HTTP/1.1、WebSocket、HTTP/2、CORS 等协议编解码与握手逻辑。
  - `neta-codec-net/`、`neta-codec-mail/`：其他协议编解码支持。
  - `neta-all/`：聚合依赖。
  - `neta-example/`、`performance/`、`neta-lab/`：示例、性能测试、实验代码，仅在对应 profile 下参与构建。

## 架构原则
- Neta 的边界划分是：连接负责生命周期，协议栈负责编解码与协议控制，业务通过 `subscribe(...)` 和 `sendData(...)` 参与收发。改业务逻辑时不要直接侵入底层协议栈，改协议行为时也不要把状态散落到业务层。
- 统一模型优先于特例实现：TCP、UDP、QUIC、SCTP、虚拟通道共享同一套 API 和大体相同的装配思路。新增能力时优先复用既有抽象，而不是为某个协议单独造一套完全不同的运行模型。
- ProtoStack 遵循静态结构原则：结构在初始化期一次性装配完成，运行期只允许切状态、切路由、切分区，不应动态增删 handler 来表达协议阶段变化。
- 连接级协议问题优先在 pipeline 内建模，业务级问题优先在 pipeline 边界之后建模。一个能力如果仍然依赖方向、连接状态、握手阶段或背压语义，它通常属于 Handler/Pipeline，而不是 subscribe 之后的业务服务层。

## 主要开发入口
- 网络入口先看 `NetManager`：服务端从 `bind(...)` 进入，客户端从 `connect(...)` 或对应测试装配进入；业务消费主要看 `subscribe(...)`，下行发送看 `NetChannel.sendData(...)`。
- 协议栈装配优先从 `PipeInitializer`、`ProtoInitializer`、`ProtoHelper.standard()`、`ProtoHelper.typed(...)`、`ProtoHelper.typedRoutingAsStatic(...)`、`nextPartition(...)` 这些入口理解，不要先从某个零散 handler 逆推全局。
- Pipeline 节点分三类理解：
  - 单工器：`ProtoHandler`，适合单方向解码、编码、聚合。
  - 双工器：`ProtoDuplexer`，适合同时处理 RCV/SND 或需要握手、升级、透明传输这类双向协作状态。
  - 控制节点：路由、分区、聚合、升级桥，负责切分和协调，而不是承载业务逻辑。
- 协议维护时按模块入口排查：
  - HTTP/1.x 先看 `HttpServerDuplexe`、`HttpClientDuplexe`、`HttpRequestAggregator`、`HttpResponseAggregator`、`HttpThroughEvent`。
  - WebSocket 先看 `WebSocketServerHandshakeDuplexer`、`WebSocketClientHandshakeDuplexer`、`WebSocketFrameDuplexer`、`WebSocketExtensionDuplexer`、`WebSocketMessageDuplexer`。
  - HTTP/2 先看 `Http2FrameDuplexe`、`Http2ObjectDuplexe`、`H2cUpgradeServerDuplexe`、`Http2Context`。

## 工作机理
- Neta 完全基于异步事件驱动：Java AIO 线程先把 I/O 事件放入队列，再由 Worker 线程消费并推进 ProtoStack。同一个 Channel 的不同 I/O 事件可能由不同线程执行，因此不要把“同一连接固定绑定单线程”当成默认前提。
- Worker 线程负责协议处理，不适合长期阻塞。耗时业务、外部 RPC、数据库调用应尽快移出 Worker，交给独立线程或业务执行器处理。
- 在 Handler 内发下行数据时，优先走 `ProtoSndQueue` 或 `ProtoContext`，不要随手调用 `NetChannel.sendData(...)` 重新把消息从整条栈顶走一遍。后者会放大协议层耦合，也更容易破坏本层本应只处理的语义边界。
- 三大流必须分开思考：
  - 数据流走 `onMessage(...)`，负责字节、帧、协议对象和业务对象的转换。
  - 事件流走 `onEvent(...)`，负责握手完成、关闭、模式切换、控制信号。
  - 异常流走 `onError(...)`，负责失败传播、资源清理、协议错误转换和恢复。
- 协议层之间优先通过显式事件协作，不要靠共享布尔值或隐式上下文猜测状态变化。例如 transparent mode、握手完成、关闭前收尾都应通过事件表达。

## Pipeline 设计判断
- 先处理边界，再处理协议，再进入业务。常见顺序是：分帧/粘拆包 -> 编解码 -> 聚合/升级/控制 -> 业务对象消费。
- 路由管线用于“当前连接此刻走哪条预定义分支”，分区管线用于“同一连接内并存多条状态隔离的子链”。不要用路由去模拟多路并发，也不要用分区去表达一次性的协议升级。
- 静态路由适合一次握手后长期固定的协议分支；动态路由只适合每条消息都可能去不同分支的场景。
- HTTP/2、HTTP/3 这类多路复用协议优先考虑分区模型；HTTP/1.x -> WebSocket、HTTP/1.1 -> h2c 这类升级切换优先考虑静态路由加显式切换控制。
- 需要“暂存并稍后回放”的数据时，优先使用 queue view / sub queue / seed handoff 这类框架内语义，不要自己在 handler 私有字段里偷偷攒一个旁路缓存，除非该状态本身就是该 handler 的协议内状态。

## 核心认知
- 先理解 `PipeInitializer`、`ProtoDuplexer`、`ProtoHandler`、`ProtoStatus` 的协作关系，再修改具体协议层。
- 优先沿用现有装配模式，例如 `ProtoHelper.standard()`、`ctx.addLastDecoder(...)`、`ctx.addLast(...)`、`nextPartition(...)`，不要平行造一套新的 pipeline DSL。
- `ProtoStatus.Next / Stop / Retry` 直接影响数据是否继续向下游传播；修改 handler 时要先确认是否改变了既有流转语义。
- 分区能力优先走 `ProtoPartitionBuilder`、`ProtoPartitionPolicy`、`ProtoPartitionControl`，不要退回到运行时从上下文里手工摸控制句柄。
- `Again`、`Back`、`Restart`、`Skip`、`Interrupt` 这类控制语义表达的是连接级协议动作，不是普通循环控制。若要改这些返回值，必须先确认对应阶段的重入、回放和异常行为。
- 输入一旦已经变成稳定业务对象，通常应让它尽快离开 pipeline，进入 `subscribe(...)` 或业务服务层；不要把本该属于业务编排的逻辑继续留在协议栈深处。

## 编码约束
- Java 8 兼容：禁止使用 `var`、Record、Switch 表达式、`List.of`、`Map.of`、`Optional.orElseThrow(Supplier)` 之外的高版本 API。
- 优先使用 `net.hasor.cobble.*` 工具类，避免为字符串、集合、反射、IO 之类常见场景额外引入第三方依赖。
- 公共 API、协议对象、扩展点优先补 Javadoc；内部测试辅助方法可保持简洁。
- 保持现有命名语义：如果字段持有的是 queue view、handle、reference，名字要体现 `View`、`Ref`、`Handle`，不要伪装成真实缓存数据。
- 记录型对象、消息对象、事件对象不要偷偷修改所有权语义；构造器和 getter 应保持字面语义，避免隐藏 retain、copy、clone 一类副作用。

## ByteBuf 与事件所有权
- 先看对象是否还在 `ProtoQueue` 里，再判断是否该由当前层释放。对象仍在队列里时，所有权属于队列；`take` 后所有权才转给调用方；`peek` 不转移；`skip` 表示由队列丢弃并释放。
- `ByteBuf` 所有权必须显式转移。谁创建 websocket/http 事件对象，谁负责在必要时先 `retain()` 或 `copy()`。
- 不要在 `AbstractWebSocketMessage`、Ping/Pong 事件、HTTP/2 Ping/Pong 事件等值对象构造器里偷偷增加引用计数。
- `content()` 这类访问器返回的是对象当前持有的数据本体；如果调用方要跨当前生命周期保存，必须自行 `retain()` 或 `copy()`。
- 修改编解码或 duplexer 时，先确认上游对象是否会在本轮处理后释放，再决定是否需要显式保活。
- 透传原对象时不要额外 release；消费原对象并产出替代对象时，当前层要接管原对象释放责任；如果原对象和派生对象要同时存活，必须显式 `retain()` 并安排对等 `release()`。
- 不要把 `peek + skip` 当成所有权转移手段。那是“观察后丢弃”，不是“观察后交给下游继续使用”。

## 队列与背压
- `ProtoRcvQueue` / `ProtoSndQueue` 是主容器，`ProtoRcvQueueView` / `ProtoSndQueueView` 是具名局部视图；它们共享同一份容量预算，不是独立容量空间。
- 把数据移到 view/sub queue 的目的，是显式表达局部调度、分支暂存、阶段性回放，不是绕开背压限制。
- 如果对象最初来自上游 `ProtoRcvQueue`，而当前层只是想跨多次 `onMessage(...)` 暂存、等待拼包、等待补齐协议边界，那么优先使用具名 `ProtoRcvQueueView` 配合 `drainToQueue(...)`、`queueView(...)`、`discard()`、`returnToHead()`、`returnToTail()` 等语义，不要通过 `retain()` 后塞进新的私有 `ProtoQueue<>(-1)` 来旁路暂存。
- 如果改动了 queue view、分区回放、升级缓存、局部子链暂存逻辑，要先验证共享容量和 pending flush 语义是否仍成立。

## WebSocket / HTTP 实现约束
- WebSocket 握手的 `Host`、`Origin` 必须来自真实目标信息：
  - 优先使用绝对 websocket URI。
  - 如果只传相对路径，必须显式提供 `Host` 和 `Origin`。
  - 不要生成 `localhost`、`example.com` 之类伪造默认值来蒙混通过。
- 与握手结果相关的上下文信息应保存在 `WebSocketContext` 中，包括 `requestPath`、`requestHost`、`requestOrigin`。
- HTTP codec 的日志优先集中在入口或调度边界，确保下游抛错时仍有前置日志；不要为了“防御性”日志到处加无意义的空值分支。
- HTTP/2 分区推荐沿用现有组合：分区选择器 + `Http2PartitionPolicy` + 每个流分区显式挂载生命周期 duplexer。
- HTTP/1.x 开发默认优先从 duplexe 入口开始，不要先手拼 request/response encoder 和 decoder；只有做底层测试、桥接或精细化调试时才直挂底层组件。
- WebSocket 常规稳定链路是：HTTP codec -> Handshake -> Frame -> Extension（可选）-> Message。扩展运行时必须放在 Frame 和 Message 之间。
- WebSocket 的大对象传输问题优先在应用层 chunk 协议解决；入站聚合和出站自动分帧解决的是协议层边界，不是完整的大文件传输方案。
- WebSocket 的接收侧半包解码、分片聚合这类 staging，如果输入仍来自上游接收队列，应继续留在 `ProtoRcvQueueView` 体系内维护已读进度和阶段性暂存，不要复制成脱离共享背压的私有 side queue。
- HTTP/2 常规稳定链路是：`Http2FrameDuplexe -> Http2ObjectDuplexe -> （可选）Aggregator`。需要 h2c upgrade 时优先用 `H2cUpgradeServerDuplexe`，不要自行拼接 HTTP/1.1 和 HTTP/2 状态机。

## 构建与测试
- 在 `neta/` 根目录使用 `./mvnw`，不要依赖系统 Maven。
- 常用命令：
  - 全量构建：`./mvnw clean install`
  - 核心模块测试：`./mvnw -pl neta-core test`
  - HTTP 模块测试：`./mvnw -pl neta-codec-http -am test`
- 当 `neta-codec-http` 依赖 `neta-core` 的最新改动时，务必带 `-am` 一起构建上游模块；只编译单模块很容易拿到过期的本地依赖。
- 使用 `-Dtest=...` 只跑部分测试时，若上游模块没有匹配测试，会被 surefire 视为失败；需要同时加 `-DfailIfNoTests=false`。
- 父 POM 里配置了 `testFailureIgnore=true`，因此 Maven 结束不等于所有测试都真的通过。需要查看 `target/surefire-reports/` 确认真实结果。

## 测试约定
- 协议测试优先覆盖真实 pipeline 行为，不要靠 mock 掉核心 duplexer 来“证明”协议正确。
- 对 WebSocket、HTTP/2 这类分阶段协议，断言应围绕握手事件、状态切换、队列清理、普通流量共存等可观察行为，而不是依赖实现偶然时序。
- 新增或修改 `WebSocketContext`、分区策略、queue view 等公共接口时，要同步检查测试桩和 mock 实现是否需要补齐新方法。
- 当问题涉及结构选择时，优先补“为什么是路由/分区/事件/聚合”的测试，而不是只验证某个具体类的方法返回值。Neta 的很多回归风险来自流转语义而不是单个对象字段。

## 文档与示例
- `neta-doc/` 是文档站点，纯协议或核心代码开发时通常不需要动它。
- 修改 README、示例代码、文档片段时，示例要优先使用当前仍被推荐的 API 组合，不要把历史兼容写法当成首选用法。