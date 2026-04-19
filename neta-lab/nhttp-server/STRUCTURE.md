# nhttp Server 分包结构设计文档

> 本文档为结构设计阶段产物，与 `ARCHITECTURE.md`（架构设计）配套使用。  
> `ARCHITECTURE.md` 描述"做什么、为什么"，本文档描述"在哪个包、叫什么名字、谁依赖谁"。

---

## 1. 设计原则

### 1.1 层次隔离
包结构严格对应架构分层，禁止跨层反向依赖：

```
root（入口层）
  └── container/（容器层）
        └── connector/（连接器层）
              └── internal/（实现层）
```

即 `internal → connector → (外部库)` 是合法方向；`connector → internal` 或 `connector → container` 均非法。

> **已确认**：neta 管道 Handler（`HttpRequestHandler`、`WebSocketLifecycleHandler`、`WebSocketFrameHandler`、`HandshakeRequest`、`HttpsRedirectHandler`）归入 `connector/`，它们本质上是连接器层对 neta 管道的桥接，与 `PipelineFactory` 同属一层，依赖关系因此完全干净。

### 1.2 接口与实现分离
- 所有对外承诺（公共 API、跨包接口）放在接口所属层的包下
- 所有具体实现（`Internal*`、`Default*`）放在 `internal/` 子包或所属层内
- `connector/` 包内的实现类（`Http1ResponseSink`、`Http2ResponseSink`）以 package-private 方式声明，不作为外部 API

### 1.3 向后兼容
现有公共接口（`Filter`、`HttpServlet`、`ServletRequest` 等）保留在根包 `net.hasor.neta.http.server`，**不做包迁移**，确保现有用户代码无需修改。新增公共接口同样放在根包。

### 1.4 命名约定
| 类型 | 前缀/后缀 | 示例 |
|------|----------|------|
| 内部实现（对外不可见） | `Internal` 前缀 | `InternalServletRequest` |
| 默认实现（有对应公共接口） | `Default` 前缀 | `DefaultErrorHandler` |
| neta pipeline Handler | 无前缀，语义命名 | `HttpRequestHandler`、`WebSocketLifecycleHandler` |
| 连接器 | `Connector` 后缀 | `HttpConnector`、`Http3Connector` |
| 工厂 | `Factory` 后缀 | `PipelineFactory` |

---

## 2. 包结构总览

