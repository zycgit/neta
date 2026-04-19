# nhttp Server 架构设计文档

## 1. 现状分析与问题

### 1.1 当前架构

当前实现围绕一个核心类 `NetaHttpServer`（约 1230 行），它承担了过多职责：服务器配置、协议管道构建、HTTP 请求分发、WebSocket 生命周期管理、CORS 处理、HTTPS 重定向等。虽然功能基本完整，但存在以下关键问题：

**P0 - 阻塞 IO 线程**：Servlet 的执行直接发生在 neta 的 IO 回调线程中（`HttpDispatchHandler.onMessage`）。如果 Servlet 执行了任何阻塞操作（数据库查询、RPC 调用、文件 IO），将直接阻塞底层事件循环，导致同一 EventLoop 上的所有连接停顿。

**P0 - 全量请求缓冲**：`HttpDispatchHandler` 使用 `ProtoHandler<FullHttpRequest, Object>` 接收聚合后的完整请求。这要求 neta-codec 的 HTTP 聚合器将整个请求体缓冲到内存，大文件上传会直接撑爆堆内存。

**P0 - 无请求生命周期管理**：没有请求超时机制、没有并发请求计数、没有请求追踪。慢请求或死锁请求会永远占用资源。

**P1 - 无连接管理**：没有活跃连接追踪、没有连接数上限、没有优雅关闭（graceful shutdown）。`stop()` 方法直接关闭监听器，不等待正在处理的请求完成。

**P1 - 响应全量缓冲**：`DefaultServletResponse` 使用 `ByteArrayOutputStream` 缓冲整个响应体。对于大文件下载或 SSE（Server-Sent Events）场景，这既浪费内存又无法实现流式传输。

**P2 - 无异步请求支持**：Servlet 无法将请求挂起后异步处理，不适合长轮询、SSE 等场景。

**P2 - 无错误页定制**：错误页面是硬编码的 HTML 字符串，无法自定义。

**P2 - 无生命周期事件**：缺乏服务器启动/停止、请求开始/结束等事件通知机制。

### 1.2 已有的良好基础（保留部分）

以下**公共接口**设计合理，作为架构的稳定 API 层保留：

- `HttpServlet` — Servlet 抽象基类（service/doGet/doPost 等）
- `Filter` / `FilterChain` — 过滤器接口
- `ServletRequest` / `ServletResponse` — 请求/响应接口（将在此基础上扩展新方法）
- `ServletContext` — 应用上下文接口
- `HttpSession` / `SessionManager` — Session 接口
- `WebSocketHandler` / `WebSocketSession` — WebSocket 接口

以下 **internal 实现**将**全部删除并重写**：

- `DefaultServletRequest` — 重新设计请求实现，统一支持同步/异步
- `DefaultServletResponse` — 全新设计，支持缓冲和流式两种模式
- `DefaultFilterChain` — 重写以支持异步链式调用
- `DefaultServletContext` — 扩展为容器级上下文
- `DefaultSessionManager` / `DefaultHttpSession` — 可保留核心逻辑但重构整合
- `DefaultWebSocketSession` — 重构，更清晰的生命周期管理
- `ServletDispatcher` — 完全重写，增强路由能力和动态注册支持
- `NetaHttpServer` 中所有内部类 — 提取并重新设计

### 1.3 底层 codec 能力（充分利用）

底层 `neta-codec-http` 已完整支持：
- HTTP/1.0、HTTP/1.1（RFC 7230-7235）
- HTTP/2（RFC 9113）— HPACK、流管理、GOAWAY
- HTTP/3（RFC 9114）— QPACK、QUIC（**预留扩展点，暂不实现**）
- WebSocket（RFC 6455）— 扩展（deflate、per-message-deflate）
- Cookie（RFC 6265）、Multipart（RFC 7578）、CORS（RFC 6454）
- 完整的异常体系（HttpSizeLimitException 等）

### 1.4 neta-core 需同步增强

**`SoContextService#acceptChannel` 扩展**：当前实现仅检查 `closeStatus`，不做连接数限制。需要同步增强（详见第 5 节）：
- `NetConfig` 新增 `maxConnections` 配置项
- `SoContextService` 用 `AtomicInteger` 追踪活跃连接数，在 `acceptChannel` 中提前拦截

---

## 2. 设计目标

1. **轻量级嵌入式**：面向应用内嵌入场景，不需要多站点/多应用部署能力，但需要生产级的健壮性
2. **充分发挥 codec 价值**：完整支持 HTTP/1.1、HTTP/2，为 HTTP/3 预留扩展点；完整支持 WebSocket
3. **正确的线程模型**：三层线程池各司其职（AIO 层、neta IO 层、nhttp worker 层）
4. **流式请求体接收**：IO 线程收齐 Headers 后立即触发 worker，body 通过生产者-消费者模型流式传递，杜绝大请求内存溢出
5. **请求生命周期管理**：超时控制、并发限制、请求追踪
6. **流式响应**：支持 chunked streaming、SSE、大文件下载；响应写入通过 `ResponseSink` 抽象屏蔽 HTTP/1.1 与 HTTP/2 的协议差异
7. **异步处理**：支持 Servlet 异步挂起/恢复（参考 Tomcat/Jetty AsyncContext 语义）
8. **可观测性**：生命周期事件、指标收集扩展点
9. **向后兼容**：保持现有 `HttpServlet`、`Filter` 等公共接口语义，新增方法均有默认实现

---

## 3. 整体架构

### 3.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Application Layer                        │
│         HttpServlet, Filter, WebSocketHandler                │
│         (用户代码，实现业务逻辑)                                 │
├──────────────────────────────────────────────────────────────┤
│                     Container Layer                          │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │RequestManager│ │  Dispatcher  │ │   ErrorHandler        │ │
│  │(请求生命周期) │ │  (路由分发)   │ │   (错误页处理)         │ │
│  └──────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │ Connection   │ │   Session    │ │  ServerEventBus       │ │
│  │ Manager      │ │   Manager    │ │  (生命周期事件)        │ │
│  └──────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌───────────────────────────────────────────────────────────┐│
│  │              Worker ExecutorService (nhttp 业务线程池)      ││
│  └───────────────────────────────────────────────────────────┘│
├──────────────────────────────────────────────────────────────┤
│                     Connector Layer                          │
│  ┌──────────────┐ ┌──────────────┐ ┌───────────────────────┐ │
│  │HttpConnector │ │HttpsConnector│ │ Http3Connector        │ │
│  │ (HTTP/1.1    │ │ (TLS+ALPN,   │ │ (QUIC/UDP,            │ │
│  │  + h2c)      │ │  h2, h1.1)   │ │  HTTP/3) [预留]       │ │
│  └──────────────┘ └──────────────┘ └───────────────────────┘ │
│  ┌───────────────────────────────────────────────────────────┐│
│  │        PipelineFactory (协议管道构建)                      ││
│  └───────────────────────────────────────────────────────────┘│
├──────────────────────────────────────────────────────────────┤
│                  Transport Layer (neta-core)                  │
│   NetManager, NetChannel, EventLoop, Pipeline                │
│   ┌──────────────────────┐  ┌──────────────────────────────┐ │
│   │  ioExecutor (AIO层)  │  │ eventExecutor (neta IO 事件) │ │
│   └──────────────────────┘  └──────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 三层线程池模型

```
 ┌─────────────────────────────────────────────────────────────────┐
 │  Layer 1: ioExecutor (SoContextService.ioExecutor)              │
 │  职责：AIO/NIO 的 OS 级 IO 完成回调，接收原始字节流               │
 │  线程数：availableProcessors / 4（最小 1）                       │
 └──────────────────────────────┬──────────────────────────────────┘
                                │ 原始字节
 ┌──────────────────────────────▼──────────────────────────────────┐
 │  Layer 2: eventExecutor (SoContextService.eventExecutor)        │
 │  职责：neta 协议栈处理（TLS、HTTP 编解码、WebSocket 帧）          │
 │  职责：HttpObject 流分发 → 触发 worker；WebSocket 消息回调        │
 │  线程数：availableProcessors                                     │
 └──────────────────────────────┬──────────────────────────────────┘
                                │ HttpRequest headers + BodyChannel
 ┌──────────────────────────────▼──────────────────────────────────┐
 │  Layer 3: workerExecutor (nhttp 独立线程池)                     │
 │  职责：Filter Chain、Servlet 业务逻辑、响应构建                  │
 │  线程数：默认 availableProcessors * 2，上限 maxConcurrentRequests│
 └─────────────────────────────────────────────────────────────────┘
```

> **WebSocket 线程说明**：WebSocket 消息回调（`onMessage`）默认在 Layer 2（neta IO 事件线程）执行，与 Jetty/Undertow 默认行为一致。原因是 WebSocket 消息通常是轻量级事件分发，若用户需要阻塞操作应自行向 workerExecutor 提交任务。

### 3.3 HTTP/2 分区模型（per-stream 隔离）

