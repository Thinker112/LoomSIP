# LoomSIP

LoomSIP 是一个面向 JDK 21 的现代 SIP（Session Initiation Protocol）协议栈，目标是遵循 RFC 3261 的协议分层与事务语义，并通过 Netty 和 Java 虚拟线程建立清晰、可测试的并发模型。

项目当前处于核心协议栈开发阶段。仓库已经实现不可变 SIP 消息模型、Parser/Encoder、Netty UDP Transport、四类 Transaction 状态机、Dialog Layer，以及 INVITE/ACK/re-INVITE/BYE/CANCEL 基本呼叫流程。当前正在实施 TCP/TLS 可靠传输；统一 Stack API、认证和扩展能力仍在后续规划中，因此当前版本暂不适合生产环境使用。

## 设计目标

- 遵循 RFC 3261 的分层结构和事务状态机语义。
- 使用 Netty 承载 UDP、TCP 和 TLS 网络 I/O。
- 使用 Java 21 虚拟线程执行协议处理和上层业务回调。
- 解耦网络传输、消息编解码、Transaction、Dialog 和业务层。
- 保持 SIP 核心模型不可变，不向上层暴露 Netty `ByteBuf`。
- 优先保证协议正确性、资源所有权清晰、可测试性和可维护性。
- 核心层不强制引入 Reactor 等响应式框架。

## 当前进度

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| SIP 消息模型 | 已实现 | Request、Response、URI、Method、Header、Body 等不可变模型 |
| SIP Parser | 已实现 | 支持完整报文、重复/紧凑/未知 Header、扩展 Method、折行与二进制 Body |
| SIP Encoder | 已实现 | 输出规范 CRLF 报文，并根据 Body 修正 `Content-Length` |
| Parser 资源限制 | 已实现 | 可限制 start-line、Header 区和 Body 大小 |
| Netty UDP Transport | 已实现 | UDP 生命周期、编解码、发送失败和真实回环收发 |
| Transaction Layer | 已实现 | ICT、IST、NICT、NIST、虚拟 Timer、ACK/CANCEL 和 RFC 6026 Accepted |
| Dialog Layer | 已实现 | Early/Confirmed Dialog、fork、Route Set、Remote Target、CSeq 和 2xx ACK |
| 基本呼叫 | 已实现 | INVITE、ACK、re-INVITE、BYE、CANCEL 及真实 UDP 完整流程 |
| TCP/TLS Transport | 实施中（5A～5C 已完成） | TCP/TLS 流式分帧、Client/Server、TLS 握手和连接复用已完成；事务失败传播和完整资源限制待 5D～5E |
| Digest Authentication | 后续规划 | 独立认证阶段，不与 Transport 生命周期混合 |
| Stack API | 后续规划 | 统一组件装配、配置和关闭顺序 |

当前 `SipMessageParser` 接收一条已经完成边界识别的 SIP 报文。TCP 半包、粘包以及同一字节流中的多条消息，已经由 Transport stream decoder 完成分帧。

## 目标架构

```text
+-------------------------------------------------------+
|       TU (Transaction User) - 应用层 / 业务逻辑       |
+-------------------------------------------------------+
|       Dialog Layer - Call-ID、Tag、CSeq、Route Set    |
+-------------------------------------------------------+
| Transaction Layer - ICT / IST / NICT / NIST 状态机   |
+-------------------------------------------------------+
|       Message Codec - SIP 消息解析与编码              |
+-------------------------------------------------------+
|       Transport Layer - Netty UDP / TCP / TLS         |
+-------------------------------------------------------+
```

- **Transport Layer**：管理监听端口、连接、收发、TCP/TLS 分帧与传输失败通知。
- **Message Codec**：在网络字节与不可变 SIP 消息之间转换。
- **Transaction Layer**：实现客户端/服务端事务状态机、重传和超时。
- **Dialog Layer**：维护 Dialog 生命周期、Route Set、Remote Target 和 CSeq。
- **TU**：承载 UAC、UAS、Registrar、Proxy 或其他业务逻辑。

## 并发模型

LoomSIP 的基本原则是：Netty 负责高效 I/O，虚拟线程负责易于理解的顺序化协议逻辑。

```text
Netty EventLoop ----+
                    |
Timer Callback -----+--> Transaction/Dialog Mailbox --> Virtual Thread --> State Machine
                    |
TU / Application ---+
```

- Netty EventLoop 只执行 I/O、分帧、编解码和快速派发，不执行业务逻辑或阻塞操作。
- 同一个 Transaction 和同一个 Dialog 内部的事件必须严格串行处理。
- 不同 Transaction 或 Dialog 之间可以并发执行。
- Mailbox 是组件专属的 FIFO 队列，仅在收到事件时按需启动虚拟线程，不长期占用线程。
- Timer 回调只投递状态机事件，不直接修改 Transaction 或 Dialog 状态。
- 上层可以使用阻塞式 API，但阻塞发生在虚拟线程而非 Netty EventLoop。