```
net.hasor.neta.http.server/                     ← 根包（公共 API + 入口）
│
├── NetaHttpServer.java                          [重写]  服务器主入口
├── ServerConfig.java                            [新增]  不可变配置对象（Builder 模式）
│
├── [公共接口 - 保持或扩展，无包迁移]
├── Filter.java                                  [保持]
├── FilterChain.java                             [保持]
├── HttpServlet.java                             [保持]
├── HttpSession.java                             [保持]
├── SessionManager.java                          [保持]
├── ServletContext.java                          [扩展]  +getServerConfig()
├── ServletRequest.java                          [扩展]  +startAsync / isAsyncStarted / getAsyncContext / getDispatcherType
├── ServletResponse.java                         [扩展]  +startStreaming / flushBuffer / isStreaming
├── WebSocketHandler.java                        [保持]
├── WebSocketSession.java                        [保持]
│
├── [新增公共接口]
├── AsyncContext.java                            [新增]  异步处理上下文
├── AsyncListener.java                           [新增]  异步事件监听器
├── DispatcherType.java                          [新增]  enum: NORMAL / ASYNC / FORWARD / INCLUDE
├── ErrorHandler.java                            [新增]  错误处理器接口
├── ServerListener.java                          [新增]  服务器生命周期事件监听器
└── ServerMetrics.java                           [新增]  运行时指标（只读接口）
│
├── connector/                                   ← 连接器层（neta IO 线程侧）
│   │
│   ├── [连接器]
│   ├── Connector.java                           [新增]  连接器顶层接口
│   ├── HttpConnector.java                       [新增]  HTTP/1.1 + h2c 连接器
│   ├── HttpsConnector.java                      [新增]  HTTPS + TLS/ALPN 连接器
│   ├── Http3Connector.java                      [新增]  HTTP/3 存根（方法体抛 UnsupportedOperationException）
│   ├── PipelineFactory.java                     [新增]  协议管道构建工厂（静态方法集合）
│   │
│   ├── [Connector↔Container 契约]
│   ├── RequestDispatchCallback.java             [新增]  Connector→Container 请求交接回调
│   ├── BodyChannel.java                         [新增]  请求体流式通道（接口）
│   ├── BackpressureStrategy.java                [新增]  反压策略（接口 + 工厂方法）
│   ├── ResponseSink.java                        [新增]  协议感知响应写入（接口）
│   ├── Http1ResponseSink.java                   [新增]  HTTP/1.1 响应写入（package-private）
│   ├── Http2ResponseSink.java                   [新增]  HTTP/2 响应写入（package-private）
│   │
│   └── [neta 管道 Handler（IO 线程桥接）]
│       ├── HttpRequestHandler.java              [新增]  HTTP 流式分发（替代旧 HttpDispatchHandler）
│       ├── HttpsRedirectHandler.java            [新增]  HTTP→HTTPS 重定向
│       ├── WebSocketLifecycleHandler.java       [新增]  WebSocket 生命周期（onActive/onClose）
│       ├── WebSocketFrameHandler.java           [新增]  WebSocket 帧处理 → onMessage 回调
│       └── HandshakeRequest.java               [新增]  WebSocket 握手阶段 ServletRequest 适配
│
├── container/                                   ← 容器层（请求生命周期）
│   ├── RequestManager.java                      [新增]  实现 RequestDispatchCallback；请求分发 + 并发管控
│   ├── ConnectionManager.java                   [新增]  连接追踪 + 空闲超时 + 优雅关闭
│   ├── ConnectionInfo.java                      [新增]  单连接信息（package-private）
│   ├── ServletDispatcher.java                   [重写]  路由分发（精确/前缀/扩展名/默认）
│   ├── ServerEventBus.java                      [新增]  生命周期事件总线
│   └── DefaultErrorHandler.java                 [新增]  默认错误处理器实现
│
└── internal/                                    ← 内部实现（全部重写，包外不可见）
    │
    ├── [请求/响应实现]
    ├── RequestContext.java                       请求内部追踪上下文（跨容器层和实现层共享）
    ├── InternalBodyChannel.java                  BodyChannel 实现（BlockingQueue + 反压）
    ├── InternalServletRequest.java               ServletRequest 实现（基于 BodyChannel 流式读取）
    ├── InternalServletResponse.java              ServletResponse 实现（缓冲/流式双模式）
    ├── StreamingOutputStream.java                流式模式专用 OutputStream
    ├── InternalFilterChain.java                  FilterChain 实现（支持异步感知）
    ├── InternalAsyncContext.java                 AsyncContext 实现
    ├── InternalServletContext.java               ServletContext 实现
    │
    ├── [Session 实现]
    ├── InternalSessionManager.java               SessionManager 实现（含定时清理）
    ├── InternalHttpSession.java                  HttpSession 实现
    │
    └── [WebSocket 实现]
        └── InternalWebSocketSession.java         WebSocketSession 实现（状态机：CONNECTING→OPEN→CLOSING→CLOSED）
```

---

## 3. 各包详细说明

### 3.1 根包 `net.hasor.neta.http.server`

**职责**：对外暴露的全部公共 API，包括服务器入口和所有用户需要实现或使用的接口。

**文件清单**：