neta 的 HTTP/2 实现采用四层模型（ByteBuf → Frame → HttpObject → 业务）并通过 `Http2ObjectPartitionSelector` 按 `streamId` 进行流分区：

```
一条 HTTP/2 TCP 连接
│
├── Http2FrameDuplexe        （ByteBuf ↔ Http2Frame，二进制帧编解码）
├── Http2ObjectDuplexe       （Http2Frame ↔ HttpObject，HPACK、流语义）
└── nextPartition（Http2ObjectPartitionSelector，按 streamId 路由）
    ├── 默认分区（streamId = 0 或控制事件）
    │   └── Http2ObjectStreamManager（GOAWAY/RST_STREAM/SETTINGS 等连接控制）
    ├── 分区 streamId=1（一个 HTTP/2 请求）
    │   └── HttpRequestHandler（与 HTTP/1.1 路径完全一致）
    ├── 分区 streamId=3（另一个 HTTP/2 请求，并发）
    │   └── HttpRequestHandler（独立实例）
    └── ...
```

**关键结论**：`byInitializer()` 为每个新 stream 动态创建独立的分区 pipeline，包含独立的 `HttpRequestHandler` 实例。因此：

- `HttpRequestHandler.currentBodyChannel` 作为实例变量完全线程安全，不需要任何 stream ID 感知
- HTTP/2 分区对 `HttpRequestHandler` 完全透明——它处理 `HttpObject` 流时与 HTTP/1.1 无本质区别
- `ResponseSink` 通过分区的 `ProtoContext`（携带 `streamId`）写响应，自动路由到正确的 HTTP/2 流
- neta HTTP/2 对象（`HttpRequest`、`HttpContent` 等）均带有 `streamId()` 属性，`Http2ResponseSink` 写出帧时使用此 ID

**聚合器（HttpObjectAggregator）的移除**：原有实现在 HTTP/1.1 和 HTTP/2 的 per-stream 分区中均使用了聚合器（`HttpServerDuplexeAggregator`），导致全量缓冲请求体。新设计从**两条路径中同时移除聚合器**，改为由 `HttpRequestHandler` 流式处理 `HttpObject`。

### 3.4 核心设计原则

- **Connector 负责协议，Container 负责业务**：Connector 在 neta IO 线程中完成协议编解码，将 `HttpRequest`（headers-only）+ `BodyChannel` 交给 Container；Container 在 workerExecutor 中执行 Servlet 逻辑
- **收齐 Headers 即触发 worker**：neta IO 线程收到完整 HTTP headers 后立即提交到 workerExecutor，body 通过 per-request 有界队列流式传递，worker 线程按需消费
- **HTTP/2 per-stream 分区透明**：`HttpRequestHandler` 不感知 HTTP/1.1 还是 HTTP/2，分区机制保证每个 handler 实例只处理单一请求流
- **请求对象构建后只读，响应对象可写但提交后不可变**
- **ResponseSink 屏蔽协议差异**：流式响应写入通过 `ResponseSink` 抽象，HTTP/1.1 实现添加 `Transfer-Encoding: chunked`，HTTP/2 实现通过分区 `ProtoContext` 直接发 DATA frame，业务代码不感知协议版本

---

## 4. 包结构设计

```
net.hasor.neta.http.server
│
├── NetaHttpServer.java              // 服务器入口（配置 + 启动/停止 + 组件组装）
├── ServerConfig.java                // 服务器配置（不可变，Builder 模式）
│
├── servlet/                         // === Servlet API 层（公共接口）===
│   ├── HttpServlet.java             // [保持] 基础 Servlet 抽象类
│   ├── ServletRequest.java          // [扩展] 请求接口 +startAsync()
│   ├── ServletResponse.java         // [扩展] 响应接口 +streaming
│   ├── Filter.java                  // [保持] 过滤器接口
│   ├── FilterChain.java             // [保持] 过滤器链接口
│   ├── ServletContext.java          // [扩展] 应用上下文
│   ├── AsyncContext.java            // [新增] 异步处理上下文
│   ├── AsyncListener.java           // [新增] 异步事件监听器
│   ├── HttpSession.java             // [保持] Session 接口
│   ├── SessionManager.java          // [保持] Session 管理器接口
│   ├── WebSocketHandler.java        // [保持] WebSocket 处理器
│   ├── WebSocketSession.java        // [保持] WebSocket 会话
│   ├── ErrorHandler.java            // [新增] 错误处理器接口
│   ├── ServerListener.java          // [新增] 服务器事件监听器
│   └── ServerMetrics.java           // [新增] 服务器运行指标（只读）
│
├── connector/                       // === 连接器层 ===
│   ├── Connector.java               // [新增] 连接器接口
│   ├── HttpConnector.java           // [新增] HTTP/1.1 + h2c 连接器
│   ├── HttpsConnector.java          // [新增] HTTPS (TLS+ALPN) 连接器
│   ├── Http3Connector.java          // [新增，预留] HTTP/3 (QUIC) 连接器，暂不实现
│   ├── PipelineFactory.java         // [新增] 协议管道构建工厂
│   ├── BodyChannel.java             // [新增] 请求体流式接收通道
│   ├── BackpressureStrategy.java    // [新增] 反压策略接口
│   └── RequestDispatchCallback.java // [新增] Connector→Container 回调接口
│
├── container/                       // === 容器层 ===
│   ├── RequestManager.java          // [新增] 请求生命周期管理器
│   ├── ConnectionManager.java       // [新增] 连接管理器
│   ├── ServletDispatcher.java       // [重写] 请求路由分发器
│   ├── ServerEventBus.java          // [新增] 服务器事件总线
│   └── DefaultErrorHandler.java     // [新增] 默认错误处理器
│
└── internal/                        // === 内部实现（全部重写）===
    ├── InternalServletRequest.java   // 请求实现（基于 BodyChannel 流式读取）
    ├── InternalServletResponse.java  // 响应实现（缓冲 + 流式双模式，通过 ResponseSink）
    ├── ResponseSink.java             // 协议感知响应写入（HTTP/1.1 vs HTTP/2）
    ├── InternalFilterChain.java      // 过滤器链实现（支持异步感知）
    ├── InternalAsyncContext.java     // 异步上下文实现
    ├── InternalServletContext.java   // 上下文实现
    ├── InternalSessionManager.java   // Session 管理器实现
    ├── InternalHttpSession.java      // Session 实现
    ├── InternalWebSocketSession.java // WebSocket Session 实现
    ├── RequestContext.java           // 请求追踪上下文（内部使用）
    ├── HttpRequestHandler.java       // IO 线程 HTTP 请求处理器（ProtoHandler<HttpObject>）
    ├── WebSocketLifecycleHandler.java// IO 线程 WebSocket 生命周期
    ├── WebSocketFrameHandler.java    // IO 线程 WebSocket 帧处理
    ├── HandshakeRequest.java         // WebSocket 握手请求适配
    └── HttpsRedirectHandler.java     // HTTPS 重定向处理器
```

---

## 5. 核心组件详细设计

### 5.1 NetaHttpServer（简化后的入口）

重构后只负责：**配置收集**、**组件组装**、**生命周期管理**。所有实际工作委托给内部组件。

```java
public class NetaHttpServer {
    private final ServerConfig       config;           // 不可变配置
    private final List<Connector>    connectors;       // 活跃连接器
    private final RequestManager     requestManager;   // 请求管理
    private final ConnectionManager  connectionManager;// 连接管理
    private final ServletDispatcher  dispatcher;       // 路由分发
    private final SessionManager     sessionManager;   // Session 管理
    private final ServerEventBus     eventBus;         // 事件总线
    private final NetManager         netManager;       // neta 传输层

    // === 配置 API（链式调用，start 前调用）===
    public NetaHttpServer serverName(String name);
    public NetaHttpServer contextPath(String path);
    public NetaHttpServer maxContentLength(int bytes);
    public NetaHttpServer maxInitialLineLength(int len);
    public NetaHttpServer maxHeaderSize(int size);
    public NetaHttpServer executor(ExecutorService executor);     // 自定义 worker 线程池
    public NetaHttpServer maxConcurrentRequests(int max);         // 最大并发请求数
    public NetaHttpServer requestTimeout(long millis);            // 请求超时
    public NetaHttpServer maxConnections(int max);                // 最大连接数（同步到 NetConfig）
    public NetaHttpServer connectionIdleTimeout(long millis);     // 连接空闲超时
    public NetaHttpServer backpressureStrategy(BackpressureStrategy s); // 反压策略
    public NetaHttpServer ssl(SslConfig ssl);
    public NetaHttpServer cors(CorsConfig cors);
    public NetaHttpServer http2(boolean enabled);
    public NetaHttpServer errorHandler(ErrorHandler handler);     // 自定义错误处理
    public NetaHttpServer addEventListener(ServerListener l);     // 事件监听
    public NetaHttpServer sessionTimeout(int seconds);

    // === 注册 API ===
    public NetaHttpServer addServlet(String pattern, HttpServlet servlet);
    public NetaHttpServer addFilter(String pattern, Filter filter);
    public NetaHttpServer addWebSocket(String path, WebSocketHandler handler);
    public NetaHttpServer setDefaultServlet(HttpServlet servlet);

    // === 生命周期 ===
    // start/startSSL 均为非阻塞调用（异步启动监听），链式返回 this
    // 调用 await() 可阻塞当前线程直到服务器停止
    public NetaHttpServer start(int port);
    public NetaHttpServer start(InetSocketAddress addr);
    public NetaHttpServer startSSL(int port);
    public NetaHttpServer startSSL(InetSocketAddress addr);
    public void stop();                               // 优雅停止（使用配置的 gracePeriod）
    public void stop(long gracePeriodMillis);          // 优雅停止（指定等待时间）
    public void await() throws InterruptedException;  // 阻塞直到服务器停止

    // === 运行时查询 ===
    public boolean isRunning();
    public ServerMetrics getMetrics();
    public SessionManager getSessionManager();
    public NetManager getNetManager();
}
```

