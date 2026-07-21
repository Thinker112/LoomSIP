# 第七阶段：SIP 订阅与事件框架

## 1. 背景

第六阶段已经完成 Digest、PRACK/100rel、UPDATE、Session Timer、INFO 及 UDP/TCP/TLS 场景验收。REFER 的 Method 和通用 Dialog 内请求通路已经存在，但完整 REFER 依赖 `refer` event package 的 SUBSCRIBE/NOTIFY 语义。

本阶段建立通用 Subscription 框架。目标不是仅透传 `SUBSCRIBE` 与 `NOTIFY`，而是定义可独立于 Dialog 生命周期存在的订阅状态、过期 Timer、事件分派和关闭收敛规则。

实施状态：7A～7B 已完成，7C 已完成 Subscription Mailbox 的 NOTIFY 生命周期入口（2026-07-21）；真实 Transaction 路由及 7D～7G 待执行。

## 2. 并发与所有权

```text
SUBSCRIBE response / NOTIFY / Expires timer / close
                         |
                         v
              Subscription Mailbox (one subscription)
                 |              |              |
                 v              v              v
             state update    Timer reset    TU callback

Dialog Mailbox -----------------> Dialog CSeq / Route / identity
Transaction Mailbox ------------> one SUBSCRIBE or NOTIFY attempt
Subscription Mailbox -----------> subscription semantics and lifecycle
```

- Netty 仅负责 UDP/TCP/TLS I/O；协议和 TU 回调继续在虚拟线程执行。
- Transaction Mailbox 只拥有单次 SUBSCRIBE 或 NOTIFY 的 RFC 3261 状态机。
- Dialog Mailbox 只拥有 Dialog 的 Call-ID、Tag、Route Set、Remote Target 和 in-dialog CSeq。
- Subscription Mailbox 串行化订阅状态、NOTIFY、Expires Timer、取消、终止和关闭；不得直接并发修改 Dialog 状态。
- Dialog 内 Subscription 可引用 Dialog；Dialog 外 Subscription 不依赖 Dialog，因此公开 API 以 `SubscriptionClient` / `SubscriptionHandle` 为中心，而不是只挂在 `DialogHandle`。

## 3. 身份与状态

Subscription identity 由以下规范化字段组成：

```text
Call-ID + local tag + remote tag + Event package + optional event-id
```

其中 local/remote tag 按本端角色解释，Event package 与可选 `id` 参数区分同一 Dialog 内的不同订阅。

初版状态机：

```text
NEW -> PENDING -> ACTIVE -> TERMINATED
                 |             |
                 +-------------+
                    refresh
```

- UAC 在成功 SUBSCRIBE 响应及合法 NOTIFY 后推进状态。
- UAS 在接受 SUBSCRIBE 后创建或刷新 Subscription，并控制初始与最终 NOTIFY。
- `Subscription-State: terminated`、`Expires: 0`、Expires Timer、Transport failure 或 Stack close 进入终态。
- 终止是单向的；迟到 NOTIFY、Timer 或异步 Handler completion 不得复活 Subscription。

## 4. 实施拆分

```text
7A  事件模型与 Header
 |
 v
7B  Subscription 状态机与 Mailbox
 |
 +--> 7C  UAC SUBSCRIBE / NOTIFY 路由
 |
 +--> 7D  UAS Dispatcher 与发布
 |
 v
7E  刷新、取消、终止与关闭竞争
 |
 v
7F  REFER event package
 |
 v
7G  UDP/TCP/TLS 汇总验收
```

### 4.1 7A：事件模型与 Header

- 已增加 `Event`、`Allow-Events`、`Subscription-State`、`Expires` 的不可变结构化值对象与 `SipHeaderValues` 解析入口。
- 已支持 Event package token、可选 event-id、Subscription-State reason、expires 和 retry-after 参数，以及多行 `Allow-Events` 合并与规范化渲染。
- 已验证重复参数、重复 capability、负 Expires、非法 interval 和不合法 state/parameter 组合被拒绝；未知 Event package 仅作为 token 保留，不在 Header 层解释业务语义。

### 4.2 7B：Subscription 状态机与 Mailbox