| 文件 | 类型 | 状态 | 说明 |
|------|------|------|------|
| `NetaHttpServer` | `class` | 重写 | 服务器主类；持有 Connector 列表、Container 组件，提供配置链式 API 和 start/stop |
| `ServerConfig` | `class` | 新增 | 不可变配置对象，Builder 模式，`NetaHttpServer.build()` 后生成 |
| `Filter` | `interface` | 保持 | `doFilter(request, response, chain)` |
| `FilterChain` | `interface` | 保持 | `doFilter(request, response)` |
| `HttpServlet` | `abstract class` | 保持 | `service / doGet / doPost / ...` |
| `HttpSession` | `interface` | 保持 | `getId / getAttribute / setAttribute / ...` |
| `SessionManager` | `interface` | 保持 | `getSession / createSession / destroySession` |
| `ServletContext` | `interface` | 扩展 | 新增 `getServerConfig()` default 方法 |
| `ServletRequest` | `interface` | 扩展 | 新增 `startAsync / isAsyncStarted / getAsyncContext / getDispatcherType`（default 实现抛 UnsupportedOperationException） |
| `ServletResponse` | `interface` | 扩展 | 新增 `startStreaming / flushBuffer / isStreaming`（同上） |
| `WebSocketHandler` | `interface` | 保持 | `onOpen / onMessage / onClose / onError` |
| `WebSocketSession` | `interface` | 保持 | `send / close / getId / ...` |
| `AsyncContext` | `interface` | 新增 | `complete / dispatch / setTimeout / addListener` |
| `AsyncListener` | `interface` | 新增 | `onComplete / onTimeout / onError / onStartAsync`（均有 default 空实现） |
| `DispatcherType` | `enum` | 新增 | `NORMAL, ASYNC, FORWARD, INCLUDE` |
| `ErrorHandler` | `interface` | 新增 | `handleError(statusCode, message, cause, request, response)` |
| `ServerListener` | `interface` | 新增 | 服务器/请求/连接生命周期事件（均有 default 空实现） |
| `ServerMetrics` | `interface` | 新增 | 只读指标：活跃连接数、活跃请求数、总请求数、... |

> **设计决策**：现有 8 个公共接口/类**不迁移包路径**。新增 7 个类型同样放在根包，避免用户需要记忆多个 import 路径。`NetaHttpServer` 和 `ServerConfig` 作为入口也放在此处。

---

### 3.2 `connector` 子包

**职责**：在 neta IO 线程中完成协议编解码，将 `HttpRequest`（headers-only）+ `BodyChannel` 交给 Container。不做任何业务逻辑。

**文件清单**：

| 文件 | 访问性 | 说明 |
|------|--------|------|
| `Connector` | `public interface` | 连接器顶层接口：`start / stop / getProtocol / getLocalAddress` |
| `HttpConnector` | `public class` | 调用 `PipelineFactory.createHttpEntryPipeline()`，绑定 TCP 端口 |
| `HttpsConnector` | `public class` | 配置 TLS + ALPN，调用 `PipelineFactory.createHttpsEntryPipeline()` |
| `Http3Connector` | `public class` | 存根，所有方法抛 `UnsupportedOperationException`；文档注释说明预计启用版本 |
| `PipelineFactory` | `public class` | 静态工厂：`createHttpEntryPipeline / createHttpsEntryPipeline / createHttp1Pipeline / createHttp2Pipeline / createHttpAppPipeline / createResponseSink` |
| `RequestDispatchCallback` | `public interface` | `onHttpRequest / onWebSocketOpen / onConnectionOpen / onConnectionClose`；由 `RequestManager` 实现 |
| `BodyChannel` | `public interface` | `read(timeout, unit) / isComplete() / close()`；由 `InternalBodyChannel` 实现 |
| `BackpressureStrategy` | `public interface` | `onQueueFull(channel, content)`；含 `FAST_FAIL` 常量和 `limitedWait(maxMillis)` 工厂方法 |
| `ResponseSink` | `public interface` | `sendHeaders / sendContent / flush / protocolVersion()`；由两个 Sink 实现 |
| `Http1ResponseSink` | `package-private class` | HTTP/1.1 实现：streaming=true 时加 `Transfer-Encoding: chunked`；last=true 时发 `LastHttpContent`（0-chunk） |
| `Http2ResponseSink` | `package-private class` | HTTP/2 实现：sendHeaders 发 HEADERS frame；sendContent(last=true) 带 END_STREAM 的 DATA frame |
| `HttpRequestHandler` | `package-private class` | neta `ProtoHandler<HttpObject, Object>`；收到 `HttpRequest` 立即创建 `InternalBodyChannel` 并调用 `callback.onHttpRequest()`；含 maxContentLength 守卫；`onEvent()` 处理 `Http2ResetEvent / Http2GoawayEvent`；`onClose()` 关闭 BodyChannel |
| `HttpsRedirectHandler` | `package-private class` | neta `ProtoHandler`；HTTP 明文请求 → 302 重定向到 HTTPS；仅在强制 HTTPS 时由 `PipelineFactory` 注入管道 |
| `WebSocketLifecycleHandler` | `package-private class` | neta `ProtoHandler`（仅事件回调）；`onActive` 创建 `InternalWebSocketSession`；`onClose` 回调 `WebSocketHandler.onClose()` |
| `WebSocketFrameHandler` | `package-private class` | neta `ProtoHandler<WebSocketFrame, WebSocketFrame>`；解包 frame → 回调 `WebSocketHandler.onMessage()` |
| `HandshakeRequest` | `package-private class` | WebSocket 握手阶段对 `ServletRequest` 的适配包装，供 `WebSocketAuthorizer` 鉴权使用；握手完成后即废弃 |