### 5.2 ServerConfig（不可变配置对象）

```java
public class ServerConfig {
    // 基础配置
    private final String  serverName;             // = "Neta-HTTP"
    private final String  contextPath;            // = ""

    // 协议限制
    private final int     maxContentLength;       // = 1048576 (1MB)，超出时返回 413
    private final int     maxInitialLineLength;   // = 4096
    private final int     maxHeaderSize;          // = 8192
    private final int     maxChunkSize;           // = 8192
    private final int     bodyQueueCapacity;      // = 16（BodyChannel 队列容量，单位：HttpContent 个数）

    // 并发与超时控制
    private final int     maxConcurrentRequests;  // = 200
    private final long    requestTimeoutMillis;   // = 30000 (30s)
    private final int     maxConnections;         // = 10000（同步到 NetConfig.maxConnections）
    private final long    connectionIdleTimeout;  // = 60000 (60s)
    private final long    gracefulShutdownMillis; // = 30000 (30s)

    // 线程池
    private final ExecutorService executor;       // null = 使用内部默认 workerExecutor

    // 反压策略
    private final BackpressureStrategy backpressureStrategy; // = BackpressureStrategy.FAST_FAIL

    // 协议开关
    private final boolean http2Enabled;           // = true
    // HTTP/3 预留扩展点，默认关闭，当前不实现
    // private final boolean http3Enabled;        // = false（未来启用）

    // SSL / CORS
    private final SslConfig  sslConfig;
    private final CorsConfig corsConfig;

    // 扩展
    private final ErrorHandler errorHandler;      // null = 使用 DefaultErrorHandler
}
```

### 5.3 Connector 体系

```java
/**
 * 连接器接口 - 负责监听端口、构建协议管道、接受连接。
 */
public interface Connector {
    /** 协议标识: "http", "https" */
    String getProtocol();

    void start(NetManager netManager, InetSocketAddress address,
               ServerConfig config, RequestDispatchCallback callback) throws Exception;

    void stop();

    InetSocketAddress getLocalAddress();
}

/**
 * Connector → Container 的回调接口。
 * 由 neta IO 线程调用，实现方负责线程切换。
 */
public interface RequestDispatchCallback {

    /**
     * HTTP 请求 headers 收齐后触发（IO 线程调用）。
     * body 通过 bodyChannel 流式读取，IO 线程将后续 HttpContent 推入 bodyChannel。
     *
     * @param context     neta 协议上下文
     * @param headers     HTTP 请求头（headers-only，不含 body）
     * @param bodyChannel 请求体流式接收通道（IO 线程持续推送 HttpContent）
     * @param channel     底层网络通道
     * @param secure      是否 HTTPS
     */
    void onHttpRequest(ProtoContext context, HttpRequest headers,
                       BodyChannel bodyChannel, NetChannel channel, boolean secure);

    /** WebSocket 握手完成（IO 线程调用） */
    void onWebSocketOpen(ProtoContext context, WebSocketHandshakeEvent event,
                         NetChannel channel, boolean secure);

    /** 连接建立（IO 线程调用） */
    void onConnectionOpen(NetChannel channel);

    /** 连接关闭（IO 线程调用） */
    void onConnectionClose(NetChannel channel);
}
```

#### 5.3.1 BodyChannel（请求体流式接收通道）

`BodyChannel` 是 neta IO 线程（生产者）与 nhttp worker 线程（消费者）之间的桥梁。每个请求创建一个独立的有界 `BlockingQueue<HttpContent>`。

```java
/**
 * 请求体流式接收通道。
 *
 * 生产者（neta IO 线程）：将 HttpContent / LastHttpContent 推入队列。
 * 消费者（nhttp worker 线程）：通过 InternalServletRequest 按需读取。
 *
 * ByteBuf 生命周期由本通道管理——消费者读取后通道内部负责释放，用户代码不感知底层对象。
 */
public interface BodyChannel {

    /**
     * 消费者调用：读取下一个 body 片段（阻塞直到数据到来或超时）。
     * 返回 null 表示超时或通道已关闭且无更多数据。
     * 返回的 HttpContent 由通道内部在下次 read() 或 close() 时自动 release。
     */
    HttpContent read(long timeout, TimeUnit unit) throws InterruptedException;

    /** 是否已收到 LastHttpContent（body 完整接收） */
    boolean isComplete();

    /** 关闭通道，清空并释放队列中所有未读的 ByteBuf */
    void close();
}

/**
 * BodyChannel 的内部实现。
 */
class InternalBodyChannel implements BodyChannel {
    private final BlockingQueue<HttpContent> queue;     // 有界队列
    private final BackpressureStrategy       strategy;  // 队列满时的反压策略
    private final NetChannel                 channel;   // 用于 HTTP/2 flow control
    private volatile boolean                 completed; // 是否已收到 LastHttpContent

    /**
     * 由 IO 线程调用，将新到达的 HttpContent 推入队列。
     * 如果队列满，根据 BackpressureStrategy 决定行为。
     * 返回 false 表示反压策略最终拒绝，IO 线程应中止本次请求。
     */
    boolean offer(HttpContent content);
}
```

#### 5.3.2 BackpressureStrategy（反压策略）

```java
/**
 * 当 BodyChannel 队列满时（worker 消费过慢），定义 IO 线程的处理行为。
 */
public interface BackpressureStrategy {

    /**
     * IO 线程尝试推入数据时队列满，调用此方法。
     *
     * @param channel 当前连接（HTTP/2 可用于调整 flow control window）
     * @param content 待推入的 HttpContent
     * @return true = 最终成功推入；false = 放弃，IO 线程应向客户端返回错误并关闭连接
     */
    boolean onQueueFull(NetChannel channel, HttpContent content);

    /**
     * 快速失败（默认策略）：队列满立即返回 false，向客户端回 503 并关闭连接。
     * 适合严格资源控制场景。
     */
    BackpressureStrategy FAST_FAIL = (channel, content) -> false;

    /**
     * 有限等待工厂方法：等待最多 maxWaitMillis 毫秒。
     * 对 HTTP/2 连接，等待期间减少该流的 flow control window，客户端会自动降速。
     * 若超时仍未消费，返回 false。
     * 适合突发流量场景，HTTP/2 下体验优于快速失败（客户端降速而非断连）。
     */
    static BackpressureStrategy limitedWait(long maxWaitMillis) { ... }
}
```

#### 5.3.3 ResponseSink（协议感知响应写入）

`ResponseSink` 由 `PipelineFactory` 在构建管道时创建并注入 `RequestContext`，屏蔽 HTTP/1.1 与 HTTP/2 的协议差异。`InternalServletResponse` 通过它写入响应，无需关心底层协议。

```java
/**
 * 协议感知响应写入抽象。
 * 由 Connector（PipelineFactory）创建，注入 RequestContext。
 */
interface ResponseSink {

    /**
     * 发送响应 headers。
     *
     * @param statusCode HTTP 状态码
     * @param headers    响应头
     * @param streaming  true = 流式模式：
     *                   HTTP/1.1 实现自动加 Transfer-Encoding: chunked；
     *                   HTTP/2 实现直接流式发 DATA frame，不加 chunked header
     */
    void sendHeaders(int statusCode, HttpHeaders headers, boolean streaming);

    /**
     * 发送一段响应体数据。
     *
     * @param content 响应体数据
     * @param last    true = 这是最后一个数据块（HTTP/1.1: 发 0-chunk；HTTP/2: END_STREAM）
     */
    void sendContent(ByteBuf content, boolean last);

    /** 刷新当前缓冲到网络 */
    void flush();

    /** 协议版本，供极少数需要感知协议的场景使用 */
    HttpVersion protocolVersion();
}

// Http1ResponseSink：sendHeaders(streaming=true) 时加 Transfer-Encoding: chunked
//                    sendContent(last=true) 时发 LastHttpContent（0-chunk）
// Http2ResponseSink：sendHeaders() 发 HEADERS frame
//                    sendContent(last=true) 发带 END_STREAM 的 DATA frame
```