- 已实现 `SubscriptionId`、`SubscriptionLifecycleState`、`SubscriptionHandle`、不可变 Snapshot 和有界 `SubscriptionManager`。
- 每个 Subscription 使用独立 Serial Mailbox；当前 Manager 负责容量、状态转换和终止清理，Expires Timer 与 TU callback 资源在后续阶段接入。
- 已定义 Pending、Active、Terminated 和远端终止、本地取消、超时、传输失败、Manager close 等终止原因。
- 已验证相同 identity 去重、Pending 到 Active、终止后移除，以及 Manager close 终止 pending Subscription 并拒绝后续创建。

### 4.3 7C：UAC SUBSCRIBE 与入站 NOTIFY

- 已在 `SubscriptionManager` 增加已关联 NOTIFY 的生命周期入口：pending/active 更新对应 Subscription Snapshot，`terminated` 单向清理 identity；已移除 identity 的迟到 NOTIFY 不会隐式重建 Subscription。
- 已增加 `SubscriptionNotifyRouter`：从入站 NOTIFY 的 Call-ID、From/To tag 和 Event 派生本地 identity，校验 Subscription-State，并选择 `200`、`400` 或未知 Subscription 的 `481` 响应。Transaction listener 接线仍待实现。
- `SubscriptionClient.subscribe(...)` 创建初始 Transaction；Dialog 内调用通过 Dialog Mailbox 分配 CSeq 和 Route。
- 处理 SUBSCRIBE 2xx、失败响应、refresh 与 `Expires: 0` 取消。
- 按 Subscription ID 路由 NOTIFY，先验证 Event package、Dialog 关联与 CSeq，再向 TU 分发事件 Body。
- 未匹配 NOTIFY 返回 `481 Call/Transaction Does Not Exist`；不支持 Event package 使用明确 SIP 错误响应。

### 4.4 7D：UAS Dispatcher 与事件发布

- `SubscriptionDispatcher` 按规范化 Event package 注册线程安全 `SubscriptionHandler`。
- Handler 决定接受的 Expires、初始 NOTIFY、后续事件发布与终止。
- UAS Expires Timer 到期时发送最终 NOTIFY：`Subscription-State: terminated;reason=timeout`。
- Handler 异常、空 completion 或响应构造失败转换为明确 SIP 错误，日志不得记录敏感 Event Body。

### 4.5 7E：刷新、取消、终止与关闭

- 相同 identity 的 SUBSCRIBE refresh 只更新 Expires，不创建重复 Subscription。
- `Expires: 0` 发起取消；UAS 发送最终 NOTIFY 并清理状态。
- 覆盖 NOTIFY terminated、Timer、transport failure、异步 Handler completion 与关闭竞争。
- 验证 Dialog、Transaction、Subscription 三类 Mailbox 的所有权边界及单终态不变量。

### 4.6 7F：REFER event package

- 在通用 Subscription 框架之上实现 `refer` package。
- 支持 `Refer-To`、`Refer-Sub`、`message/sipfrag` NOTIFY 和 refer subscription 终止。
- 不在本阶段实现 Proxy、B2BUA 或业务侧呼叫转移策略。

### 4.7 7G：跨 Transport 验收

| 场景 | UDP | TCP | TLS |
| --- | --- | --- | --- |
| SUBSCRIBE 2xx + 初始 NOTIFY | 完整 | 完整 | 完整 |
| refresh / Expires: 0 | 完整 | 完整 | 完整 |
| NOTIFY terminated | 完整 | 完整 | 完整 |
| UDP Timer 重传 | 完整 | 不适用 | 不适用 |
| REFER event package | 完整 | 完整 | 完整 |
| close / 迟到事件 | 一条确定性场景 | 不重复 | 不重复 |

## 5. 错误与安全边界

- 不支持的 `Event` package 按 RFC 3265 返回适当的能力或错误语义；具体状态码以实现时 RFC 校验为准。
- 无法关联的 NOTIFY 返回 `481`，不得创建隐式 Subscription。
- Expires、Subscription-State 和 event-id 的格式错误在进入 TU 前被拒绝。
- Subscription Event Body 属于业务数据；诊断只记录 package、Subscription ID 摘要、状态与错误类别，不记录完整 Body 或认证 Header。
- 初版不实现 Presence、Reg、Message Summary 等具体 event package 业务语义，框架仅提供注册和分派能力。

## 6. 非目标

- RFC 3265 之外的完整 Presence、Reginfo、MWI 业务模型。
- SIP Proxy、Registrar、B2BUA 或第三方呼叫控制。
- RFC 3263 DNS、WebSocket、JAIN-SIP Adapter、统一 Stack API。
- 多节点 Subscription 复制、持久化恢复和跨进程事件分发。
