# WebSocket 扩展支持改造计划

更新时间：2026-04-15

## 背景

当前 `neta-codec-http` 的 WebSocket 扩展能力已经从“显式不支持扩展协商”演进到“默认不启用，但可通过 settings 显式开启统一扩展框架与首批内置扩展能力”：

- 握手阶段已经可以执行扩展协商，并将协商结果写入 `WebSocketContext` / `WebSocketHandshakeEvent`
- Frame 层已经具备 `RSV1/RSV2/RSV3` 承载能力，并通过独立 `WebSocketExtensionDuplexer` 执行运行时扩展逻辑
- 当前内置正式支持能力已经覆盖 `permessage-deflate`、`deflate-frame`、`x-webkit-deflate-frame`
- 未显式启用扩展支持实现时，系统仍保持“默认拒绝扩展协商”的兼容语义
- 对外协议文档与接入示例已经同步更新，调用方可以按稳定 pipeline 形态启用扩展能力

因此，当前状态不是“完全不支持扩展”，而是“已经具备统一扩展承载框架和首批内置 frame-aware 扩展，但仍缺少多扩展编排、更广扩展覆盖，以及更高层扩展类型分层”。

## 2026-04-15 复核结论

结合当前主代码与测试复核，本文涉及的核心改造已经大部分完成：

- `WebSocketSettings`、扩展协商 SPI、结构化扩展结果、运行时扩展实例，以及 `WebSocketExtensionDuplexer` 已经形成稳定主链路。
- `permessage-deflate` 已经不是“只有最小样例能力”，而是已经具备 `no_context_takeover`、`client_max_window_bits`、`server_max_window_bits`、分片消息与双消息 round-trip 的正式支持。
- `deflate-frame` 与 `x-webkit-deflate-frame` 已经落地到主代码和测试中，且文档页 [../docs/guides/protocol/app/websocket.md](../docs/guides/protocol/app/websocket.md) 已同步说明。
- 当前真正未完成的事项，已经收敛到多扩展协商与运行时编排、更多扩展类型接入、以及 `mux` 一类高层扩展的独立模型设计。

下面的分阶段记录保留为演进轨迹；本文底部“当前状态”以这次复核结果为准。

## 目标

本计划的目标不是一次性引入完整扩展框架，而是分阶段把能力从“显式不支持”升级到“可以安全承载扩展”，并以 `permessage-deflate` 作为第一批最小实现。

总体原则如下：

- 默认行为保持兼容，未显式启用扩展时仍然拒绝扩展协商
- 第一阶段先补基础设施，不急于直接做压缩逻辑
- 第一批功能只覆盖 RFC 6455，不处理 V0
- 第一批扩展只做 `permessage-deflate`
- 扩展执行逻辑放在握手之后、`WebSocketMessageDuplexer` 之前的 Frame-aware 运行时层，不把复杂逻辑塞回握手器或基础 Frame codec

## 分阶段计划

### 第一阶段：固定边界与默认策略

#### 1.1 配置收口与协商能力落地

- [x] 为 WebSocket 引入统一的 `Settings` 配置类，集中收口握手、Frame、扩展及后续可演进配置
- [x] 将扩展相关开关、协商策略、未来扩展选项统一纳入 `Settings`，不再分散在握手器构造参数或临时逻辑中
- [x] 在基于增加项的能力下，把当前“默认拒绝扩展协商”的硬编码逻辑改为“执行协商并给出合理反馈”
- [x] 第一阶段先只完成扩展协商入口与协商结果反馈，暂不提供扩展执行实现
- [x] 明确首版仍不自动启用具体扩展实现，能力是否开启由 `Settings` 决定

当前进展说明：

- 已引入 `WebSocketSettings`，并将 `WebSocketHandshakeAuthorizer` 一并收口到 settings 中
- 扩展协商职责已收口到 `WebSocketExtension` SPI，其服务端选择与客户端校验语义分别由 `WebSocketExtensionSelector`、`WebSocketExtensionValidator` 两个父接口表达
- 扩展 SPI 入口目前位于 `websocket` 主包，内置实现位于 `websocket.extensions` 子包
- 服务端与客户端握手器都已改为通过 settings 决定是否允许扩展协商
- 未配置扩展协商组件时，仍保持“默认拒绝任何扩展”的兼容语义
- 当前只打通了“握手协商入口 + 协商结果反馈”，尚未进入扩展运行时执行阶段