#### 5.3.4 PipelineFactory

将当前 `NetaHttpServer` 中的所有 `createXxxInitializer` 方法集中到无状态工厂类：

```java
public class PipelineFactory {

    /**
     * HTTP/1.1 应用层管道（WebSocket 升级 + HTTP 流式分发）。
     * 注意：移除旧有的 HttpServerDuplexeAggregator，直接使用 HttpRequestHandler 处理 HttpObject 流。
     */
    public static ProtoInitializer createHttpAppPipeline(
        ServerConfig config, RequestDispatchCallback callback, boolean secure);

    /** HTTP/1.1 协议层 + 应用层（含 h2c 升级桥接） */
    public static ProtoInitializer createHttp1Pipeline(
        ServerConfig config, RequestDispatchCallback callback,
        boolean secure, ProtoRoutingControl routingControl);

    /**
     * HTTP/2 协议层 + 应用层（帧解码 → 流管理 → per-stream 分区）。
     * per-stream 分区结构：
     *   - 默认分区：Http2ObjectStreamManager（连接控制面）
     *   - 业务分区：byInitializer → createHttpAppPipeline（每个新 stream 独立实例，无聚合器）
     * 注意：移除旧有的 per-stream HttpServerDuplexeAggregator。
     */
    public static ProtoInitializer createHttp2Pipeline(
        ServerConfig config, RequestDispatchCallback callback,
        boolean secure, ProtoRoutingControl routingControl);

    /** 纯 HTTP 入口（协议检测: h2 prior-knowledge vs h1） */
    public static ProtoInitializer createHttpEntryPipeline(
        ServerConfig config, RequestDispatchCallback callback, boolean secure);

    /** HTTPS 入口（TLS 检测 → ALPN 路由） */
    public static ProtoInitializer createHttpsEntryPipeline(
        ServerConfig config, RequestDispatchCallback callback);

    /** 创建协议感知的 ResponseSink（在管道构建时注入 RequestContext） */
    public static ResponseSink createResponseSink(ProtoContext ctx, HttpRequest headers);

    /** WebSocket 授权检查器 */
    public static WebSocketHandshakeAuthorizer createWebSocketAuthorizer(
        ServletDispatcher dispatcher);
}
```

三个 Connector 实现：

- **`HttpConnector`**：调用 `PipelineFactory.createHttpEntryPipeline()`，绑定 TCP
- **`HttpsConnector`**：配置 ALPN，调用 `PipelineFactory.createHttpsEntryPipeline()`，绑定 TCP
- **`Http3Connector`**：**预留接口，暂不实现**。方法体直接抛 `UnsupportedOperationException`

### 5.4 HttpRequestHandler（IO 线程请求处理器）

替代旧的内部类 `HttpDispatchHandler`。核心变化：改为 `ProtoHandler<HttpObject, Object>`（流式），去掉聚合器，收到 `HttpRequest` 时立即触发 worker。

**多协议部署说明**：
- **HTTP/1.1 路径**：一个 TCP 连接对应一个 `HttpRequestHandler` 实例，请求串行到达，`currentBodyChannel` 天然安全
- **HTTP/2 路径**：neta 的 `Http2ObjectPartitionSelector` 按 `streamId` 将每个流路由到独立的分区 pipeline；`byInitializer()` 为每个新流创建独立的 `HttpRequestHandler` 实例，因此 `currentBodyChannel` 同样安全——handler 完全不需要感知 stream ID

```java
/**
 * IO 线程 HTTP 请求处理器（HTTP/1.1 与 HTTP/2 per-stream 分区共用同一实现）。
 * 替代旧的 HttpDispatchHandler（ProtoHandler<FullHttpRequest>）。
 *
 * 流程：
 * 1. 收到 HttpRequest  → 创建 InternalBodyChannel，调用 callback.onHttpRequest()
 * 2. 收到 HttpContent  → 推入当前请求的 bodyChannel
 * 3. 收到 LastHttpContent → 推入后标记 bodyChannel.completed = true
 *
 * 注意：此 handler 在 neta IO 线程执行，不做任何业务逻辑，只做分发。
 * PipelineFactory 需从 HTTP/1.1 和 HTTP/2 两条路径中同时移除聚合器。
 */
class HttpRequestHandler implements ProtoHandler<HttpObject, Object> {
    private final boolean                  secure;
    private final ServerConfig             config;
    private final RequestDispatchCallback  callback;
    private       InternalBodyChannel      currentBodyChannel;
    private       long                     receivedBodyBytes = 0;  // maxContentLength 守卫

    @Override
    public ProtoStatus onMessage(ProtoContext ctx,
                                 ProtoRcvQueue<HttpObject> src,
                                 ProtoSndQueue<Object> dst) {
        while (src.hasMessage()) {
            HttpObject obj = src.takeMessage();

            if (obj instanceof HttpRequest) {
                HttpRequest headers = (HttpRequest) obj;
                this.receivedBodyBytes = 0;
                this.currentBodyChannel = new InternalBodyChannel(
                    config.getBodyQueueCapacity(),
                    config.getBackpressureStrategy(),
                    ctx.channel());
                callback.onHttpRequest(ctx, headers, currentBodyChannel, ctx.channel(), secure);

            } else if (obj instanceof HttpContent) {
                // ① maxContentLength 守卫（替代原聚合器的检查）
                receivedBodyBytes += ((HttpContent) obj).content().readableBytes();
                if (receivedBodyBytes > config.getMaxContentLength()) {
                    if (currentBodyChannel != null) {
                        currentBodyChannel.close();
                        currentBodyChannel = null;
                    }
                    ((HttpContent) obj).release();
                    sendRejectAndClose(ctx, 413); // 413 Payload Too Large
                    return ProtoStatus.CONTINUE;
                }

                if (currentBodyChannel != null) {
                    boolean accepted = currentBodyChannel.offer((HttpContent) obj);
                    if (!accepted) {
                        // 反压策略拒绝：向客户端发错误响应，关闭连接
                        sendRejectAndClose(ctx, 503);
                        currentBodyChannel = null;
                    }
                    if (obj instanceof LastHttpContent) {
                        currentBodyChannel = null;
                        receivedBodyBytes = 0;
                    }
                }
            }
        }
        return ProtoStatus.CONTINUE;
    }

    /**
     * 通过 neta Event 机制检测连接/流关闭，关闭 BodyChannel 解除 worker 线程阻塞。
     *
     * 事件来源：
     * - HTTP/1.1：TCP 连接关闭时 onClose() 触发（见下方）
     * - HTTP/2：per-stream 分区关闭时 onClose() 不可靠；
     *   通过 onEvent() 接收来自协议层的关闭相关事件
     *
     * 注意：AbstractHttp2Event（RST_STREAM、GOAWAY、StreamClose）由 Http2ObjectPartitionSelector
     * 路由到默认分区由 Http2ObjectStreamManager 处理，不直接投递到 stream 分区的 HttpRequestHandler。
     * Http2ObjectStreamManager 收到 RST_STREAM 后调用 closePartition()，
     * 由此触发 onClose()（对于支持此回调的分区实现）。
     * 对于不触发 onClose() 的情况，通过以下 onEvent() 兜底处理。
     */
    @Override
    public boolean onEvent(ProtoContext ctx, SoEvent event) throws Throwable {
        Object data = event.getData();
        // HTTP/2：流被重置（RST_STREAM）或连接关闭（GOAWAY）
        if (data instanceof Http2ResetEvent || data instanceof Http2GoawayEvent) {
            closeBodyChannelIfPresent();
        }
        // HTTP/2：入站方向正常结束（END_STREAM）——此时 LastHttpContent 已推入队列，
        // bodyChannel.completed 已为 true，此处不需要额外处理
        return true; // 继续向后传播事件
    }

    /**
     * HTTP/1.1：TCP 连接关闭时调用。
     * HTTP/2：部分情况下分区关闭时调用（onEvent 作为兜底）。
     */
    @Override
    public void onClose(ProtoContext ctx) {
        closeBodyChannelIfPresent();
    }

    private void closeBodyChannelIfPresent() {
        if (currentBodyChannel != null) {
            currentBodyChannel.close();
            currentBodyChannel = null;
        }
    }
}
```

### 5.5 RequestManager（请求生命周期管理 — 核心新增组件）

