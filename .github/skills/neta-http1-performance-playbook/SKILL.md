---
name: neta-http1-performance-playbook
description: '为 Neta HTTP/1.x 编解码持续性能优化提供固定工作流。覆盖正式轮次格式、基准方法、JMH 与 JFR 工具使用、full run 与 exploratory run 的边界、报告写作规则、限制项与交付检查表。适用于继续推进 HTTP1 codec、对比 Netty、维护 HTTP1_CODEC_BASELINE.md。'
argument-hint: '目标轮次、目标热点、是否需要更新正式报告、是否已有 JFR/GC 输出、是否需要对比 Netty'
user-invocable: true
disable-model-invocation: false
---

# Neta HTTP/1.x 性能优化工作流

这个 SKILL 用于指导后续 Neta HTTP/1.x codec 性能提升。目标不是生成一段零散建议，而是强制沿用已经验证过的正式流程，保证性能结论、profiling 证据、benchmark 数据和累计报告保持一致。

## 适用场景

- 继续推进性能测试
- 准备发起 N 轮正式性能优化
- 已有热点或者没有热点猜测，但需要 profiler 先确认再下手
- 根据对话要求更新指定的性能记录文档。

## 目标产物

每次完整执行后，至少应交付以下内容：

- 一处或一组相互关联的已落地代码优化
- 与变更对应的测试或最小验证结果
- 至少一份可追溯的 benchmark 原始输出文件
- 写正式报告必须有一份完整场景 full run 原始输出
- 更新后的性能记录文档
- 必要时更新仓库记忆，记录本轮有效方法、已拒绝方案和报告规则

## 基本原则

1. 正式轮次以完整场景 full run 为准。
2. exploratory run 可以用于找热点、验证方向、缩小搜索空间，但不能直接写进正式累计报告。
3. 每一轮都要明确 “本轮改造打的是哪一层热点”，避免把多个互相干扰的改动混在同一轮里。
4. late-stage 调优优先 profiler 驱动，不再依赖静态猜热点做大范围试错。
5. 报告是正式文档，不是工作日志；不写猜测、过程噪音、摇摆判断和未定结论。

## 正式轮次格式

正式报告固定维护在性能记录文档，文档结构沿用现有顺序。

### 顶层结构

正式文档应保持以下章节顺序：

1. `## 基准环境`
2. `## 初始基线`
3. `## 第一轮` 到当前最新轮次
4. `## 基线与当前主干对比`

不要在正式轮次之间插入“长窗口复测”“专项 GC”“专项 JFR”“补充观察”之类独立章节。

### 单轮写法

从第二轮开始，单轮章节默认采用下面结构：

1. `改造：`
2. 当前轮结果表
3. 2 到 3 条编号结论

其中：

- `改造：` 只写本轮真正落地并保留的变更，不写已放弃实验。
- 结果表必须包含本轮、上一轮、基线的可比数据。
- 结论只写由数据支持的稳定判断，不写推测性解释。

### 单轮结果表要求

正式 full run 轮次的结果表至少应包含：

- 场景
- 基线 ops/s
- 上一轮 ops/s
- 当前轮 ops/s
- 吞吐相对基线
- 吞吐相对上一轮
- 基线 B/op
- 上一轮 B/op
- 当前轮 B/op
- 分配相对基线
- 分配相对上一轮

场景固定为完整 7 项：

- 编码简单请求
- 编码 POST 请求
- 编码简单响应
- 解码简单请求
- 解码 POST 请求
- 解码简单响应
- 解码大响应

### 当前主干对比写法

文档结尾必须保留 `## 基线与当前主干对比`，并满足：

- 当前主干统一取最新一轮正式 full run
- 与基线和同次 Netty full run 并排对比
- 不得混用 focused run 或不同配置下的局部结果
- `当前结论` 只能引用当前最新正式轮次事实

如果某一轮正式结果被推翻或替换，必须从该轮之后重写所有受影响内容，尤其是：

- 该轮章节本身
- 后续轮次中的“相对上一轮”描述
- 文档末尾的当前主干对比
- 文档末尾的当前结论

