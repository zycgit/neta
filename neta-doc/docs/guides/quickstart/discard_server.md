---
sidebar_position: 1
title: 例1：丢弃一切数据
description: dbVisitor 架构在整体上体系化，局部层面各个模块遵循独立原则。因此每一个模块几乎都可以独立使用而且互不影响。
---

# 客户端





## 构建协议栈

当链接被创建后 Neta 会通过 ProtoInitializer 接口为新的链接创建 ProtoStack。在构建协议栈的过程中两层协议处理器之间需要匹配类型。

位于最底层的 Handler 其 RCV_UP




EmbeddedInitializer initializer = ctx -> ProtoHelper.embedded(Integer.class, Integer.class)//
.nextDuplex(doNextHandler("1Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("1Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
.nextDuplex(doNextHandler("2Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("2Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
.nextDuplex(doNextHandler("3Dec", decoderFinishCnt, decoderFailedCnt), doNextHandler("3Enc", encoderFinishCnt, encoderFailedCnt)) // rcv/snd +1
.build();



