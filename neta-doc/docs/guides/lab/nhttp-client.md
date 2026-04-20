# nhttp Client 设计与实现说明

## 目标

`nhttp` 的 client 侧 API 采用明确的三层原则：

- 用户只面向 `HttpClient`、`Call`、`Response`、`WebSocket`。
- 出站请求模型统一复用 `net.hasor.nhttp.request.Request`。
- 连接、协议协商、超时、并发控制、生命周期管理全部下沉到内部运行时。

这意味着调用方式保持 OkHttp 风格，但实现继续复用 Neta 的协议栈与 Future 模型。

## Public API

客户端对外暴露如下对象：

- `HttpClient`：主入口，Builder 风格创建。
- `Request`：唯一请求模型，继续使用 `net.hasor.nhttp.request` 包。
- `Call`：一次 HTTP 调用。
- `Response`：聚合响应结果。
- `WebSocket`：已打开的 WebSocket 会话。
- `WebSocketListener`：WebSocket 生命周期与消息监听器。

异步模型统一使用 `net.hasor.cobble.concurrent.future.Future`：

- `Call.executeAsync()` 返回 `Future<Response>`
- `HttpClient.openWebSocket(...)` 返回 `Future<WebSocket>`

同步调用只是对异步结果的阻塞等待封装。

## 内部结构

实现分为三层：

1. public API
   - `net.hasor.nhttp.client`
2. internal runtime
   - `net.hasor.nhttp.client.internal`
3. codec / transport
   - 复用 `neta-core`、`neta-codec-http`、`neta-codec-ssl`

内部运行时由以下职责组成：

- `ClientRuntime`
  - 管理 `NetManager`
  - 负责 HTTP / WebSocket 的真正执行
  - 统一处理 TLS、协议版本选择、握手、消息分发、资源回收
- `ClientDispatcher`
  - 管理全局并发与按主机并发限制
  - 调度 `Call` 与 WebSocket open 请求
- `RealCall`
  - 实现单次 HTTP 调用
- `RealWebSocket`
  - 实现已连接的 WebSocket 会话

## 协议策略

### HTTP

- 请求序列由 `HttpWriter.write(request, version)` 生成。
- 明文连接：
  - `AUTO` 等价于 HTTP/1.1
  - `HTTP_2` 走 h2 prior knowledge pipeline
- TLS 连接：
  - `AUTO` 通过 ALPN 在 `h2` 和 `http/1.1` 之间协商
  - `HTTP_2` 固定只协商 `h2`
  - `HTTP_1_1` 固定只协商 `http/1.1`

### WebSocket

- WebSocket 也通过 `Request` 进入 client。
- 握手阶段会把 `Request` 中的 headers 合并进标准握手报文。
- HTTP/1.1 使用 RFC 6455 upgrade。
- HTTP/2 使用 RFC 8441 CONNECT 风格握手。

## 超时与并发

当前实现落地如下控制项：

- `connectTimeoutMillis`
- `readTimeoutMillis`
- `writeTimeoutMillis`
- `callTimeoutMillis`
- `webSocketOpenTimeoutMillis`
- `maxConcurrentCalls`
- `maxConcurrentCallsPerHost`

这版并发控制优先保证 API 行为和生命周期可控，暂不引入跨请求连接复用池。也就是说：

- HTTP exchange 采用“每次调用独立连接”的执行策略
- WebSocket 会话采用“每个会话独立连接”的执行策略
- 并发、取消、超时、订阅回收已经统一由 runtime 管理

这样做的原因是先稳定 public API 与运行时边界，再在下一阶段把连接池和多路复用复用能力接入到同一运行时接口下。

## 测试组织

`nhttp` 的测试统一落在 `net.hasor.nhttp` 根下，并按模块继续分包：

- `net.hasor.nhttp.request`
- `net.hasor.nhttp.client.http`
- `net.hasor.nhttp.client.websocket`

其中 client 测试覆盖：

- HTTP/1.1 同步请求
- HTTP 异步 Future 请求
- 按主机并发限制
- HTTPS + HTTP/2 ALPN 请求
- WebSocket open / text echo / close 生命周期

## 后续演进

当前实现已经完成新的 public client 主旋律：`HttpClient + Request.Builder + Future`。

下一阶段若继续演进，优先级建议为：

1. 在 `ClientRuntime` 内接入连接池与空闲连接回收。
2. 将 HTTP/2 连接复用提升为真正的多 stream 调度。
3. 在同一 runtime 抽象下挂接 HTTP/3 / QUIC adapter。