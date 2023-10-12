# Cobble ByteBuf

&emsp;&emsp; Cobble ByteBuf 是一款增强的 ByteBuf 框架，用于替代 JDK ByteBuf，相比较于 Netty ByteBuf 多了并发特性且更小更轻量。

## 功能和特性

- 支持 `动态容量` 和 `固定容量` 两种模式
- 支持 读/写模式一体化
- 支持 `ByteBuf` 零拷贝扩缩容
- 支持 `多线程` 场景下 `ByteBuf` 并发操作
- 支持 `Direct 内存`
- 支持 `大端/小端` 字节序
- 支持 `InputStream` 和 `OutputStream` 串联

## 同类对比

| 特性                                 | Cobble | Netty | JDK |
|------------------------------------|--------|-------|-----|
| 动态扩容                               | ✅      | ✅     | ✅   |
| 支持 Direct 内存                       | ✅      | ✅     | ✅   |
| 大、小端 字节序                           | ✅      | ✅     | ✅   |
| copy 方法                            | ✅      | ✅     | ✅   |
| duplicate 方法                       | ❌      | ✅     | ✅   |
| slice 方法                           | ❌      | ✅     | ✅   |
| readOnly 方法                        | ❌      | ✅     | ✅   |
| 零拷贝                                | 扩容时    | ✅     | ❌   |
| 读/写模式一体化                           | ✅      | ✅     | ❌   |
| ByteBuf 转为 输出流                     | ✅      | ✅     | ❌   |
| ByteBuf 转为 输入流                     | ✅      | ✅     | ❌   |
| 管道模式（`Input -> ByteBuf -> Output`） | ✅      | ❌     | ❌   |
| 并发互斥锁                              | ✅      | ❌     | ❌   |
| 多线程并发 读/写                          | ✅      | ❌     | ❌   |


## 引入依赖

```xml
<dependency>
    <groupId>net.hasor</groupId>
    <artifactId>cobble-bytebuf</artifactId>
    <version>4.6.1</version>
</dependency>
```

## 软件质量

- 行测试覆盖率：70%
- 有效代码行：1264