阶段产出：

- WebSocket 配置入口完成统一收口
- 握手阶段已经可以执行扩展协商并返回合理结果
- 即使尚未落地扩展执行器，框架也不再依赖硬编码拒绝分支维持行为
- 后续扩展能力有明确的配置承载位与演进入口

#### 1.2 第一批支持范围收口

- [x] 明确第一批支持范围仅限 RFC 6455 分支，不扩展到 V0
- [x] 明确第一批目标仅为 `permessage-deflate`，不同时引入多扩展链能力
- [x] 明确第一批实现只围绕单扩展路径设计，不为多扩展组合预埋复杂运行时机制

当前进展说明：

- 已新增内置 `PerMessageDeflateSupport`，作为第一批官方扩展支持实现
- 该内置实现同时承担服务端选择与客户端校验职责，但统一收口到当前 `WebSocketExtension` SPI 之下
- 该内置实现只接受 RFC 6455 握手路径；V0 路径若试图协商扩展会直接失败
- 该内置实现在自身作用域内只接受单一扩展值 `permessage-deflate`；握手层对多扩展请求的分发与顺序处理由上层统一协商逻辑负责
- 当前只完成首批范围收口与握手边界校验，尚未进入参数矩阵支持与运行时执行阶段

阶段产出：

- 第一批实现范围被明确限制在 RFC 6455
- 第一批扩展目标被明确限制为 `permessage-deflate`
- 后续实现可以围绕单扩展主路径推进，避免过早引入多扩展复杂度

### 第二阶段：补齐帧模型对扩展位的承载能力

- [x] 为 `WebSocketFrame` 增加 `RSV1/RSV2/RSV3` 的访问能力
- [x] 为 `DefaultWebSocketFrame` 增加对应存储字段
- [x] 保留现有工厂方法，新增带 RSV 参数的重载，避免大面积破坏现有调用方
- [x] 在调试输出中补充 RSV 信息，便于定位扩展相关问题

当前进展说明：

- `WebSocketFrame` 已新增 `isRsv1()` / `isRsv2()` / `isRsv3()` 三个访问器
- `DefaultWebSocketFrame` 已增加对应字段，并在 `toString()` 中输出 RSV 信息
- 现有 `WebSocketFrame.create(...)` 工厂方法保持兼容，同时新增带 RSV 参数的重载
- `WebSocketFrameDecoder` 已能把线上的 RSV 位写入 frame 模型
- 对于单个大帧被切成 synthetic fragment sequence 的场景，RSV 位只保留在第一片，后续 continuation 片段清零
- `WebSocketFrameEncoder` 已能将 frame 模型中的 RSV 位重新写回 RFC 6455 帧头
- 当前仍保持“RSV 是否允许通过”由 decoder 的协议校验决定；本阶段只补承载能力，不放开未协商扩展的通过条件

阶段产出：

- Frame 模型可以真实表达扩展语义
- 后续扩展执行不需要再依赖额外 side-channel 传递 RSV 信息

### 第三阶段：构建开放扩展 API，并以最小扩展能力作为试验田

- [x] 定义扩展接入 API，使扩展能力可以通过统一入口接入握手协商、运行时上下文和 Frame 处理链
- [x] API 设计以“未来可开放接入多个扩展”为目标，但本阶段只验证单扩展路径
- [x] 选择 `permessage-deflate` 作为 API 试验田，因为它是最常见、最具代表性的 WebSocket 扩展
- [x] 在本阶段只支持 `permessage-deflate` 的最小能力，不追求完整参数矩阵
- [x] 最小能力至少覆盖：协商结果可进入运行时、`RSV1` 有合法通路、控制帧不参与扩展处理
- [x] 本阶段输出的重点是形成稳定 API，并证明该 API 足以承载一个真实扩展

当前进展说明：