> **为什么 neta Handler 和 `ResponseSink` 同在 `connector/`**：这几个类全部在 neta IO 线程执行，不包含业务逻辑，是连接器层对 neta 管道的桥接组件。统一放入 `connector/` 使依赖关系完全干净（`connector/` 只依赖 neta 外部库，`internal/` 只依赖 `connector/`，无任何反向依赖）。所有 Handler 和 Sink 实现类均为 package-private，外部不可直接构造，只能通过 `PipelineFactory` 和 `RequestDispatchCallback` 间接使用。

---

### 3.3 `container` 子包

**职责**：请求生命周期管理（并发控制、超时、路由分发、连接追踪、事件总线）。在 worker 线程池中运行，是连接器层和业务层之间的协调者。

**文件清单**：

| 文件 | 访问性 | 说明 |
|------|--------|------|
| `RequestManager` | `public class` | 实现 `RequestDispatchCallback`；管理 workerExecutor（Layer 3）、Semaphore 并发限制、超时调度；`completeRequest` 在任何线程均可安全调用 |
| `ConnectionManager` | `public class` | 注册/注销连接（`register / unregister`）；定时扫描空闲连接；`closeAll(gracePeriod)` 对 H2 发 GOAWAY |
| `ConnectionInfo` | `package-private class` | 单连接信息：`channelId / channel / connectTimeMillis / lastActiveTimeMillis / activeRequestCount / protocol` |
| `ServletDispatcher` | `public class` | URL 路由：精确 → 最长前缀 → 扩展名 → 默认 Servlet；`ReadWriteLock` 保护动态注册；支持 `order` 排序 |
| `ServerEventBus` | `public class` | `CopyOnWriteArrayList<ServerListener>`；所有 fire 方法异常安全（单个 listener 异常不影响其他） |
| `DefaultErrorHandler` | `public class` | 实现 `ErrorHandler`；响应未提交时写简单 HTML 错误页 |

> **为什么 `ConnectionInfo` 是 package-private**：它仅被 `ConnectionManager` 使用，外部不需要感知其内部字段。若未来需要在 `ServerMetrics` 中聚合，通过 `ConnectionManager` 提供的方法（`getActiveCount()`）间接访问，不暴露 `ConnectionInfo` 本身。

---

### 3.4 `internal` 子包

**职责**：所有不对外承诺的具体实现。类均声明为 `class`（非 `public` 或仅包内可见），不作为稳定 API 对外暴露。

**文件清单（按功能分组）**：

#### 请求/响应实现