```java
/**
 * 请求管理器。
 * 职责：接收来自 Connector 的请求，分发到 workerExecutor，管理并发/超时/生命周期。
 */
public class RequestManager implements RequestDispatchCallback {
    private final ServerConfig                        config;
    private final ExecutorService                     workerExecutor;
    private final ServletDispatcher                   dispatcher;
    private final ErrorHandler                        errorHandler;
    private final ConnectionManager                   connectionManager;
    private final SessionManager                      sessionManager;
    private final ServerEventBus                      eventBus;
    private final ConcurrentMap<Long, RequestContext>  activeRequests;
    private final AtomicLong                          requestIdGen;
    private final Semaphore                           concurrencyLimiter;
    private final ScheduledExecutorService            timeoutScheduler;
    private final CorsConfig                          corsConfig;
    private volatile boolean                          accepting = true;

    @Override
    public void onHttpRequest(ProtoContext ctx, HttpRequest headers,
                              BodyChannel bodyChannel, NetChannel ch, boolean secure) {
        // IO 线程调用，必须立即返回
        if (!accepting) {
            bodyChannel.close();
            sendReject(ctx, headers, 503, "Service Unavailable");
            return;
        }
        if (!concurrencyLimiter.tryAcquire()) {
            bodyChannel.close();
            sendReject(ctx, headers, 503, "Too Many Requests");
            return;
        }

        ResponseSink responseSink = PipelineFactory.createResponseSink(ctx, headers);
        RequestContext reqCtx = new RequestContext(
            requestIdGen.incrementAndGet(), ctx, headers, bodyChannel, responseSink, ch, secure);
        activeRequests.put(reqCtx.getRequestId(), reqCtx);

        reqCtx.scheduleTimeout(timeoutScheduler, config.getRequestTimeoutMillis(),
            () -> handleTimeout(reqCtx));

        try {
            workerExecutor.execute(() -> processRequest(reqCtx));
        } catch (RejectedExecutionException e) {
            cleanupRequest(reqCtx);
            sendReject(ctx, headers, 503, "Service Unavailable");
        }
    }

    @Override
    public void onConnectionOpen(NetChannel channel) {
        connectionManager.register(channel);
        eventBus.fireConnectionOpened(channel);
    }

    @Override
    public void onConnectionClose(NetChannel channel) {
        connectionManager.unregister(channel);
        eventBus.fireConnectionClosed(channel);
    }

    private void processRequest(RequestContext reqCtx) {
        try {
            InternalServletRequest  servletReq  = new InternalServletRequest(reqCtx, sessionManager);
            InternalServletResponse servletResp = new InternalServletResponse(reqCtx);
            reqCtx.bind(servletReq, servletResp);

            eventBus.fireRequestReceived(servletReq);

            if (handleCorsPreflightIfNeeded(reqCtx, servletReq, servletResp)) {
                return;
            }

            dispatcher.dispatch(servletReq, servletResp);

            if (servletReq.isAsyncStarted()) {
                return; // 异步模式，由 AsyncContext.complete() 负责后续
            }

            applyCorsHeaders(reqCtx, servletReq, servletResp);
            applyAltSvcHeader(servletResp);
            servletResp.commit();
            handleKeepAlive(reqCtx);

        } catch (Throwable e) {
            handleError(reqCtx, e);
        } finally {
            if (!reqCtx.isAsyncStarted()) {
                completeRequest(reqCtx);
            }
        }
    }

    void completeRequest(RequestContext reqCtx) {
        if (reqCtx.markCompleted()) {
            reqCtx.cancelTimeout();
            reqCtx.getBodyChannel().close(); // 释放未读的 ByteBuf
            activeRequests.remove(reqCtx.getRequestId());
            concurrencyLimiter.release();
            eventBus.fireRequestCompleted(reqCtx);
        }
    }

    private void handleTimeout(RequestContext reqCtx) {
        if (reqCtx.markTimedOut()) {
            reqCtx.getBodyChannel().close();
            activeRequests.remove(reqCtx.getRequestId());
            concurrencyLimiter.release();
            eventBus.fireRequestTimeout(reqCtx);
            // 尝试发送 408；中断业务线程为尽力而为，不保证成功
        }
    }

    public boolean shutdown(long timeoutMillis) {
        accepting = false;
        // 等待 activeRequests 清空，或超时后强制关闭所有 bodyChannel
    }
}
```

### 5.6 RequestContext（请求内部追踪上下文）

```java
/**
 * 请求的内部追踪上下文。仅内部使用，不暴露给用户 API。
 */
class RequestContext {
    private final long               requestId;
    private final long               startTimeNanos;
    private final ProtoContext        protoContext;
    private final HttpRequest         headers;       // 请求头（只读）
    private final BodyChannel         bodyChannel;   // 请求体流式接收（替代 FullHttpRequest）
    private final ResponseSink        responseSink;  // 协议感知响应写入
    private final NetChannel          channel;
    private final boolean             secure;

    // 状态机: ACTIVE → COMPLETED / TIMED_OUT
    private final AtomicInteger       state;
    private static final int STATE_ACTIVE    = 0;
    private static final int STATE_COMPLETED = 1;
    private static final int STATE_TIMED_OUT = 2;

    private ScheduledFuture<?>        timeoutFuture;
    private InternalServletRequest    servletRequest;
    private InternalServletResponse   servletResponse;
    private volatile boolean          asyncStarted = false;

    boolean markCompleted();
    boolean markTimedOut();
    void cancelTimeout();
    void scheduleTimeout(ScheduledExecutorService scheduler, long millis, Runnable action);
    long elapsedNanos();
    void bind(InternalServletRequest req, InternalServletResponse resp);
    BodyChannel getBodyChannel();
    ResponseSink getResponseSink();
}
```

### 5.7 ConnectionManager

```java
/**
 * 连接管理器 - 追踪和管理所有活跃的 TCP 连接。
 *
 * 连接数上限的**拦截**通过 neta-core 的 SoContextService#acceptChannel 在 TCP 握手前完成（见 5.10节）。
 * ConnectionManager 负责连接维度的信息追踪、空闲超时和优雅关闭。两者职责互补，不重复。
 */
public class ConnectionManager {
    private final ConcurrentMap<Long, ConnectionInfo> connections;
    private final long idleTimeoutMillis;

    public void register(NetChannel channel);
    public void unregister(NetChannel channel);
    public void markActive(NetChannel channel);
    public int getActiveCount();
    public int closeIdleConnections();

    /** 优雅关闭（对 H2 发送 GOAWAY，等待后关闭所有连接） */
    public void closeAll(long gracePeriodMillis);
}

class ConnectionInfo {
    final long       channelId;
    final NetChannel channel;
    final long       connectTimeMillis;
    volatile long    lastActiveTimeMillis;
    final AtomicInteger activeRequestCount;  // HTTP/2 多路复用下的并发请求数
    String           protocol;               // "h1", "h2", "ws"
}
```

### 5.8 ServletDispatcher（全新设计）

```java
/**
 * Servlet 路由分发器。
 * 改进：支持注册优先级（order）、运行时动态添加/删除、URL pattern 预编译
 */
public class ServletDispatcher {

    public void addServlet(String urlPattern, HttpServlet servlet);
    public void addServlet(String urlPattern, HttpServlet servlet, int order);
    public void removeServlet(String urlPattern);

    public void addFilter(String urlPattern, Filter filter);
    public void addFilter(String urlPattern, Filter filter, int order);
    public void removeFilter(String urlPattern, Filter filter);

    public void addWebSocket(String path, WebSocketHandler handler);
    public void removeWebSocket(String path);
    public void setDefaultServlet(HttpServlet servlet);
    public WebSocketHandler findWebSocketHandler(String path);

    public void dispatch(ServletRequest request, ServletResponse response) throws IOException;
    public void init(ServletContext context) throws Exception;
    public void destroy();
}
```

路由匹配规则（与 Servlet 规范一致，优先级从高到低）：
1. 精确匹配：`/api/users`
2. 最长路径前缀匹配：`/api/*`
3. 扩展名匹配：`*.json`
4. 默认 Servlet：兜底

### 5.9 InternalServletResponse（全新设计 — 双模式响应）