不能只局部插入一段说明来修补旧结论。

## 方法

### 1. 先锁定正式基线

开始新一轮前，先确认：

- 当前最新正式轮次是哪一轮
- 性能记录文档中的当前主干对比引用的是哪份 full run
- 上一轮原始输出文件是否仍可复核

如果这些信息不清楚，先补齐上下文，再继续编码。

### 2. 先刷新构建产物

HTTP/1.x benchmark 使用打包 jar 时，必须先刷新上游模块，避免 shaded jar 混入旧字节码。

当前可靠刷新顺序：

```bash
cd /Users/zyc/Documents/my-project/neta
./mvnw -pl neta-core,neta-codec-http -am clean install -DskipTests -q
./mvnw -f performance/pom.xml clean package -Dmaven.test.skip=true -q
```

如果使用完整 tests profile 或其他模块链路，先确认当前 reactor 状态，再决定是否需要额外安装 `nhttp-server` 等依赖模块。

### 3. 正式 benchmark 与 exploratory benchmark 分开

正式 benchmark 的用途：

- 产出正式轮次数据
- 更新累计报告
- 与上一轮和 Netty 做正式对比

exploratory benchmark 的用途：

- 缩小热点范围
- 验证某条路径是否值得继续优化
- 观察长窗口波动和热点迁移

exploratory benchmark 可以做，但必须明确标注为内部工作数据，不进入正式累计报告，除非它本身就是新的完整 7 项正式 full run。

### 4. late-stage 优化必须先做 profiler

当收益已经进入后半段，优先顺序是：

1. 用 JFR 或可用 profiler 观察当前热点
2. 确认热点是在 parser、对象生命周期、queue、pipeline 还是 release 链
3. 只对证据最强的一层下手
4. 变更后再次验证热点是否迁移

不要直接因为“上一轮某块看起来慢”就继续沿原方向堆改动。

### 5. 每轮改造尽量保持单主题

推荐每轮只解决一类核心问题，例如：

- start-line 解析
- header entry 生命周期
- `QueueByteBuf` 行扫描路径
- response status-line 快路径
- decodeHeaders 内部判定逻辑
- parser 之外的 queue / pipeline 开销

如果一轮同时动 parser、对象模型、queue、pipeline，后续很难解释收益来源，也很难写正式结论。

### 6. 先验证功能正确，再看 full run

涉及 HTTP/1.x codec 的代码改动后，至少执行受影响模块测试。必要时用 targeted test 缩短迭代时间，但进入正式轮次前应保证相关测试通过。

推荐命令：

```bash
cd /Users/zyc/Documents/my-project/neta
./mvnw -pl neta-codec-http -am -DfailIfNoTests=false -Dtest=HttpRequestDecoderTest,HttpResponseDecoderTest test
```

### 7. 用正式 full run 收口

当局部验证通过后，再跑正式 full run。当前标准命令：

```bash
java -jar /Users/zyc/Documents/my-project/neta/performance/target/neta-performance-1.2.1-SNAPSHOT.jar \
  HttpCodecBenchmark -wi 2 -i 3 -w 1s -r 2s -f 1 -prof gc -foe true
```

如果需要写入正式报告，必须保存原始输出到 `performance/target/`，并使用清晰、一致的命名，例如：

- `http1-codec-round10-full.txt`
- `http1-codec-round10-jfr/`
- `http1-codec-round10-decode-focused.txt`

### 8. 先解析数据，再写文档

写报告前先明确整理出：

- 基线数值
- 上一轮正式数值
- 当前轮正式数值
- 同次 Netty 数值
- 每项吞吐变化百分比
- 每项分配变化百分比

可以用 `awk` 等脚本快速计算，但最终写入文档前必须人工确认每一列含义无误。

## 工具

### 必备工具

- `./mvnw`：构建和测试唯一默认入口
- JMH shaded jar：正式 benchmark 入口
- `-prof gc`：正式轮次统一分配统计
- JFR：当前环境下优先 profiler
- `jcmd`：辅助导出与检查 JFR
- `awk`：批量计算吞吐与分配百分比
- `rg` / `grep`：核对报告章节、旧结论和原始输出命名

