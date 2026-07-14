# LoomSIP 初步架构设计

## 1. 项目目标

LoomSIP 计划基于 JDK 21 重写一个现代 SIP（Session Initiation Protocol）协议栈，核心目标如下：

- 遵循 RFC 3261 的协议分层和事务语义。
- 使用 Netty 实现 UDP、TCP 和 TLS 网络传输。
- 使用 Java 21 虚拟线程承载协议处理和业务调用。
- 将网络 I/O、事务状态机、Dialog 状态和业务层相互解耦。
- 优先保证协议正确性、可测试性和可维护性，再考虑零拷贝等性能优化。

核心并发原则是：Netty 负责高效 I/O，虚拟线程负责易于理解的顺序化协议逻辑。项目不强制引入 Reactor 等响应式框架。

## 2. 总体分层

```text
+-------------------------------------------------------+
|       TU (Transaction User) - 应用层 / 业务逻辑       |
+-------------------------------------------------------+
|       Dialog Layer - Call-ID、Tag、CSeq、Route Set     |
+-------------------------------------------------------+
| Transaction Layer - ICT / IST / NICT / NIST 状态机    |
+-------------------------------------------------------+
|       Message Codec - SIP 消息解析与编码              |
+-------------------------------------------------------+
|       Transport Layer - Netty UDP / TCP / TLS         |
+-------------------------------------------------------+
```

各层职责：

- **Transport Layer**：连接与端口管理、收发字节流、TCP 消息分帧、TLS 会话管理。
- **Message Codec**：将字节数据解析为 SIP 消息，并将 SIP 消息编码为网络数据。
- **Transaction Layer**：实现 RFC 3261 客户端和服务端事务状态机、重传及超时。
- **Dialog Layer**：管理 Dialog 生命周期、Route Set、Remote Target、Local/Remote CSeq。
- **TU**：Registrar、Proxy、UAC、UAS 或其他上层业务实现。

## 3. 初期代码组织

项目早期建议保持单 Maven module，通过 package 明确边界，待接口稳定后再考虑拆分 module：

```text
org.loomsip
  api           面向上层业务的公共 API
  message       Request、Response、URI、Header 等模型
  codec         SIP parser、encoder 和 TCP framing
  transport     UDP、TCP、TLS 传输及连接管理
  transaction   SIP 事务、状态机和事务仓库
  dialog        Dialog 模型、状态管理和仓库
  concurrent    Mailbox、调度和事件派发
  stack         配置、组件装配和生命周期管理
```

后续可按实际依赖关系拆分：

```text
loomsip-core
loomsip-transport-netty
loomsip-testkit
loomsip-examples
```

## 4. 并发与线程模型

### 4.1 基本原则

- Netty EventLoop 只负责 I/O、分帧、编解码和快速派发。
- EventLoop 上不执行业务逻辑，也不执行可能阻塞的操作。
- 同一个 Transaction 的事件必须严格串行处理。
- 同一个 Dialog 的状态变更必须严格串行处理。
- 不同 Transaction 或 Dialog 之间允许并发。
- 定时器回调不能直接修改状态，必须投递为状态机事件。
- 上层业务可以使用阻塞式 API，但阻塞应发生在虚拟线程中。

### 4.2 Mailbox

Mailbox 是组件专属的 FIFO 事件队列，用来建立状态修改的串行化边界。多个线程可以同时投递事件，但同一时刻只有一个消费者处理队列。

```text
Netty EventLoop ----+
                    |
Timer Callback -----+--> Transaction Mailbox --> Virtual Thread --> State Machine
                    |
TU / Application ---+
```

Mailbox 不绑定永久存活的虚拟线程：

1. 队列为空时，不占用线程。
2. 第一个事件入队时，启动一个虚拟线程。
3. 虚拟线程顺序消费当前队列。
4. 队列消费完毕后，虚拟线程结束。
5. 后续有新事件时，再启动新的虚拟线程。

参考接口：

```java
public interface Mailbox {
    void submit(Runnable event);
}
```

初期采用“每个 Transaction 一个 mailbox、每个 Dialog 一个 mailbox”的粒度。Transaction 需要更新 Dialog 时，将更新操作作为事件投递到 Dialog mailbox，而不是跨线程直接修改 Dialog 状态。

### 4.3 事件路由标识

收到 SIP 消息后，Dispatcher 根据协议标识查找目标对象：

- Transaction：根据 top Via branch、sent-by、method 及 RFC 匹配规则定位。
- Dialog：根据 Call-ID、local tag、remote tag 定位。
- 新请求：没有匹配事务时，创建 Server Transaction 并通知 TU。
- 新响应：匹配 Client Transaction 后投递响应事件。

必须特别处理 ACK 和 CANCEL：