```java
/**
 * 全新设计的响应实现，支持两种工作模式：
 *
 * 1. 缓冲模式（默认）：所有写入缓冲到内存，commit() 时通过 ResponseSink 一次性发送
 * 2. 流式模式：立即发 headers，后续 write/flush 将缓冲区内容作为数据块发送
 *
 * 状态转换：
 *   INITIAL  ──write()──────────────────────> INITIAL   (数据写入内部缓冲区)
 *   INITIAL  ──getOutputStream().flush()───> STREAMING  (自动发 headers + 第一个 chunk)
 *   INITIAL  ──startStreaming()────────────> STREAMING  (显式切换，可在写任何数据前发 headers)
 *   INITIAL  ──commit()────────────────────> COMMITTED  (Servlet 返回，一次性发完整响应)
 *   STREAMING ──flush()────────────────────> STREAMING  (发当前缓冲区内容作为 chunk)
 *   STREAMING ──commit()───────────────────> COMMITTED  (发 last-chunk，结束流)
 *
 * 用户无需手动调用 startStreaming() 即可实现流式写入：
 *   OutputStream out = response.getOutputStream();
 *   for (byte[] chunk : ...) { out.write(chunk); out.flush(); }  // 首次 flush 自动发 headers
 *
 * startStreaming() 保留作为显式 API，用于需要在写入任何 body 之前就发出 headers 的场景（如 SSE）。
 *
 * HTTP/1.1 vs HTTP/2 的差异由 ResponseSink 实现处理，此类不感知协议版本。
 */
class InternalServletResponse implements ServletResponse {
    private final RequestContext reqCtx;
    private final ResponseSink   sink;

    private enum State { INITIAL, STREAMING, COMMITTED }
    private State state = State.INITIAL;

    private int                   statusCode = 200;
    private final HttpHeaders     headers;
    private final List<Cookie>    cookies;
    private ByteArrayOutputStream buffer;    // 缓冲模式

    @Override public void setStatus(int code);
    @Override public void setHeader(String name, String value);
    @Override public void addHeader(String name, String value);
    @Override public void addCookie(Cookie cookie);
    @Override public void setContentType(String type);
    @Override public void setContentLength(long length);

    @Override public OutputStream getOutputStream();
    @Override public void write(String content);
    @Override public void write(byte[] content);
    @Override public void write(byte[] content, int offset, int length);

    @Override
    public void startStreaming() throws IOException {
        // sink.sendHeaders(statusCode, headers, streaming=true)
        // 切换 OutputStream 为 StreamingOutputStream
    }

    @Override public void flushBuffer() throws IOException;
    @Override public boolean isStreaming();
    @Override public void sendRedirect(String location);
    @Override public void sendError(int code);
    @Override public void sendError(int code, String message);
    @Override public boolean isCommitted();

    /** 缓冲模式：sink.sendHeaders() + sink.sendContent(buf, last=true)
     *  流式模式：sink.sendContent(empty, last=true) 关闭流 */
    Future<?> commit() throws IOException;
}

/** 流式模式下的 OutputStream */
class StreamingOutputStream extends OutputStream {
    private final ResponseSink     sink;
    private final ByteBufAllocator allocator;
    private       ByteBuf          chunkBuffer;

    @Override
    public void flush() {
        // sink.sendContent(chunkBuffer, false)；清空 chunkBuffer
    }

    @Override
    public void close() {
        // sink.sendContent(empty, true)  // 触发流结束
    }
}
```

### 5.10 AsyncContext（异步处理支持）

设计参考 Tomcat/Jetty 的 `AsyncContext` 语义：`startAsync()` 使 FilterChain 正常返回但不触发 commit；`complete()` 在任意线程中提交响应并释放资源；`dispatch()` 将请求重新提交到容器（走完整 FilterChain，`DispatcherType = ASYNC`）。

```java
public interface AsyncContext {
    ServletRequest getRequest();
    ServletResponse getResponse();

    /**
     * 设置异步超时（毫秒）。0 = 无超时，-1 = 使用服务器默认 requestTimeoutMillis
     */
    void setTimeout(long timeoutMillis);
    long getTimeout();

    /**
     * 完成异步处理：提交响应，释放资源（Semaphore、BodyChannel）。
     * 可从任意线程调用，幂等。
     */
    void complete();

    /**
     * 将请求重新分发到容器（DispatcherType = ASYNC），在 workerExecutor 上异步执行。
     * 会重新走完整的 FilterChain。
     * 调用方需确保 dispatch() 之后不再操作 request/response。
     */
    void dispatch();
    void dispatch(String path);

    void addListener(AsyncListener listener);
    boolean isCompleted();
}

public interface AsyncListener {
    default void onComplete(AsyncContext context) {}
    default void onTimeout(AsyncContext context) {}
    default void onError(AsyncContext context, Throwable cause) {}
    default void onStartAsync(AsyncContext context) {}
}
```

`ServletRequest` 新增：

```java
public interface ServletRequest {
    // ... 现有所有方法保持 ...

    /** 启动异步处理模式。只能在 Servlet 或 Filter 的调用栈中调用 */
    AsyncContext startAsync();

    boolean isAsyncStarted();

    /** 未启动异步时抛 IllegalStateException */
    AsyncContext getAsyncContext();

    /** 获取本次分发类型（NORMAL / ASYNC / FORWARD / INCLUDE） */
    DispatcherType getDispatcherType();
}
```

### 5.11 ErrorHandler

```java
public interface ErrorHandler {
    void handleError(int statusCode, String message, Throwable cause,
                     ServletRequest request, ServletResponse response) throws IOException;
}

public class DefaultErrorHandler implements ErrorHandler {
    @Override
    public void handleError(int statusCode, String message, Throwable cause,
                            ServletRequest request, ServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("text/html; charset=UTF-8");
            String body = "<!DOCTYPE html><html><body><h1>" + statusCode
                        + " " + escapeHtml(message) + "</h1></body></html>";
            response.write(body);
        }
    }
}
```

### 5.12 ServerEventBus + ServerListener

```java
public interface ServerListener {
    default void onServerStarted(NetaHttpServer server) {}
    default void onServerStopping(NetaHttpServer server) {}
    default void onServerStopped(NetaHttpServer server) {}
    default void onRequestReceived(ServletRequest request) {}
    default void onRequestCompleted(ServletRequest request, ServletResponse response, long elapsedMillis) {}
    default void onRequestTimeout(ServletRequest request) {}
    default void onConnectionOpened(long channelId, SocketAddress remoteAddress) {}
    default void onConnectionClosed(long channelId, SocketAddress remoteAddress) {}
}

/** 内部使用 CopyOnWriteArrayList，fire 方法异常安全（单个 listener 异常不影响其他） */
public class ServerEventBus {
    private final List<ServerListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ServerListener listener);
    public void removeListener(ServerListener listener);

    void fireServerStarted(NetaHttpServer server);
    void fireServerStopping(NetaHttpServer server);
    void fireServerStopped(NetaHttpServer server);
    void fireRequestReceived(ServletRequest request);
    void fireRequestCompleted(RequestContext reqCtx);
    void fireRequestTimeout(RequestContext reqCtx);
    void fireConnectionOpened(NetChannel channel);
    void fireConnectionClosed(NetChannel channel);
}
```

### 5.13 ServerMetrics

```java
public interface ServerMetrics {
    int  getActiveConnections();
    int  getActiveRequests();
    long getTotalRequests();
    long getTimedOutRequests();
    long getRejectedRequests();
    int  getActiveWebSockets();
    int  getActiveSessions();
}
```

### 5.14 neta-core 同步增强：SoContextService#acceptChannel

当前实现仅检查 `closeStatus`，需同步增强以支持连接数上限在 TCP 握手阶段（最早时机）拦截。

**`NetConfig` 新增**：
```java
private int maxConnections; // = 0（0 表示不限制）
```

**`SoContextService` 增强**：
```java
// 新增活跃连接计数器（用 AtomicInteger 避免 ConcurrentLinkedQueue.size() 的 O(n) 开销）
private final AtomicInteger activeChannelCount = new AtomicInteger(0);

// acceptChannel 增强
public boolean acceptChannel(SocketAddress remoteAddress) {
    if (this.closeStatus) {
        return false;
    }
    int max = this.config.getMaxConnections();
    if (max > 0 && activeChannelCount.get() >= max) {
        logger.warn("Connection rejected: maxConnections={} reached, remote={}", max, remoteAddress);
        return false;
    }
    return true;
}

// initChannel() 中：channelMap.put() 之后 activeChannelCount.incrementAndGet()
// doCloseChannel() 中：channelMap.remove() 之后 activeChannelCount.decrementAndGet()
```

> **职责分工**：连接数上限**拦截**在 `SoContextService#acceptChannel`（TCP 握手前）；`ConnectionManager` 负责连接维度的信息追踪、空闲超时和优雅关闭。两者互补，不重复。

### 5.15 线程模型总览

```
   Layer 1: AIO/NIO           Layer 2: neta IO              Layer 3: nhttp Worker
  (SoContextService.          (SoContextService.            (RequestManager.
    ioExecutor)                 eventExecutor)                workerExecutor)
 ┌──────────────┐            ┌──────────────────────┐      ┌──────────────────────┐
 │ TCP Accept   │            │ TLS Handshake         │      │                      │
 │ Raw Bytes    │ ──raw──>   │ Protocol Decode        │      │ Filter Chain         │
 │ Read/Write   │            │ HttpRequest 收齐       │      │ Servlet.service()    │
 │              │            │ → create BodyChannel   │      │                      │
 │              │            │ → callback.onHttpReq() │─────>│ InternalServletReq   │
 │              │            │                       │      │   .getInputStream()  │
 │              │            │ HttpContent 到达       │      │   → BodyChannel.read │
 │              │            │ → bodyChannel.offer() │      │                      │
 │              │            │                       │      │ (async: returns here │
 │              │            │ WebSocket 消息         │      │  AsyncContext        │
 │              │            │ → onMessage() [此层]  │      │  .complete() later)  │
 │              │ <──enc──   │ ResponseSink.send()   │ <────│ resp.commit()        │
 └──────────────┘            └──────────────────────┘      └──────────────────────┘

  不做协议以上工作              不做业务逻辑                   不直接操作 Channel
```