- 已引入 `WebSocketExtension`、`WebSocketExtensionRuntime`、`WebSocketExtensionResult`，形成“握手协商 -> 结构化结果 -> 运行时扩展实例”的统一入口
- `WebSocketContext` 与 `WebSocketHandshakeEvent` 已同时暴露结构化扩展结果，并保留 `extensions()` 兼容字符串视图
- `WebSocketContextImpl` 已根据 settings 中注册的扩展支持实现解析协商结果并初始化运行时扩展实例，而不是只保存原始头字符串
- `WebSocketFrameDecoder` / `WebSocketFrameEncoder` 已把运行时扩展接入基础 Frame codec 路径；基础 codec 继续只处理 RFC 6455 帧格式，扩展执行只在已协商时介入
- `PerMessageDeflateSupport` 已作为试验田扩展打通最小运行时路径：支持协商结果进入上下文、`RSV1` 合法通路、控制帧严格排除、单消息与分片消息的最小压缩/解压执行
- 本阶段当时仍明确限制在单扩展 `permessage-deflate`，且暂不支持参数矩阵、上下文接管配置等更复杂行为

阶段产出：

- 形成可对外演进的扩展接入 API
- `permessage-deflate` 作为首个试验田扩展完成最小落地
- 框架首次具备“协商结果 -> 运行时模型 -> 最小扩展执行路径”的闭环
- 后续扩展可以沿这套 API 接入，而不必再次重做整体模型

### 第四阶段：构建标准扩展 API 机制，并将已有实现适配过去

- [x] 保留 `WebSocketContext.extensions()` 作为兼容接口
- [x] 增加结构化扩展结果访问能力，而不是只保留逗号分隔字符串
- [x] 能表达“是否启用某扩展”以及“该扩展的参数结果”
- [x] `WebSocketHandshakeEvent` 同步暴露结构化扩展结果或可回溯的兼容视图
- [x] 将第三阶段为 `permessage-deflate` 落地的最小执行路径抽象为可复用扩展处理层
- [x] 引入独立 `WebSocketExtensionDuplexer`，作为握手之后、`WebSocketMessageDuplexer` 之前的标准运行时扩展层
- [x] 扩展处理层按协商结果决定是否接管入站/出站帧 payload
- [x] 基础 Frame codec 继续只负责 RFC 6455 基础格式编解码
- [x] 未启用扩展时，这一层应退化为零行为或不安装
- [x] 将第三阶段的最小 `permessage-deflate` 能力扩展为首版正式能力
- [x] 将 `FrameDecoder` 当前“任意 RSV 置位即报错”的逻辑，改为查询扩展能力后再判断
- [x] 只有在已协商且已安装对应扩展执行器时，才允许对应 RSV 位通过
- [x] 未协商扩展时，继续保持当前严格拒绝语义
- [x] 控制帧即便在扩展开启后，也要继续维持严格约束
- [x] 每个子阶段内部必须同步补齐测试，测试不再作为独立阶段单列

当前进展说明：

- `WebSocketContext` 已同时保留 `extensions()` 兼容字符串视图、`extensionList()` 结构化结果视图，以及 `hasExtension(...)` 这样的能力查询入口。
- `WebSocketHandshakeEvent` 已同步暴露结构化扩展结果，并保留兼容的扩展字符串视图。
- 已引入 `WebSocketExtension` / `WebSocketExtensionRuntime`，将第三阶段的试验田能力沉淀为可复用扩展处理接口；`PerMessageDeflateSupport` 已按这套接口接入。
- 已引入独立的 `WebSocketExtensionDuplexer`，作为握手之后、消息层之前的标准运行时扩展层；相关测试 pipeline 也已按 `ws-frame -> ws-ext -> ws-msg` 语义接入。
- `WebSocketFrameDecoder` / `WebSocketFrameEncoder` 已重新收口为基础 RFC 6455 / V0 Frame codec，本身只负责基础帧格式编解码与 RSV 位承载，不再内联扩展 payload 处理逻辑。
- `WebSocketExtensionDuplexer` 负责基于协商结果调用运行时扩展，并在入站/出站方向统一处理 payload 转换、RSV 合法性校验以及运行时状态 reset / close。
- 当前 `permessage-deflate` 已具备首版正式可用能力：支持结构化协商结果、单消息与分片消息的最小压缩/解压路径、严格的控制帧约束，以及基于协商结果的 `RSV1` 合法通路。
- 扩展相关测试已经并入握手扩展测试、frame 扩展测试与 client/server flow 测试，不再依赖单独的“测试补做阶段”。
- 第四阶段范围内的目标已经完成；后续仍待支持的扩展能力统一收敛到第五阶段，不再在本阶段重复维护。

