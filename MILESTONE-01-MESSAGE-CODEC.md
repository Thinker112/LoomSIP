# 里程碑 01：SIP 消息模型与编解码

## 目标

第一阶段建立一个不依赖 Netty、Spring 或 Reactor 的纯 Java 协议核心：能够将一条完整的 RFC 3261 风格 SIP 报文解析为不可变 Java 对象，并将对象重新编码为合法 SIP 报文。

本阶段优先验证 SIP 数据模型、字节边界和扩展兼容性，不引入网络、Transaction、Dialog、Mailbox 或定时器。

## 工程约束

- 使用 JDK 21 和 Maven。
- 主包名使用 `org.loomsip`。
- 使用 record、sealed interface 等 JDK 语言能力，不引入 Lombok。
- 使用 JUnit 5 编写测试。
- SIP 核心模型不暴露 Netty `ByteBuf`。
- Body 始终按字节处理，不假定为文本。

## 实现范围

### 消息模型

第一版包括：

- `SipMessage`
- `SipRequest`
- `SipResponse`
- `SipMethod`
- `SipVersion`
- `SipUri`
- `SipHeader`
- `SipHeaders`
- `SipBody`

消息及其组成对象保持不可变。`SipBody` 在构造和读取时执行防御性复制，避免调用者在消息创建后修改内容。

`SipMethod` 使用可扩展值对象而不是封闭 enum，使协议栈能够保留标准外的 SIP Method。

### Header 模型

第一版使用有序通用 Header 表示，并满足：

- Header 名称查询不区分大小写。
- 保留重复 Header 和原始顺序。
- 支持 `v`、`f`、`t`、`i`、`m`、`l` 等紧凑名称的等价查询。
- 保留未知 Header。
- 拒绝包含 CR/LF 的名称或值，避免报文注入。

Via、From、To、CSeq 等 Header 的强类型值解析将在事务层实现前增加；本阶段先保证原始语义无损保存，避免过早固化全部 Header 语法。

### Parser

Parser 接收一条已经确定边界的完整报文：

```java
SipMessage parse(byte[] data) throws SipParseException;
```

支持：

- Request-Line 和 Status-Line。
- CRLF 行结束符及 Header/Body 空行边界。
- 重复 Header。
- Header 折行。
- 紧凑 Header。
- 未知 Method 和 Header。
- 空 Body 和二进制 Body。
- `Content-Length` 字节长度校验。
- start-line、Header 区和 Body 的可配置大小限制。
- 带字节位置的解析异常。

如果完整报文没有 `Content-Length`，Parser 将空行后的全部剩余字节视为 Body。若存在 `Content-Length`，实际 Body 长度必须与其一致。

TCP 半包、粘包和一条字节流中的多条 SIP 消息不属于本阶段，由后续 Netty stream decoder 根据 Header 结束位置和 `Content-Length` 完成分帧。

### Encoder

Encoder 输出规范 CRLF 报文：

```java
byte[] encode(SipMessage message);
```

编码时根据 Body 实际字节数生成或修正 `Content-Length`。重复、未知 Header 继续保留，已有多个 `Content-Length` 只输出一个规范值。

## 测试范围

第一批测试覆盖：

- OPTIONS、REGISTER、INVITE 请求。
- 100、180、200、404 响应。
- Header 大小写无关查询。
- 多个 Via 的顺序。
- 紧凑 Header 查询。
- 未知 Method 和未知 Header。
- Header 折行。
- UTF-8/二进制 Body 的字节长度。
- Content-Length 不匹配。
- 非法 start-line 和 Header。
- `parse(encode(message))` 语义等价。

## 验收标准

- `mvn test` 在 JDK 21 下通过。
- 消息与 codec 代码不依赖网络框架。
- 至少覆盖七类典型 SIP 请求/响应。
- 未知 Method、未知 Header、重复 Header、紧凑 Header 和二进制 Body 不丢失。
- 编码结果始终使用正确的 `Content-Length`。
- 解析错误包含足以定位输入问题的信息。

## 后续里程碑

本阶段完成后，下一步实现 Netty UDP Transport，并完成一个暂不依赖事务层的 OPTIONS 收发闭环。之后再引入 Mailbox、Timer 和 RFC 3261 Transaction 状态机。