| 文件 | 说明 |
|------|------|
| `RequestContext` | 请求内部追踪上下文；被 `container/RequestManager`（创建）和 `internal/Internal*`（消费）共同使用；持有 `headers / bodyChannel / responseSink / channel / state / timeoutFuture` |
| `InternalBodyChannel` | `BodyChannel` 实现；`BlockingQueue<HttpContent>` 有界队列；`offer()` 触发 `BackpressureStrategy`；`close()` 释放队列内所有 `ByteBuf` |
| `InternalServletRequest` | `ServletRequest` 实现；基于 `RequestContext.bodyChannel` 实现 `getInputStream()`；消费 `HttpContent` 时内部 release ByteBuf；解析查询参数、Cookie、multipart 等 |
| `InternalServletResponse` | `ServletResponse` 实现；缓冲/流式双模式；状态机 `INITIAL → STREAMING / COMMITTED`；`getOutputStream().flush()` 自动触发进入 STREAMING 模式 |
| `StreamingOutputStream` | 流式模式专用 `OutputStream`；`flush()` → `sink.sendContent(buf, false)`；`close()` → `sink.sendContent(empty, true)` |
| `InternalFilterChain` | `FilterChain` 实现；感知 `isAsyncStarted()`，异步模式下 FilterChain 返回时不触发 commit |
| `InternalAsyncContext` | `AsyncContext` 实现；`complete()` 幂等，调用 `resp.commit() + reqMgr.completeRequest() + bodyChannel.close()`；`dispatch(path)` 重新提交到 workerExecutor，DispatcherType = ASYNC |
| `InternalServletContext` | `ServletContext` 实现；实现 `getServerConfig()` 返回配置；持有属性 Map |

#### Session 实现

| 文件 | 说明 |
|------|------|
| `InternalSessionManager` | `SessionManager` 实现；`ConcurrentHashMap<String, InternalHttpSession>`；定时任务清理过期 Session |
| `InternalHttpSession` | `HttpSession` 实现；`lastAccessTime` 用于超时判断；属性存储线程安全 |

#### WebSocket 实现

| 文件 | 说明 |
|------|------|
| `InternalWebSocketSession` | `WebSocketSession` 实现；状态机 `CONNECTING → OPEN → CLOSING → CLOSED`；`send()` 封装 neta WebSocket frame 发送；由 `connector/WebSocketLifecycleHandler` 创建并持有 |

#### neta 管道 Handler

| 文件 | 说明 |
|------|------|
| `HttpRequestHandler` | neta `ProtoHandler<HttpObject, Object>`；收到 `HttpRequest` 立即创建 `InternalBodyChannel` 并调用 `callback.onHttpRequest()`；收到 `HttpContent` 推入队列（含 maxContentLength 守卫）；`onEvent()` 处理 `Http2ResetEvent / Http2GoawayEvent`；`onClose()` 关闭 BodyChannel |
| `HttpsRedirectHandler` | neta `ProtoHandler`；HTTP 明文请求 → 302 重定向到 HTTPS；仅在 `HttpConnector` 开启强制 HTTPS 时注入管道 |

---

## 4. 包间依赖规则

```
┌─────────────────────────────────────────────────────────────┐
│                      Root Package                           │
│  (NetaHttpServer, ServerConfig, 所有公共接口)               │
└────────────┬────────────────────┬───────────────────────────┘
             │ 组装               │ 使用公共接口
             ▼                    ▼
┌────────────────────┐  ┌───────────────────────────────────┐
│    container/      │  │            internal/              │
│ RequestManager     │  │  InternalServletRequest           │
│ ConnectionManager  │──│  InternalServletResponse          │
│ ServletDispatcher  │  │  InternalBodyChannel              │
│ ServerEventBus     │  │  RequestContext                   │
└────────┬───────────┘  └──────────────────┬────────────────┘
         │ 实现 callback                    │
         ▼                                  │ 使用
┌────────────────────────────────────────────────────────────┐
│                       connector/                           │
│  Connector, PipelineFactory                                │
│  BodyChannel, BackpressureStrategy                         │
│  ResponseSink, Http1ResponseSink, Http2ResponseSink        │
│  HttpRequestHandler, HttpsRedirectHandler                  │
│  WebSocketLifecycleHandler, WebSocketFrameHandler          │
│  HandshakeRequest                                          │
└─────────────────────────────┬──────────────────────────────┘
                              │
                              ▼
                  neta-core / neta-codec-http
                  （外部库，不属于本项目）
```