**默认 workerExecutor 策略**：
- 核心线程数 = `availableProcessors * 2`
- 最大线程数 = `maxConcurrentRequests`（与 Semaphore 上限对齐）
- 队列 = `SynchronousQueue`（避免排队延迟，立即分配线程或触发 Semaphore 拒绝）
- 拒绝策略 = 返回 503 Service Unavailable
- 用户可通过 `executor(ExecutorService)` 完全自定义

---

## 6. 核心流程

### 6.1 HTTP 请求完整生命周期

```
                    neta IO Thread                              Worker Thread
               ┌──────────────────────┐                    ┌──────────────────────┐
Client ──TCP─> │ 1. acceptChannel()    │                    │                      │
               │    maxConn 检查       │                    │                      │
               │    （TCP 握手前拦截） │                    │                      │
               │                       │                    │                      │
               │ 2. TLS/ALPN           │                    │                      │
               │ 3. Protocol Decode    │                    │                      │
               │                       │                    │                      │
               │ 4. HttpRequest 到达   │                    │                      │
               │    (headers 收齐)     │                    │                      │
               │    → create BodyChan  │                    │                      │
               │    → onHttpRequest()  │                    │                      │
               │    → Semaphore check  ──────────────────>  │ 5. Build ServReq/Resp│
               │                       │                    │    (with BodyChannel)│
               │ 6. HttpContent 到达   │                    │                      │
               │    → bodyChannel      │                    │ 7. CORS Check        │
               │      .offer()         │                    │                      │
               │                       │                    │ 8. FilterChain       │
               │ 7. LastHttpContent    │                    │    ├─ Filter 1       │
               │    → bodyChannel      │                    │    ├─ Filter 2       │
               │      .offer()         │                    │    └─ Servlet        │
               │      .completed=true  │                    │       reads body via │
               │                       │                    │       BodyChannel    │
               │ 10. ResponseSink      │ <───────────────── │ 9. resp.commit()     │
               │     encode + send     │                    │    or startStreaming()│
               └──────────────────────┘                    │ 10.completeRequest() │
                                                           │    Semaphore.release │
                                                           │    BodyChannel.close │
                                                           └──────────────────────┘
```

### 6.2 异步请求流程

```
  Worker Thread                         User's Async Thread
┌──────────────────┐               ┌──────────────────────────┐
│ Filter Chain      │               │                          │
│   └─ Servlet      │               │                          │
│      req.startAsync()             │                          │
│      ↓ return     │               │                          │
│ (FilterChain 返回, │               │                          │
│  框架检测到 async  │               │                          │
│  不 commit 响应   │               │                          │
│  Semaphore 不释放 │               │                          │
│  worker 线程归还) │               │ // 某个异步回调           │
│                   │               │ asyncCtx.getResponse()   │
│                   │               │   .write(data)           │
│                   │               │ asyncCtx.complete()      │
│                   │               │   → resp.commit()        │
│                   │               │   → reqMgr.complete(ctx) │
│                   │               │   → Semaphore.release()  │
│                   │               │   → BodyChannel.close()  │
└──────────────────┘               └──────────────────────────┘
```

### 6.3 流式响应流程

```
  Worker Thread                                neta IO Thread
┌──────────────────────────┐                ┌──────────────────┐
│ resp.setStatus(200)       │                │                  │
│ resp.setHeader(...)       │                │                  │
│ resp.startStreaming()     │                │                  │
│   → sink.sendHeaders(     │                │                  │
│       status, hdrs, true) │─── HEADERS ──> │ HTTP/1.1: +chunked header  │
│                           │                │ HTTP/2:   HEADERS frame     │
│ out.write(chunk1)         │                │                  │
│ out.flush()               │                │                  │
│   → sink.sendContent(     │                │                  │
│       buf1, false)        │──── DATA ────> │ HTTP/1.1: chunk  │
│                           │                │ HTTP/2: DATA frame│
│ out.write(chunk2)         │                │                  │
│ out.flush()               │──── DATA ────> │ (同上)            │
│                           │                │                  │
│ resp.commit() / close()   │                │                  │
│   → sink.sendContent(     │                │ HTTP/1.1: 0-chunk│
│       empty, true)        │── END_STREAM ─>│ HTTP/2: END_STREAM│
└──────────────────────────┘                └──────────────────┘
```

### 6.4 优雅关闭流程

```
stop(gracePeriodMillis)
    │
    ├── 1. eventBus.fireServerStopping()
    │
    ├── 2. 所有 Connector 停止接受新连接
    │      connector.stop()
    │
    ├── 3. RequestManager.shutdown(gracePeriodMillis)
    │      ├── accepting = false（新请求 → 503）
    │      ├── 等待 activeRequests 清空（每 100ms 检查）
    │      └── 超时后 close 所有活跃 bodyChannel，强制取消
    │
    ├── 4. ConnectionManager.closeAll()
    │      ├── 对 HTTP/2 连接发送 GOAWAY
    │      └── 关闭所有 TCP 连接
    │
    ├── 5. workerExecutor.shutdown()
    │      └── awaitTermination(剩余时间)
    │
    ├── 6. Dispatcher.destroy()
    │      ├── Filter.destroy() × N
    │      └── Servlet.destroy() × N
    │
    ├── 7. SessionManager.cleanExpiredSessions()
    │
    └── 8. eventBus.fireServerStopped()
```

---

## 7. 与现有 API 的兼容性

| 现有接口/类               | 变化        | 详细说明                                       |
|---------------------------|-------------|------------------------------------------------|
| `HttpServlet`             | **不变**    | service/doGet/doPost 等签名完全保持             |
| `Filter` / `FilterChain`  | **不变**    | doFilter 签名完全保持                           |
| `ServletRequest`          | **新增方法** | +`startAsync()`, `isAsyncStarted()`, `getAsyncContext()`, `getDispatcherType()` |
| `ServletResponse`         | **新增方法** | +`startStreaming()`, `flushBuffer()`, `isStreaming()` |
| `WebSocketHandler`        | **不变**    | 所有回调方法签名保持                             |
| `WebSocketSession`        | **不变**    | 所有方法签名保持                                 |
| `HttpSession`             | **不变**    | 完全保持                                        |
| `SessionManager`          | **不变**    | 完全保持                                        |
| `ServletContext`          | **新增方法** | +`getServerConfig()`                           |
| `NetaHttpServer` 公共 API | **新增方法** | +`executor()`, `maxConcurrentRequests()`, `backpressureStrategy()`, `stop(long)`, `getMetrics()` 等 |
| 所有 `internal/` 类       | **全部重写** | 类名变更、内部逻辑完全重新实现                    |

**不存在破坏性变更**：现有用户代码（实现 `HttpServlet`、`Filter` 等）无需修改即可运行。

---

## 8. 详细开发计划

### Phase 1：基础骨架搭建

**目标**：建立新的包结构和核心接口，先让代码能编译通过。

#### Step 1.1：新增公共接口和配置类
- 创建 `ServerConfig.java`（含 `bodyQueueCapacity`、`backpressureStrategy`）
- 在 `ServletRequest` 中新增 `startAsync()`, `isAsyncStarted()`, `getAsyncContext()`, `getDispatcherType()`（default 实现抛 UnsupportedOperationException）
- 在 `ServletResponse` 中新增 `startStreaming()`, `flushBuffer()`, `isStreaming()`（同上）
- 创建 `AsyncContext.java` + `AsyncListener.java`
- 创建 `ErrorHandler.java` + `DefaultErrorHandler.java`
- 创建 `ServerListener.java`、`ServerMetrics.java`
- **验收**：编译通过，现有测试不受影响

#### Step 1.2：创建 Connector 体系 + 新核心接口
- 创建 `connector/Connector.java`
- 创建 `connector/BodyChannel.java` + `connector/BackpressureStrategy.java`（含 `FAST_FAIL` 和 `limitedWait()` 实现）
- 创建 `connector/RequestDispatchCallback.java`（使用 `HttpRequest + BodyChannel` 签名）
- 创建 `internal/ResponseSink.java`（接口 + `Http1ResponseSink` + `Http2ResponseSink` 骨架）
- 创建 `connector/PipelineFactory.java`：搬入 `createXxxInitializer` 方法，新增 `createResponseSink()` 工厂方法
- 创建 `connector/HttpConnector.java`、`connector/HttpsConnector.java`
- 创建 `connector/Http3Connector.java`（存根，方法体抛 UnsupportedOperationException）
- **验收**：Connector 体系可独立编译

#### Step 1.3：创建容器层骨架
- 创建 `container/ServerEventBus.java`
- 创建 `container/ConnectionManager.java`（暂时只做计数）
- 创建 `container/ServletDispatcher.java`（从旧 `internal/ServletDispatcher` 搬入）
- **验收**：容器层基础结构就位