建议的 pipeline 语义：

- 当前稳定形态：握手成功 -> `WebSocketExtensionDuplexer` -> `WebSocketMessageDuplexer` -> InboundHandler
- 当前稳定形态：OutboundHandler -> `WebSocketMessageDuplexer` -> `WebSocketExtensionDuplexer` -> FrameEncoder
- 基础 Frame codec 与运行时扩展层已经完成解耦，后续演进重点转向扩展类型分层与多扩展编排边界

阶段产出：

- 扩展结果完成结构化建模，并可被运行时直接消费
- 第三阶段的单扩展试验田被沉淀为标准扩展 API 机制
- `permessage-deflate` 等 frame-aware 扩展已经具备统一运行时承载位，并已收口到独立 `WebSocketExtensionDuplexer`
- `permessage-deflate` 从试验田升级为首版正式支持能力
- 测试成为每个阶段的内建交付物，而不是尾部补做事项

### 第五阶段：在标准机制下丰富其它常见扩展

事项：

- `permessage-deflate`：
- [~] 已补齐更完整的协商参数矩阵与运行时行为：当前已覆盖 `client_no_context_takeover`、`server_no_context_takeover`、`client_max_window_bits`、`server_max_window_bits`，并补上了服务端单向收紧自身参数的协商分支；`*_max_window_bits` 已从“仅接受 `15`”推进到支持 `8..15`
- [~] 已补齐一批边界值校验与多消息往返验证：当前已覆盖重复参数、缺失值、前导零、以及 context takeover / no_context_takeover 下的双消息 round-trip；后续继续补更完整的参数矩阵与更大范围的运行时验证
- `deflate-frame`：
- [x] 接入 `deflate-frame` 扩展支持，打通握手协商、运行时压缩/解压与基础测试覆盖
- `x-webkit-deflate-frame`：
- [x] 以兼容别名方式复用 `deflate-frame` 运行时，补齐 `x-webkit-deflate-frame` 的握手协商与基础 frame 测试覆盖
- 其它 frame-aware 扩展：
- [ ] 在统一扩展机制下评估并接入下一批常见扩展，优先选择仍属于 frame-aware 范畴、无需改变 routing / owner 语义的扩展类型
- 多扩展编排：
- [ ] 放开多扩展协商与多扩展运行时编排能力，明确协商顺序、入站/出站执行顺序、冲突处理与状态边界
- [ ] 在多扩展组合场景下明确扩展顺序、冲突处理、参数矩阵与运行期状态边界
- 高层扩展模型：
- [ ] 识别哪些扩展适合接入标准 `WebSocketExtensionDuplexer`，哪些扩展不应强行收敛为单一 duplexer 内部模型
- [ ] 如果未来支持类似 `mux` 这类会改变连接内逻辑通道、owner 或 routing 语义的扩展，应按扩展类型设计单独模型，而不是强行塞进通用 payload duplexer
- 通用约束：
- [ ] 新增扩展必须通过统一 API 接入，不能走特例逻辑
- [ ] 每增加一个常见扩展，都要验证其是否仍符合既有握手、上下文、Frame、事件模型
- [ ] 扩展丰富过程继续坚持阶段内测试同步完成，不将验证工作后置

当前进展说明：