### profiler 使用规则

优先级如下：

1. JFR
2. 其他系统已可用 profiler
3. 如果环境不具备 async-profiler，就不要伪造 async-profiler 结论

可以用 JFR 做两类观察：

- 变更前热点确认
- 变更后热点迁移复核

JFR 适合回答的问题：

- 哪个方法现在占前排样本
- 成本是在解析、对象释放还是 pipeline 协作
- 本轮改动是否把热点从目标位置移开

### exploratory benchmark 建议

以下实验可以做，但默认视为内部工作流：

- decode-only
- response-only
- request-only
- 长窗口复测
- 只跑 suspect path 的 GC 对比
- 变更前后 JFR 对照

这些结果只用于决定下一步，不直接进入正式轮次报告。

## 限制与禁令

1. 正式报告中不得写不完整场景集的数据。
2. 正式报告中不得写猜测、怀疑、暂定、待观察、日志式流水记录。
3. focused run、专项 JFR、长窗口复测不得作为独立正式轮次章节。
4. 不得把不同配置、不同命令、不同轮次的结果混在同一张正式表里。
5. 如果同一轮比较 Neta 与 Netty，必须使用同次 benchmark 输出。
6. 如果构建产物可能过期，先刷新再跑 benchmark，不要直接相信旧 jar。
7. 不要因为局部专项结果好看，就跳过正式 full run。
8. 不要为了凑结论保留已被正式 full run 推翻的旧描述。
9. 不要修改无关模块来“顺手提速”，每轮改动范围必须可解释。
10. 如果发现工作树里有用户自己的未提交改动，不要回滚它们。

## 文档编写要求

### 语气

- 正式、克制、面向事实
- 先写数据，再写结论
- 结论必须能回指到表格数据
- 不写讨论记录式口吻

### 内容边界

正式报告只记录三类信息：

- 环境与命令
- 正式轮次改造内容
- 正式轮次 full run 结果与稳定结论

以下内容默认不写入正式报告正文：

- 试错过程
- 被放弃方案
- 仅用于分析的局部 benchmark
- 没有完整 7 场景支撑的“补充结论”

### 写法要求

- 每轮 `改造：` 用编号列表，写 2 到 4 条即可
- 每轮结论用编号列表，写稳定事实，不写推演日志
- `当前结论` 只总结最新正式 full run 的主干状态
- 顶部原始输出清单只保留正式轮次 full run 文件；如需保留分析用产物，应单独放在工作记录或记忆中，不放在正式基线报告里

### 一致性检查

写完报告后，必须检查：

- 文档顶部原始输出清单是否只包含正式轮次 full run 文件
- 是否残留“长窗口复测”“响应专项”等非正式章节
- 是否出现两处或多处 `当前结论`
- `当前主干对比` 是否引用最新正式轮次
- 本轮“相对上一轮”是否真的对比上一轮正式 full run

## 推荐执行顺序

1. 读性能记录文档，确认当前正式轮次和文档尾部结论。
2. 刷新构建产物，确保 benchmark jar 不是旧字节码。
3. 如果需要，先跑 focused benchmark 和 JFR，锁定热点。
4. 实施单主题优化。
5. 跑相关测试，确认 codec 行为未回归。
6. 跑正式 full run，保存原始输出。
7. 计算基线、上一轮、当前轮、同次 Netty 的变化百分比。
8. 按既定格式更新性能记录文档。
9. 复查报告结构、标题、结论和原始输出清单。
10. 必要时把方法论、已拒绝实验和报告规则更新到 repo memory。

## 输出检查表

完成一次正式轮次前，逐项确认：

- 是否有明确的本轮主题热点
- 是否有对应代码改动和测试验证
- 是否有正式 full run 原始输出
- 是否有同次 Netty 对比
- 是否更新了累计报告
- 是否把 exploratory 结果排除在正式报告之外
- 是否重写了所有受影响的后续结论

如果以上任一项不满足，本轮还不能算正式收口。