#### Step 1.4：增强 neta-core（SoContextService）
- `NetConfig` 新增 `maxConnections` 字段（默认 0 = 不限制）
- `SoContextService` 新增 `AtomicInteger activeChannelCount`
- 增强 `acceptChannel()` 检查连接数上限
- 在 `initChannel()` 中递增、`doCloseChannel()` 中递减
- **验收**：单元测试通过，maxConnections=N 时第 N+1 个连接被拒绝

---

### Phase 2：RequestManager 与流式 P-C 线程模型

**目标**：这是最关键的一步——引入 BodyChannel P-C 模型，将请求处理迁移到 worker 线程池。

#### Step 2.1：实现 InternalBodyChannel
- 有界 `BlockingQueue<HttpContent>`，容量可配置（`bodyQueueCapacity`）
- 实现 `FAST_FAIL` 和 `limitedWait()` 两种策略
- `close()` 遍历队列 release 所有 ByteBuf
- **验收**：单元测试覆盖 offer/read/close/满队列反压

#### Step 2.2：实现 RequestContext
- 持有 `HttpRequest headers`、`BodyChannel`、`ResponseSink`（替代旧的 `FullHttpRequest`）
- 实现状态机（ACTIVE → COMPLETED / TIMED_OUT）

#### Step 2.3：实现 RequestManager
- 实现 `RequestDispatchCallback`（新签名）
- 实现 workerExecutor 创建逻辑（默认策略）
- 实现 Semaphore 并发限制
- **暂不实现**：超时检测、异步支持（Phase 4）

#### Step 2.4：重写 HttpRequestHandler + 移除所有聚合器
- 实现 `ProtoHandler<HttpObject, Object>`（替代旧的 `HttpDispatchHandler<FullHttpRequest>`）
- **HTTP/1.1 路径**：`PipelineFactory.createHttpAppPipeline()` 移除 `HttpServerDuplexeAggregator`，改用 `HttpRequestHandler`
- **HTTP/2 路径**：`PipelineFactory.createHttp2Pipeline()` 的 per-stream `byInitializer()` 中同样移除 `HttpServerDuplexeAggregator`，复用同一 `createHttpAppPipeline()`（两条路径共享一个 handler 实现）
- **验收**：大文件上传不 OOM，HTTP/1.1 和 HTTP/2 请求均正常处理

#### Step 2.5：实现 ResponseSink 两套实现
- `Http1ResponseSink`：streaming 时加 `Transfer-Encoding: chunked`，sendContent(last=true) 发 LastHttpContent
- `Http2ResponseSink`：sendHeaders 发 HEADERS frame，sendContent(last=true) 带 END_STREAM

#### Step 2.6：重写 InternalServletRequest
- 基于 `RequestContext.bodyChannel` 实现 `getInputStream()`
- 消费 BodyChannel 时内部 release ByteBuf
- `startAsync()` 暂时抛 UnsupportedOperationException

#### Step 2.7：重写 InternalServletResponse（缓冲模式）
- 先只实现缓冲模式，通过 `ResponseSink` 发送
- `startStreaming()` 暂时抛 UnsupportedOperationException

#### Step 2.8：组装 NetaHttpServer
- 重写 `NetaHttpServer`，使用新组件体系
- **验收**：所有现有测试通过，HTTP/1.1 和 HTTP/2 请求正常处理

---

### Phase 3：流式响应支持

**目标**：实现 `startStreaming()` + `StreamingOutputStream`，验证 SSE 和大文件下载。

#### Step 3.1：实现 StreamingOutputStream
- `flush()` 调用 `ResponseSink.sendContent(chunkBuffer, false)`，清空 chunkBuffer
- `close()` 调用 `ResponseSink.sendContent(empty, true)` 关闭流

#### Step 3.2：增强 InternalServletResponse
- 实现 `startStreaming()`，通过 `ResponseSink.sendHeaders(status, hdrs, true)` 立即发送 headers
- **验收**：SSE 和大文件下载测试通过；HTTP/2 下无 `Transfer-Encoding: chunked` header

---

### Phase 4：异步处理 + 超时 + WebSocket 重构

#### Step 4.1：实现 InternalAsyncContext
- `complete()` → `resp.commit()` + `reqMgr.completeRequest()` + `BodyChannel.close()`
- `dispatch(path)` → 重新提交到 workerExecutor，DispatcherType = ASYNC，重走 FilterChain
- `setTimeout()` 注册独立定时器

#### Step 4.2：在 InternalServletRequest 中启用 startAsync
- **验收**：长轮询和异步回调场景测试通过

#### Step 4.3：请求超时检测
- 超时时：`markTimedOut()` → 发送 408 → `bodyChannel.close()` → 释放 Semaphore → 触发事件
- 注意：`Thread.interrupt()` 为尽力而为，不保证中断所有阻塞操作

#### Step 4.4：重写 InternalWebSocketSession
- 状态机：CONNECTING → OPEN → CLOSING → CLOSED

#### Step 4.5：重写 InternalFilterChain（感知异步模式）

#### Step 4.6：重写 InternalServletContext（新增 `getServerConfig()`）

#### Step 4.7：重写 Session 管理（核心逻辑搬入，新增定时清理）

---

### Phase 5：增强 Dispatcher + 连接管理

#### Step 5.1：重写 ServletDispatcher 路由匹配
- URL Pattern 预编译，支持 order，支持运行时动态注册（ReadWriteLock 保护）

#### Step 5.2：完善 ConnectionManager
- 空闲连接检测（定时扫描 `lastActiveTimeMillis`）
- `closeAll()` 对 H2 发 GOAWAY

#### Step 5.3：完善 ServerEventBus（异常安全，确保所有生命周期节点有事件）

#### Step 5.4：实现 ServerMetrics（AtomicLong 计数器，聚合各组件数据）

---

### Phase 6：测试与文档

#### Step 6.1：单元测试
- `InternalBodyChannel` 反压策略（FAST_FAIL / limitedWait）
- `RequestContext` 状态机
- `ServletDispatcher` 路由匹配（精确、前缀、扩展名、优先级）
- `InternalServletRequest` 参数解析、body 流式读取
- `InternalServletResponse` 缓冲/流式双模式
- `InternalAsyncContext` 超时和完成
- `ConnectionManager` 注册/注销/空闲超时
- `InternalSessionManager` 创建/过期/清理

#### Step 6.2：集成测试
- HTTP/1.1 完整请求/响应
- HTTP/2（h2 over TLS + h2c）
- 大文件上传（验证不 OOM，内存平稳）
- WebSocket 连接/消息/关闭
- CORS 预检和简单请求
- HTTPS 重定向
- 流式响应（SSE）
- 异步请求处理（长轮询）
- 并发限制（超出返回 503）
- 请求超时（408）
- 优雅关闭（正在处理的请求能完成）
- Filter 链执行顺序
- maxConnections 连接数上限（TCP 握手前拒绝）

#### Step 6.3：压力测试
- 吞吐量（wrk / ab）
- 大文件上传内存稳定性
- 高并发下 Semaphore + BodyChannel 反压行为
- BodyChannel ByteBuf 无泄漏（Heap dump 验证）

#### Step 6.4：文档
- 更新 README 示例
- 新功能使用指南（流式上传、流式响应、异步、事件监听、反压策略）
- 配置参数说明

---

## 9. 各 Phase 预期产出和里程碑

| Phase | 名称 | 核心交付 | 风险点 |
|-------|------|---------|--------|
| **1** | 基础骨架 | 新包结构、接口定义、Connector 体系、neta-core 增强 | 低风险；neta-core 改动需注意向后兼容 |
| **2** | 线程模型 + P-C | BodyChannel、ResponseSink、HttpRequestHandler 改造（去聚合器）、RequestManager | **最高风险**：去掉聚合器后管道兼容性、ByteBuf 生命周期管理 |
| **3** | 流式响应 | StreamingOutputStream、ResponseSink 两套实现 | 中等风险：HTTP/1.1 chunked vs HTTP/2 DATA frame 需仔细测试 |
| **4** | 异步+超时 | AsyncContext、请求超时、WebSocket/FilterChain/Session 重写 | 中等风险：异步超时边界条件多，dispatch() 重入需防死循环 |
| **5** | 增强完善 | Dispatcher 路由重写、ConnectionManager 空闲检测、Metrics | 低风险：增量增强 |
| **6** | 测试文档 | 完整测试覆盖、文档 | 低风险 |

**Phase 2 是关键路径**，完成后整个架构核心骨架确立。特别需要关注：

1. **ByteBuf 生命周期**：`InternalBodyChannel` 的每个 `HttpContent.content()` 必须在消费后精确 release（close() 时清空，read() 返回的 content 由通道在下次 read 或 close 时 release），否则内存泄漏。
2. **去掉聚合器的影响**：`PipelineFactory.createHttpAppPipeline()` 移除 `HttpObjectAggregator` 后，需确认 codec 层其他依赖聚合器的组件（如 CORS handler、WebSocket 升级）不受影响，必要时单独处理。