- 扩展协商不再只是“名义成功”，而是已经具备 `WebSocketSettings`、协商接口、结构化协商结果、运行时扩展实例与独立 `WebSocketExtensionDuplexer` 的完整承载链路
- `RSV1/RSV2/RSV3` 的模型承载、基础格式编解码、以及“仅已协商扩展可合法占用对应 RSV 位”的校验路径已经落地
- `permessage-deflate` 已从“只接受裸名称”推进到“可解析、可校验、可回写、可落到运行时”的参数协商路径，当前已支持 `no_context_takeover` 与 `8..15` 的 `max_window_bits` 参数，并补齐了一批 RFC 7692 边界校验
- `permessage-deflate` 在 takeover 与 no_context_takeover 两种语义下，已经补上双消息往返的定向测试，不再只覆盖单消息路径
- `deflate-frame` 已作为第五阶段第一个新增扩展完成最小支持，当前已经打通握手协商、frame 级压缩/解压路径以及定向测试覆盖
- `x-webkit-deflate-frame` 已按兼容别名方式复用 `deflate-frame` 运行时，当前已具备最小握手与 frame 级验证
- `1010 MANDATORY_EXTENSION` 的客户端/服务端角色语义已经单独修正，不再是扩展阶段阻塞项

阶段产物：

- 常见扩展可以沿统一机制持续接入，而不必继续污染基础 codec
- `permessage-deflate` 已从“最小正式支持”进入“参数矩阵初步成型”的阶段，`max_window_bits` 已具备真实运行时承载，后续继续补齐更大范围的组合验证
- `deflate-frame` 与 `x-webkit-deflate-frame` 已作为第五阶段首批新增 frame-aware 扩展，用于验证“第二个真实扩展”及其兼容别名接入统一机制的成本与边界
- 多扩展协商与编排边界得到明确定义
- 不同类型扩展的运行时承载方式得到分层：`permessage-deflate` 这类 frame-aware 扩展默认进入标准 `WebSocketExtensionDuplexer`，而 `mux` 这类改变连接/路由语义的扩展按类型拆分
- 扩展 API 机制经由多个真实扩展验证后趋于稳定
- 框架从“支持一个试验田扩展”升级为“具备可持续演进的扩展能力”

### 第六阶段：文档与示例收口

- [x] 更新协议文档，说明当前支持的扩展范围
- [x] 明确默认不启用任何扩展
- [x] 给出显式启用内置扩展的配置示例，并明确 `WebSocketExtensionDuplexer` 的接入位置
- [x] 列出当前稳定边界：仅 RFC 6455 内置扩展、`permessage-deflate` 参数范围、内置压缩扩展按单扩展用法对外说明
- [x] 随文档同步说明扩展 API 的接入方式与演进边界

阶段产出：

- 文档口径与代码行为一致
- 避免调用方误以为仅有 `extensions()` 非空或仅注册 settings 就代表扩展已被执行
- 对外形成可落地的配置与接入说明

## 建议实施批次

为降低风险，建议拆成三个可独立合入的批次：

### 批次 A：基础设施

- `Settings` 配置入口收口完成
- 握手层扩展协商能力完成基础落地
- `WebSocketFrame` / `DefaultWebSocketFrame` 增加 RSV 模型
- `WebSocketContext` 增加结构化扩展结果承载能力
- 第一批实现范围收口到 RFC 6455 + `permessage-deflate`

### 批次 B：最小功能

- 形成开放扩展 API
- 以 `permessage-deflate` 作为试验田实现最小协商与执行路径
- 放开 `RSV1` 的合法使用路径

### 批次 C：收口

- 将单扩展试验田沉淀为标准扩展机制
- 引入独立 `WebSocketExtensionDuplexer` 并完成与基础 Frame codec 的职责解耦
- 在统一机制下扩展常见能力并完成阶段内测试
- 补文档与示例

## 当前状态

- 状态：核心能力已完成，剩余增强项进行中
- 已完成：第一阶段至第四阶段、第六阶段，以及第五阶段中的首批内置扩展落地
- 优先级：中
- 前置条件：保持默认不启用扩展、未协商扩展时严格拒绝 RSV 的兼容语义不回归
- 当前阶段：第五阶段收尾
- 下一实现目标：补齐多扩展协商与运行时编排、继续扩展更多 frame-aware 扩展、为 `mux` 一类高层扩展建立独立模型，并继续补充 `permessage-deflate` 更大范围的组合边界验证
