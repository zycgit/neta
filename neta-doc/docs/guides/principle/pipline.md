---
sidebar_position: 3
title: 管道
description: 本文将会介绍管道在不同行为上的具体逻辑。
---

# 管道

## 规则

消息在 Pipeline 各个 Handler 环节传递的过程中遵循如下规则：

- 通常情况下是按照顺序依次执行，即：使用 PipeStatus.Next。
- 包括 PipeStatus.Next 之内 Pipeline 的行为共有 8 种模式可供选用。
- 在处理一次 I/O 事件中如果某个 Handler 环节发生异常，则当前事件处理链上的 PipeContext 会被设置为异常标记。并且自动进入 onError 方法进行处理。如果异常标记没有被清理那么异常会随着 Pipeline 向后传递。
- 任何时候异常被清除后都可以通过 PipeStatus.Retry 方式重新进入当前 Handler 的 onMessage 方法，也可以使用 PipeStatus.Next 恢复后续的 Pipeline 正常执行。

以常规的顺序执行模式为例，控制 Pipeline 的行为只需要为 onMessage 或 onError 方法设定正确的返回值即可，如下：

```java
public PipeStatus onMessage(PipeContext context, 
        PipeRcvQueue<ByteBuf> src, PipeSndQueue<String> dst) {
    ...
    return PipeStatus.Next;
}
```

## Next 模式

作用：继续执行流水线

```text
┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
┃ Handler (0) ┃ > ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
      Next              Next              Next
```

## Retry 模式

作用：重试此方法调用，再次使用以避免递归

```text
                ╭──────╮
┏━━━━━━━━━━━━━┓ │  ┏━━━┷━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
┃ Handler (0) ┃ ┷> ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
┗━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
      Next              Retry             Next
```

## Again 模式

作用：管道完成后重新启动。
- 如果管道被中断，它将不会重新启动

```text
╭──────────────────────────────────────────────╮
│  ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓         ┏━┷━━━━━━━━━━━┓
┕> ┃ Handler (0) ┃ > ┃ Handler (1) ┃ > ... > ┃ Handler (2) ┃ > ...
   ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛         ┗━━━━━━━━━━━━━┛
        Next              Again                   Next
```

## Back 模式

作用：管道完成后返回当前节点重新启动。
- 如果管道处于中断状态，则不会重新启动。

```text
                ╭────────────────────────────╮
┏━━━━━━━━━━━━━┓ │  ┏━━━━━━━━━━━━━┓         ┏━┷━━━━━━━━━━━┓
┃ Handler (0) ┃ ┷> ┃ Handler (1) ┃ > ... > ┃ Handler (2) ┃ > ...
┗━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━┛         ┗━━━━━━━━━━━━━┛
      Next               Back                    Next
```

## Restart 模式

作用：中断管道事件传播并重启管道。

```text
╭──────────────────────╮
│  ┏━━━━━━━━━━━━━┓   ┏━┷━━━━━━━━━━━┓   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄╮
┕> ┃ Handler (0) ┃ > ┃ Handler (1) ┃ > ┆ Handler (2) ┆ > ...
   ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
        Next             Restart             Skip
```

## Skip 模式

作用：继续执行，跳过管道的下一个节点。

```text
    ┏━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┏━━━━━━━━━━━━━┓
... ┃ Handler (0) ┃ > ┆ Handler (1) ┆ > ┃ Handler (2) ┃ > ...
    ┗━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ┗━━━━━━━━━━━━━┛
         Skip            (skipped)            Next
```

## Exit 模式

作用：中断管道事件传播，并跳过以下所有 Handler

```text
    ┏━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄╮
... ┃ Handler (0) ┃ > ┆ Handler (1) ┆ > ┆ Handler (2) ┆ > end
    ┗━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
         Exit              Skip               Skip
```

## Interrupt 模式

作用：中断管道事件传播，并抛出错误

```text
    ┏━━━━━━━━━━━━━━┓
... ┃ PipeNode (0) ┃ > Throw Error
    ┗━━━━━━━━━━━━━━┛
        Interrupt
```
