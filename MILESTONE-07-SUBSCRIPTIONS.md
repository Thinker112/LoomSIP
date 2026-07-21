# 第七阶段：SIP 订阅与事件框架

## 1. 背景

第六阶段已经完成 Digest、PRACK/100rel、UPDATE、Session Timer、INFO 及 UDP/TCP/TLS 场景验收。REFER 的 Method 和通用 Dialog 内请求通路已经存在，但完整 REFER 依赖 `refer` event package 的 SUBSCRIBE/NOTIFY 语义。

本阶段建立通用 Subscription 框架。目标不是仅透传 `SUBSCRIBE` 与 `NOTIFY`，而是定义可独立于 Dialog 生命周期存在的订阅状态、过期 Timer、事件分派和关闭收敛规则。

实施状态：第七阶段已完成（7A～7G，2026-07-21）。

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
- 已增加 `SubscriptionNotifyRouter`：从入站 NOTIFY 的 Call-ID、From/To tag 和 Event 派生本地 identity，校验 Subscription-State，并选择 `200`、`400` 或未知 Subscription 的 `481` 响应。
- 已增加 `SubscriptionNotifyServerListener`，可作为 NIST Server Listener adapter 接入 Transaction 层；仅截获 NOTIFY 并在协议 callback executor 上发送 Router 响应，其他 Non-INVITE Method 原样委派下游。
- 已增加 `SubscriptionSubscribeResponseRouter` 与 NICT Client Listener adapter：成功初始 SUBSCRIBE 响应使用请求 From tag、响应 To tag 和 Event 创建 pending UAC Subscription；provisional/non-2xx 响应不创建，缺失远端 To tag 的 2xx 作为关联失败处理。
- 已增加 `InitialSubscriptionRequest`、`SubscriptionRequestProfile` 与 `SubscriptionClient`，可构建并启动初始 out-of-dialog SUBSCRIBE NICT；Client 受管 Via、From、To、Call-ID、CSeq、Event、Expires，拒绝调用方覆盖。Dialog 内 SUBSCRIBE 的 CSeq/Route 接入留待后续扩展。
- `SubscriptionClient.subscribe(...)` 创建初始 Transaction；Dialog 内调用通过 Dialog Mailbox 分配 CSeq 和 Route。
- 处理 SUBSCRIBE 2xx、失败响应、refresh 与 `Expires: 0` 取消。
- 按 Subscription ID 路由 NOTIFY，先验证 Event package、Dialog 关联与 CSeq，再向 TU 分发事件 Body。
- 未匹配 NOTIFY 返回 `481 Call/Transaction Does Not Exist`；不支持 Event package 使用明确 SIP 错误响应。

### 4.4 7D：UAS Dispatcher 与事件发布

- 已实现线程安全 `SubscriptionDispatcher`，按规范化 Event package 注册 `SubscriptionHandler`，并解析 SUBSCRIBE 的 Event/Expires 后选择 Handler；未知 package 保持未匹配，由后续 Server Listener 决定 SIP 错误响应。
- 已实现 `SubscriptionSubscribeServerListener`：作为 NIST adapter 解析并调用 Handler，未知 Event 返回 `489 Bad Event`、非法 Header 返回 `400`；Handler 的 2xx 决策创建 UAS pending Subscription，并发送带本地 To tag 与协商 Expires 的最终响应。
- 已实现 `SubscriptionNotification` 与 `SubscriptionPublisher`，可使用 existing Subscription ID 构建并启动 UAS NOTIFY NICT；Publisher 受管 Via、From/To tag、Call-ID、CSeq、Event、Subscription-State，适合作为初始或后续事件发布的发送边界。
- Handler 决定接受的 Expires、初始 NOTIFY、后续事件发布与终止。
- UAS Expires Timer 到期时发送最终 NOTIFY：`Subscription-State: terminated;reason=timeout`。
- Handler 异常、空 completion 或响应构造失败转换为明确 SIP 错误，日志不得记录敏感 Event Body。

### 4.5 7E：刷新、取消、终止与关闭

- 已实现带 To tag 的 UAS SUBSCRIBE refresh：按既有 `SubscriptionId` 更新 Expires、复用 To tag，并且不重新调用 Event package Handler 或创建第二个 Subscription。
- 接受初始 SUBSCRIBE 时，Listener 会先成功注册 Expires Timer，再发送 2xx；缺少 Scheduler 等 setup 失败会清理刚创建的 Subscription 并回复 500，避免残留 pending identity。
- 已增加 `SubscriptionTerminationListener`：Manager 在同一 Subscription Mailbox 内完成 identity 删除后，至多一次地交付不可变终态 Snapshot。该钩子不拥有路由，也不得阻塞或重入状态机。
- 已增加 `SubscriptionFinalNotifier` 与 `SubscriptionFinalNotification`：UAS 集成层显式登记 Contact 路由、目标 Transport 和受控 NOTIFY CSeq；本地 `Expires: 0` 转换为 `terminated;reason=deactivated`，Timer 到期转换为 `terminated;reason=timeout`。远端终止、setup 失败和 Stack close 只释放上下文，不发送额外 NOTIFY。
- `SubscriptionSubscribeServerListener` 可选接收 `SubscriptionFinalNotifier` 与 `SubscriptionFinalNotificationFactory`。工厂从已接受初始 SUBSCRIBE 的请求和 TransportContext 构造最终 NOTIFY 上下文；它们必须成对提供，避免把 Contact 路由或 CSeq 计数错误地下沉到通用状态机。
- 已验证 Timer replacement、Expires: 0、remote terminate、Manager close 及 UDP NICT 重传共存时，最终通知只创建一个唯一 Via branch 的事务。Dialog、Transaction、Subscription 三类 Mailbox 的所有权边界保持不变。