## 环境要求

- JDK 21
- Maven

确认环境并运行测试：

```shell
java -version
mvn -version
mvn test
```

构建项目：

```shell
mvn clean package
```

项目尚未发布到公共 Maven 仓库。当前应从源码构建和使用。

## Codec 示例

下面的示例解析一条完整的 SIP OPTIONS 请求，再将消息编码回网络字节：

```java
import org.loomsip.codec.SipMessageEncoder;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.message.SipMessage;

import java.nio.charset.StandardCharsets;

String wireMessage =
        "OPTIONS sip:service@example.com SIP/2.0\r\n" +
        "Via: SIP/2.0/UDP client.example.com;branch=z9hG4bK-1234\r\n" +
        "From: <sip:alice@example.com>;tag=alice-1\r\n" +
        "To: <sip:service@example.com>\r\n" +
        "Call-ID: call-1234@example.com\r\n" +
        "CSeq: 1 OPTIONS\r\n" +
        "Max-Forwards: 70\r\n" +
        "Content-Length: 0\r\n" +
        "\r\n";

SipMessageParser parser = new SipMessageParser();
SipMessage message = parser.parse(wireMessage.getBytes(StandardCharsets.UTF_8));

SipMessageEncoder encoder = new SipMessageEncoder();
byte[] encoded = encoder.encode(message);
```

消息模型保留 Header 的原始顺序和重复项；Header 查询不区分大小写，并将 `v`/`Via`、`f`/`From` 等 RFC 3261 紧凑名称视为等价。Body 始终按二进制数据处理，Encoder 会根据实际字节数生成唯一且正确的 `Content-Length`。

## 代码组织

当前采用单 Maven module，通过 package 明确边界：

```text
org.loomsip
  message       Request、Response、URI、Header、Body 等模型
  codec         SIP Parser、Encoder 和解析资源限制
  transport     UDP Transport、Endpoint、网络上下文和生命周期
  transaction   ICT、IST、NICT、NIST、Timer、Dispatcher 和 Repository
  dialog        Dialog、Route Set、CSeq、ACK、re-INVITE 和 BYE
  concurrent    Mailbox 和顺序回调派发
```

随着里程碑推进，计划增加或完善：

```text
org.loomsip
  api           面向上层业务的公共 API
  transport     TCP/TLS 连接管理、复用和资源限制
  stack         配置、组件装配和生命周期管理
  auth          Digest 认证策略和 Credential Provider
  testkit       虚拟网络、场景驱动和协议断言
```

接口稳定后，再评估拆分为 `loomsip-core`、`loomsip-transport-netty`、`loomsip-testkit` 和 `loomsip-examples` 等独立 module。

## 路线图

1. **协议基础（已完成）**：消息模型、Parser、Encoder 和 UDP Transport。
2. **事务层（已完成）**：Transaction ID、Mailbox、Dispatcher、四类状态机、Timer、ACK/CANCEL 关联规则。
3. **Dialog 与基本呼叫（已完成）**：Early/Confirmed Dialog，以及 INVITE、ACK、re-INVITE、BYE、CANCEL 完整流程。
4. **可靠传输（实施中，5A～5C 已完成）**：TCP/TLS 分帧、连接复用、TLS 握手、失败传播和资源限制。
5. **认证与 SIP 扩展**：Digest、PRACK/100rel、UPDATE、Session Timer、REFER 和 INFO。
6. **扩展能力**：RFC 3263 DNS、WebSocket、Registrar/Proxy、测试工具、指标、追踪和诊断能力。

## 文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：总体分层、并发模型、协议边界与完整实施路线。
- [MILESTONE-01-MESSAGE-CODEC.md](MILESTONE-01-MESSAGE-CODEC.md)：消息模型与 Codec 的实现范围和验收标准。
- [MILESTONE-02-NETTY-UDP-TRANSPORT.md](MILESTONE-02-NETTY-UDP-TRANSPORT.md)：Netty UDP Transport 的详细设计。
- [MILESTONE-03-TRANSACTION-LAYER.md](MILESTONE-03-TRANSACTION-LAYER.md)：Transaction、Timer、ACK 和 CANCEL 的详细设计。
- [MILESTONE-04-DIALOG-LAYER.md](MILESTONE-04-DIALOG-LAYER.md)：Dialog、2xx ACK、re-INVITE、BYE 和基本呼叫设计。
- [MILESTONE-05-RELIABLE-TRANSPORT.md](MILESTONE-05-RELIABLE-TRANSPORT.md)：TCP/TLS 分帧、连接复用、安全和资源限制方案。
- [CONTRIBUTING.md](CONTRIBUTING.md)：开发约定与公共 API Javadoc 要求。

## 贡献约定

提交代码前请运行 `mvn test`。新增或修改公共 API 时，应明确不可变性、数据所有权、线程与回调规则、参数约束、异常语义和生命周期限制，并为公共或受保护类型及成员补充契约型 Javadoc。