- 非 2xx 最终响应的 ACK 属于 INVITE Transaction 的处理范围。
- 2xx 响应的 ACK 由 TU/Dialog 层生成和处理。
- CANCEL 自身是独立事务，但需要关联原 INVITE Transaction。

## 5. SIP 消息模型

SIP 消息对象原则上保持不可变：

```java
public sealed interface SipMessage permits SipRequest, SipResponse {
    SipVersion version();
    SipHeaders headers();
    byte[] body();
}

public record SipRequest(
        SipMethod method,
        SipUri requestUri,
        SipVersion version,
        SipHeaders headers,
        byte[] body
) implements SipMessage {
}
```

设计要求：

- 常用 Header 提供强类型表示，例如 Via、From、To、Call-ID、CSeq、Contact。
- 未识别的扩展 Header 必须能够解析、保存并重新编码。
- Header 名称匹配不区分大小写。
- 支持 RFC 3261 紧凑 Header，例如 `v`、`f`、`t`、`i`。
- Header 顺序和同名多值语义不能丢失。
- Body 由 `Content-Length` 确定边界，不假设其一定为文本。
- SIP 核心模型不暴露 Netty `ByteBuf`，明确网络缓冲区的所有权边界。

第一阶段不追求端到端零拷贝。SIP 消息通常较小，优先避免 `ByteBuf` 引用计数泄漏和跨线程访问问题。

## 6. Codec 与网络分帧

UDP 数据报天然具有消息边界，可以对单个 Datagram 直接解析。

TCP/TLS 是字节流，必须按以下信息判断完整消息：

1. 解析 start-line 和全部 Header，直到空行。
2. 获取 `Content-Length`，没有该 Header 时按 RFC 规则处理。
3. 等待完整 Body 到达。
4. 从同一连接中继续解析后续 SIP 消息。

Parser 需要支持：

- 网络粘包和半包。
- CRLF 行结束符。
- 合法的多值 Header。
- 未知扩展 method 和 Header。
- 明确的大小限制，防止超大 start-line、Header 或 Body 消耗内存。
- 返回可定位的解析错误，而不是只抛出通用异常。

## 7. Transaction Layer

事务层显式实现四类 RFC 3261 状态机：

- Invite Client Transaction（ICT）
- Invite Server Transaction（IST）
- Non-Invite Client Transaction（NICT）
- Non-Invite Server Transaction（NIST）

参考接口：

```java
public interface Transaction {
    TransactionId id();

    TransactionState state();

    void onEvent(TransactionEvent event);
}
```

状态机只接收事件，不由任意线程直接调用内部状态转换方法。事件可以包括：

```text
RequestReceived
ResponseReceived
TransportSucceeded
TransportFailed
TimerExpired
ApplicationRequest
ApplicationResponse
CancelRequested
```

实现时应避免将状态转换散落在大量条件判断中。每次转换至少明确：

- 当前状态。
- 输入事件。
- 下一状态。
- 需要发送的消息。
- 需要启动或取消的 Timer。
- 是否通知 TU。
- 是否销毁 Transaction。

可靠传输与不可靠传输要区分处理。UDP 需要按 RFC 执行重传；TCP/TLS 不执行相同的消息重传逻辑，但仍需保留事务总超时和连接失败处理。

## 8. Dialog Layer

Dialog 负责维护跨 Transaction 的会话状态，包括：

- Call-ID。
- Local Tag 和 Remote Tag。
- Local URI 和 Remote URI。
- Local CSeq 和 Remote CSeq。
- Route Set。
- Remote Target。
- Early、Confirmed、Terminated 状态。

Dialog 不替代 Transaction。一个 Dialog 生命周期内可以产生多个 Transaction，例如 INVITE、UPDATE、BYE 和 INFO。

Dialog ID 的 local/remote tag 方向取决于本端角色，不能只按报文字段顺序简单拼接。

## 9. Timer 与时间抽象

SIP 事务依赖 Timer A、B、D、E、F、G、H、I、J、K。状态机不应直接依赖系统时钟，统一通过调度接口管理：

```java
public interface SipScheduler {
    Cancellable schedule(Duration delay, Runnable task);
}
```

生产实现可以基于 `ScheduledExecutorService` 或 Netty 定时器。Timer 到期后只负责向目标 mailbox 投递 `TimerExpired` 事件。

测试环境应提供虚拟时钟和可控调度器，使几十秒的协议超时测试可以立即完成，并且不依赖真实时间和线程调度速度。

## 10. Transport Layer

Transport 层对上提供统一抽象，不把 Netty Channel 直接暴露给协议层：

```java
public interface SipTransport {
    CompletionStage<SendResult> send(SipMessage message, TransportTarget target);
}
```