### 4.6 7F：REFER event package

- 已增加 `ReferToHeaderValue` 和 `ReferSubHeaderValue`。`Refer-To` 使用既有结构化 name-addr 解析；`Refer-Sub` 缺失时按照 RFC 4488 默认 `true`，重复或非 boolean 值在 Header 边界拒绝。
- 已增加 `SipfragStatus`：仅接受 CRLF 结尾的 `SIP/2.0 <100-699> <reason>` status line，并可无损生成最小 `message/sipfrag` Body。
- 已增加 `ReferNotifier`。它仅接受无 event-id 的 `Event: refer` Subscription，受管 `Content-Type: message/sipfrag`，并强制 sipfrag 的终态性与 `Subscription-State: terminated` 一致；Via、CSeq、identity 和 NICT 仍由 `SubscriptionPublisher` 管理。
- 已增加 `ReferRequest`、`ReferHandler` 和 `ReferServerListener`。Listener 只截获 REFER，解析失败返回 400，Handler 异常或空结果返回 500，其他 Non-INVITE Method 原样委派。
- Handler 返回 2xx 且 `Refer-Sub: true` 时，Listener 使用 REFER 已有的 To/From tag 创建并激活 `Event: refer` 的隐式 Subscription，提交 2xx 后通过 `ReferSubscriptionListener` 将只读 Handle、Refer-To 和 TransportContext 交给 TU 建立初始通知。`Refer-Sub: false` 只返回 REFER 响应，不创建隐式 Subscription。
- 已增加 `ReferSubscriptionPublisher` 与不可变 `ReferSubscriptionProfile`。每个隐式 refer Subscription 有一个有界发布 Mailbox，拥有 NOTIFY CSeq 与一次终态发布标记；它调用 `ReferNotifier` 启动 NICT，但通过 `SubscriptionManager` 完成生命周期终止。
- 非终态 sipfrag 发送 `Subscription-State: active`；终态 sipfrag 发送 `terminated;reason=noresource`，成功启动后终止 Subscription。终态构造失败以 `TRANSPORT_FAILURE` 清理，Subscription/stack 关闭会关闭发布器并使迟到进度失败。已覆盖排队双终态只启动一个 NOTIFY 的竞态。
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

已实现 7G-A～7G-C：真实 UDP、TCP、TLS 场景覆盖 out-of-dialog `REFER -> 202 Accepted -> 100 Trying NOTIFY -> 200 OK final NOTIFY` 和 `Refer-Sub: false` 仅返回 202、不创建 Subscription/NOTIFY。UAS 为 out-of-dialog REFER 生成 To tag 并建立隐式 Subscription；带 To tag 的 NOTIFY 在 Dialog Bridge 前转交 Subscription/Non-INVITE listener，避免被未知 Dialog 误判为 481。测试在收到初始 sipfrag 后再触发最终业务进度，避免将独立 NICT 的异步连接建立顺序误当作协议保证；另有 UDP 确定性关闭场景验证初始通知后 Stack 关闭会拒绝迟到终态进度且不再发送 NOTIFY。

已实现 7G-D：真实 UDP、TCP、TLS 场景覆盖通用 UAC `SUBSCRIBE -> 202 -> Subscription-State: active NOTIFY -> Subscription-State: terminated NOTIFY`。测试通过 `SubscriptionSubscribeClientListener` 在 2xx 回调前创建 UAC identity，再由 `SubscriptionNotifyServerListener` 返回 200 并驱动 pending/active/terminated Mailbox 转换。UAS 发送 NOTIFY 时使用与 UAC 相反的本地/远端 tag 视角，确保 RFC 3265 identity 关联正确。

已实现 7G-E：新增 `SubscriptionRefreshRequest` 与 `SubscriptionClient.refresh(...)`，以既有 Call-ID、双方 tag 和 Event 构造 in-dialog refresh 或 `Expires: 0` cancellation。成功的带 To tag SUBSCRIBE 响应由 `SubscriptionSubscribeResponseRouter` 识别为 refresh，使用协商 Expires 更新 Timer；真实 UDP、TCP、TLS 场景验证 `Expires: 0` 2xx 后进入 `LOCAL_CANCELLED`，且不依赖额外 NOTIFY。

已实现 7G-F～7G-G：真实 UDP refresh 场景验证 Timer replacement，先后协商 30/60 秒时旧 deadline 不会终止 Subscription，只有最新 deadline 进入 `EXPIRED`；另有 UDP close 场景在 UAC Manager 关闭后发送迟到 NOTIFY，验证返回 `481`、保持 `MANAGER_CLOSED` 终态且不隐式重建 identity。至此 7G 验收矩阵全部覆盖。

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