**禁止的依赖方向**：

| 禁止 | 原因 |
|------|------|
| `connector → internal` | connector 层不应知道内部实现细节 |
| `connector → container` | connector 层通过 `RequestDispatchCallback` 接口与容器解耦 |
| `internal → container` | 实现层通过 `RequestContext`（注入）与容器通信，不反向依赖 |

**`RequestContext` 的跨层传递**：`container/RequestManager` 创建 `RequestContext` 并注入（构造器参数）给 `internal/Internal*`，`internal/` 类不直接 `import container.RequestManager`，仅持有 `RequestContext` 引用。

---

## 5. 从旧结构到新结构的迁移

### 5.1 文件去向对照表

| 旧文件（`internal/`） | 去向 | 说明 |
|----------------------|------|------|
| `DefaultFilterChain` | `internal/InternalFilterChain` | 重写，支持异步感知 |
| `DefaultHttpSession` | `internal/InternalHttpSession` | 核心逻辑保留，迁移重命名 |
| `DefaultServletContext` | `internal/InternalServletContext` | 重写，新增 `getServerConfig()` |
| `DefaultServletRequest` | `internal/InternalServletRequest` | 重写，基于 BodyChannel |
| `DefaultServletResponse` | `internal/InternalServletResponse` | 重写，双模式 |
| `DefaultSessionManager` | `internal/InternalSessionManager` | 核心逻辑保留，迁移重命名 |
| `DefaultWebSocketSession` | `internal/InternalWebSocketSession` | 重写，清晰状态机 |
| `ServletDispatcher` | `container/ServletDispatcher` | 迁移包，逻辑重写 |

| 旧文件（`server/`，根包） | 去向 | 说明 |
|--------------------------|------|------|
| `NetaHttpServer`（内部类群） | 拆分到 `connector/`、`container/`、`internal/` | 保持类名 `NetaHttpServer`，但内部逻辑全部委托给子组件 |
| `NetaHttpServer.HttpDispatchHandler` | `connector/HttpRequestHandler` | 独立为顶层类，签名改为 `ProtoHandler<HttpObject, Object>`，迁入 connector/ |
| `NetaHttpServer.WebSocketLifecycleHandler`（内部类） | `connector/WebSocketLifecycleHandler` | 独立为顶层类，迁入 connector/ |

### 5.2 新增文件（无对应旧文件）

```
connector/   Connector, HttpConnector, HttpsConnector, Http3Connector
             PipelineFactory（从 NetaHttpServer 方法提取）
             RequestDispatchCallback
             BodyChannel, BackpressureStrategy
             ResponseSink, Http1ResponseSink, Http2ResponseSink
             HttpRequestHandler, HttpsRedirectHandler
             WebSocketLifecycleHandler, WebSocketFrameHandler, HandshakeRequest

container/   RequestManager, ConnectionManager, ConnectionInfo
             ServerEventBus, DefaultErrorHandler

internal/    RequestContext, InternalBodyChannel
             StreamingOutputStream, InternalFilterChain
             InternalAsyncContext

root/        ServerConfig
             AsyncContext, AsyncListener, DispatcherType
             ErrorHandler, ServerListener, ServerMetrics
```

### 5.3 测试文件迁移

测试包结构与 main 保持一一对应：

| 旧测试包 | 新测试包 |
|---------|---------|
| `net.hasor.neta.http.server` | `net.hasor.neta.http.server`（集成测试不动） |
| `net.hasor.neta.http.server.internal` | `net.hasor.neta.http.server.internal`（单元测试跟随实现类） |
| —（新增） | `net.hasor.neta.http.server.connector`（ResponseSink 测试） |
| —（新增） | `net.hasor.neta.http.server.container`（RequestManager、SessionDispatcher 测试） |

---

## 6. 关键文件详细说明

本节补充说明几个在结构层面容易产生疑问的文件。

### 6.1 `RequestContext`（`internal/`）