TransportTarget 至少包含：

- 目标地址和端口。
- UDP、TCP 或 TLS 类型。
- 可选的已有连接标识。

Transport 层负责：

- UDP 监听及发送。
- TCP/TLS 连接建立、复用、关闭及失败通知。
- 消息编码和网络写入。
- 本地监听地址管理。
- 合理的连接、消息和缓冲区上限。
- 将传输成功或失败转换为 Transaction 事件。

DNS NAPTR/SRV、RFC 3263 服务器选择和连接保活可以后续增加。

## 11. 面向 TU 的 API

核心 API 不暴露 Netty 类型，也不要求上层了解 mailbox：

```java
public interface SipStack extends AutoCloseable {
    void start();

    ClientTransaction sendRequest(
            SipRequest request,
            ClientTransactionListener listener
    );

    void sendResponse(
            ServerTransaction transaction,
            SipResponse response
    );

    void addListener(SipListener listener);

    @Override
    void close();
}
```

一个请求可能依次收到多个临时响应和最终响应，因此 `sendRequest` 不应只返回单个 `CompletionStage<SipResponse>`。第一版优先采用 listener/event API，接口稳定后再评估是否提供 JDK `Flow.Publisher` 适配器。

业务回调默认运行在虚拟线程上，避免阻塞 Netty EventLoop。需要为回调异常、执行超时和应用关闭定义清晰行为。

## 12. 测试策略

协议栈测试应分为以下层次：

- **Codec 单元测试**：RFC 示例报文、边界输入、扩展 Header、非法报文。
- **状态机测试**：覆盖每个状态和事件组合，使用虚拟时钟验证 Timer。
- **传输测试**：UDP 收发、TCP 半包/粘包、连接断开和 TLS 握手失败。
- **协议场景测试**：UAC 与 UAS 完成 INVITE、ACK、BYE、CANCEL 等完整流程。
- **互操作测试**：后续与常见 SIP Server 或测试工具进行报文互通。

`loomsip-testkit` 后续可以提供内存传输、虚拟时钟、报文断言和场景驱动器，避免集成测试全部依赖真实端口和等待时间。

## 13. 分阶段实现计划

### 阶段一：协议基础

- SIP URI、Header、Request、Response 模型。
- Parser 和 Encoder。
- UDP Transport。
- 基础配置和 Stack 生命周期。

### 阶段二：事务层

- Transaction ID 和仓库。
- Mailbox 与事件 Dispatcher。
- NICT、NIST、ICT、IST 状态机。
- Timer 抽象和虚拟时钟测试。
- ACK、CANCEL 的事务关联规则。

### 阶段三：Dialog 与基本呼叫

- Early 和 Confirmed Dialog。
- Route Set、Remote Target、CSeq 管理。
- 简单 UAC/UAS 示例。
- INVITE、ACK、BYE、CANCEL 完整场景测试。

### 阶段四：可靠传输与安全

- TCP 分帧、连接复用和失败处理。
- TLS Transport。
- 消息及连接资源限制。
- Digest Authentication。

### 阶段五：扩展能力

- RFC 3263 DNS 解析和目标选择。
- Registrar、Proxy 等示例或独立组件。
- WebSocket Transport。
- 监控指标、链路追踪和诊断能力。

## 14. 初步设计决策

当前讨论形成以下初步结论：

1. SIP 消息模型采用不可变设计。
2. Transaction 使用显式事件驱动状态机。
3. 每个 Transaction 和 Dialog 通过 mailbox 保证内部状态串行修改。
4. Mailbox 按需启动虚拟线程，不为长生命周期对象永久占用线程。
5. Netty EventLoop 不执行业务逻辑和阻塞操作。
6. Timer 通过统一调度接口产生事件，不直接修改状态。
7. 核心层不引入 Reactor，不暴露 Netty 类型。
8. 初期保持单 Maven module，接口稳定后再拆分。
9. 第一阶段优先实现 UDP 和 RFC 3261 核心流程。
10. 正确性和可测试性优先于零拷贝等提前优化。

## 15. 待进一步讨论

- 状态机使用普通 Java 类、状态模式，还是表驱动方式实现。
- Mailbox 的背压、队列上限、异常隔离和关闭语义。
- Transaction 与 Dialog 事件交互中的顺序和失败处理。
- Parser 对原始 Header 格式的保留程度。
- 公共 API 中 Builder、不可变集合和 Body 表示方式。
- Stack 多实例、多个监听地址以及多租户隔离方式。
- 可观测性接口，包括日志上下文、指标和报文追踪。

以上内容作为初步架构基线，后续在实现每个阶段前通过小型 ADR（Architecture Decision Record）记录重要选择及其取舍。
