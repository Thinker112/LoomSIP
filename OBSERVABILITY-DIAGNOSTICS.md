# LoomSIP 指标监控与诊断方案

## 1. 目标

为 SIP Stack 提供运行期诊断、结构化事件和低基数聚合指标，同时不让监控框架、业务标识或阻塞回调侵入 Transaction、Dialog、Subscription 的状态机和 Mailbox。

## 2. 分层

```text
实时诊断：StackStateSnapshot / 查询 API
      |
事件诊断：SipStackListener / 结构化事件
      |
聚合指标：SipMetrics / 框架 Adapter
```

- 实时诊断回答“当前 Stack 处于什么状态”。
- 事件诊断回答“刚才发生了什么”。
- 聚合指标回答“最近一段时间的趋势和失败率如何”。

## 3. 实时诊断

扩展 `StackStateSnapshot`，但只保存状态、计数和脱敏摘要：

```text
StackStateSnapshot
  |- stack lifecycle state
  |- UDP/TCP/TLS bound endpoints and state
  |- active ICT / IST / NICT / NIST counts
  |- active Dialog and Subscription counts by state
  |- active connection / pending connect / pending write counters
  |- mailbox queue depth / rejection counters
  |- timer counts and recent timeout category
  `- last failure summary
```

快照不得包含完整 SIP Body、Authorization、完整 Call-ID、电话号码或业务 URI。

## 4. 结构化事件

`SipStackListener` 负责异步观测事件；监听器异常必须隔离，不得影响状态机、Transport I/O 或关闭流程。

建议事件：

```text
StackStarted / StackClosed / StackStartFailed
TransportStarted / TransportFailed / TransportClosed
TransactionTimeout / TransactionTransportFailure
DialogCreated / DialogTerminated
SubscriptionCreated / SubscriptionTerminated
AuthenticationRejected
MailboxRejected / CallbackFailed
```

每个事件至少包含：

```text
timestamp
component
event type
transport protocol
local / remote endpoint
transaction/dialog/subscription summary ID
failure class and category
```

协议 identity 使用 hash 前缀或摘要，不直接输出完整 Call-ID、tag、branch 或敏感 Header。

## 5. 聚合指标

核心模块定义小型 `SipMetrics` 抽象，默认 no-op；Micrometer、OpenTelemetry 或其他平台适配器放在独立模块，避免核心依赖特定监控实现。

```java
public interface SipMetrics {
    void increment(String metric, Tags tags);
    void record(Duration duration, String metric, Tags tags);
    void gauge(String metric, Supplier<Number> value);
}
```

第一批指标：

| 分类 | 建议指标 |
| --- | --- |
| 生命周期 | `loomsip.stack.starts`、`loomsip.stack.start.failures`、`loomsip.stack.closes` |
| Transport | `loomsip.transport.messages.in/out`、`parse.failures`、`send.failures`、`connections.active`、`writes.pending` |
| Transaction | `loomsip.transactions.active`、`created`、`timeouts`、`retransmissions`、`transport.failures` |
| Dialog | `loomsip.dialogs.active`、`created`、`terminated`、`ack.failures` |
| Subscription | `loomsip.subscriptions.active`、`subscribe.accepted/rejected`、`notify.received`、`notify.unmatched` |
| 执行模型 | `loomsip.mailbox.rejected`、`loomsip.callback.failures`、`loomsip.callback.queue.depth` |

推荐标签：

```text
transport=udp|tcp|tls
transaction_type=ict|ist|nict|nist
method=INVITE|BYE|INFO|SUBSCRIBE|NOTIFY|REFER
outcome=success|timeout|transport_failure|protocol_error
status_class=2xx|4xx|5xx|6xx
```

禁止将 Call-ID、URI、电话号码、IP:port、branch、tag、错误消息作为 metrics label，避免高基数时序数据。

## 6. 追踪

初版不直接引入完整 OpenTelemetry 自动埋点。先定义 `SipTraceContext` 或 `SipOperationObserver`，在以下边界创建 span 或事件：

```text
inbound Transport receive
Transaction created / final response / timeout
Dialog created / terminated
outbound send completion
Subscription state transition
```

Trace 使用脱敏 correlation ID。业务自定义 Trace Header 的映射由应用 Adapter 完成，核心不规定业务 Header。

## 7. 实施顺序

1. 扩展 Stack Snapshot，补 Dialog、Subscription、Mailbox、Connection 计数。
2. 将 Stack Listener 扩展为结构化事件，并隔离监听器异常。
3. 引入无依赖 `SipMetrics` 和 no-op 默认实现。
4. 在 Transport、Transaction、Dialog、Subscription 的创建、终止、失败边界埋点。
5. 提供 Micrometer 与 OpenTelemetry Adapter 独立模块。

## 8. 非目标

- 在协议状态机中直接写业务日志或绑定监控 SDK。
- 以 metrics label 承载高基数 SIP identity。
- 默认记录完整 SIP Body、认证凭据或业务标识。
- 用同步 listener 阻塞 Netty EventLoop、Mailbox drain 或 Stack close。
