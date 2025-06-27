---
sidebar_position: 4
title: 缓冲区(ByteBuf)
description: 本文将会介绍 Neta 中的缓冲区(ByteBuf)工作机制以及如何使用它。
---

# 内存

内存缓冲区分为三种类型：连续内存、池化内存、环形内存

|      | 固定容量 | 堆外内存 | 自动扩容 |
|------|------|------|------|
| 连续内存 | ✔    | ✔    | ✔    |
| 池化内存 | ✔    | ✔    | ✔    |
| 环形内存 | ✔    | ✔    | ✗    |

## 内存池

内存分配算法

BufferPool 内存池核心管理器
BufferArena 内存池按照使用率分区快管理
BufferRing 管理多个内存区块的 环形链表
PageChunkPool，被 BufferRing 管理，每个独立的内存区块，使用 伙伴算法来分配。


## 零拷贝

- 内存 0 拷贝技术



ByteBuf
所有权

- 拥有所有权等于拥有内存引用，在 free 操作上会减少引用计数，当引用计数为 0 时候内存页会被放回内存池。
- 当 Buffer 具有所有权，的写操作可以放心的进行。

ByteBuf 系统整体分为三个部分：Page、Buffer、ByteBuf

Page

基于伙伴算法以页为单位提供内存分配的辅助支持，Page 系统的特点是并不真实产生内存开销而是提供算法支持。

- 每次分配会获得一段连续的 Page
- 若干连续的 Page 组合在一起形成 Chunk
- 每个伙伴系统默认维护 4096 个 Page

Buffer

- 通过 BufferAllocator 申请真实的内存空间，(即 4096 个 Page)，一次性申请整块连续的内存
- 基于 Page 可以从 Buffer 中获取内存的操作接口 BufferPage，并通过 BufferPage 接口对内存进行读写
- Buffer 利用 Page 的 split 能力可以
-

ByteBuf







    /** Splits the buffer into two, at the splitOffset position. */
    BufferPage split(int splitOffset);

    /** Discards the compactOffset bytes, and moves the buffer contents to the beginning of the buffer. */
    BufferPage compact(int compactOffset);