`RequestContext` 是容器层（container）和实现层（internal）之间的数据载体。它由 `container/RequestManager` 创建，传入 `internal/Internal*` 构造器，生命周期由 `RequestManager.completeRequest()` 管理。

```
RequestManager（container）
  │  创建
  ▼
RequestContext（internal）
  │  注入
  ├──> InternalServletRequest（internal）
  ├──> InternalServletResponse（internal）
  └──> InternalAsyncContext（internal）
```

`RequestContext` 虽然在 `internal/` 包，但 `container/RequestManager` 需要引用它。这是唯一一处 `container → internal` 的依赖，通过以下方式保持合理：`RequestContext` 是纯数据持有者（DTO），不包含业务逻辑，`RequestManager` 创建并持有其引用，调用其状态变更方法（`markCompleted / markTimedOut / cancelTimeout`）。这与"实现类不被外部依赖"的原则不冲突——`RequestManager` 是创建者而非消费者。

### 6.2 `PipelineFactory`（`connector/`）

`PipelineFactory` 是 `connector/` 包内最复杂的类，将旧 `NetaHttpServer` 中所有 `createXxxInitializer` 方法集中到此。它的职责：

- 构建 neta 协议管道（装配 neta Handler 序列）
- 创建 `ResponseSink` 实例（根据协议版本返回 `Http1ResponseSink` 或 `Http2ResponseSink`）
- 创建并装配管道 Handler（`HttpRequestHandler`、`WebSocketLifecycleHandler` 等）

**已确认**：`HttpRequestHandler`、`HttpsRedirectHandler`、`WebSocketLifecycleHandler`、`WebSocketFrameHandler`、`HandshakeRequest` 均归入 `connector/`。理由：这些类全部运行在 neta IO 线程，不包含 Servlet 业务逻辑，是连接器层对 neta 管道的桥接实现，与 `PipelineFactory` 天然同属一层。将它们放入 `connector/` 后，依赖关系完全单向：

```
internal/ → connector/ → neta 外部库
```

无任何反向依赖或跨层环路。所有 Handler 类均为 package-private，用户代码不可见；`PipelineFactory` 是唯一的组装入口。

### 6.3 `Http3Connector`（`connector/`）

存根实现，所有方法统一处理：

```java
public class Http3Connector implements Connector {
    @Override
    public String getProtocol() { return "http/3"; }

    @Override
    public void start(...) {
        throw new UnsupportedOperationException(
            "HTTP/3 support is not yet implemented. Planned for a future release.");
    }
    // 其余方法同上
}
```

`NetaHttpServer` 不创建此 Connector 实例；保留此类仅为扩展点存档。

---

## 7. Phase 1 开发顺序与文件创建顺序

结合 ARCHITECTURE.md §8 的开发计划，Phase 1 文件创建顺序建议：

```
Step 1.1  root/
          └── 新增公共接口 (AsyncContext, AsyncListener, DispatcherType,
                            ErrorHandler, ServerListener, ServerMetrics, ServerConfig)
          └── 扩展现有接口 (ServletRequest, ServletResponse, ServletContext)
              （加 default 方法，不破坏现有实现）

Step 1.2  connector/
          ├── 接口/策略先行: Connector, RequestDispatchCallback, BodyChannel,
          │                  BackpressureStrategy, ResponseSink
          ├── 存根 Connector: Http3Connector
          ├── Handler 骨架: HttpRequestHandler, HttpsRedirectHandler,
          │                 WebSocketLifecycleHandler, WebSocketFrameHandler, HandshakeRequest
          │                 （签名就位即可，具体逻辑 Phase 2 填充）
          ├── 工厂骨架: PipelineFactory（搬入旧逻辑，暂不清聚合器）
          └── 实现 Connector: HttpConnector, HttpsConnector

Step 1.3  container/
          ├── ServerEventBus
          ├── ConnectionManager（暂时只做计数）
          └── ServletDispatcher（从 internal/ServletDispatcher 搬入迁移包）

Step 1.4  neta-core（跨模块）
          └── NetConfig + SoContextService 增强（maxConnections）

Step 2+   internal/（Phase 2 开始填充）
```
