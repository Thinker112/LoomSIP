# 里程碑 02：Netty UDP Transport

## 1. 目标

第二阶段实现基于 Netty 的 UDP Transport，并完成两个 LoomSIP Endpoint 之间的 OPTIONS 请求与 `200 OK` 响应闭环。

本阶段建立以下执行边界：

- Netty EventLoop 负责 UDP 网络 I/O、报文大小检查、编解码和快速派发。
- 虚拟线程负责调用上层消息处理逻辑。
- SIP 核心模型不暴露 Netty 类型。
- Transport 只负责发送和接收单条 SIP 消息，不负责事务关联、重传或超时。

```text
UDP Datagram
    ↓
Netty NioDatagramChannel
    ↓
复制 ByteBuf 数据
    ↓
SipMessageParser
    ↓
InboundSipMessage
    ↓
Virtual Thread Executor
    ↓
SipMessageHandler
    ↓
SipMessageEncoder
    ↓
Netty UDP Send
```

## 2. 阶段边界

### 本阶段实现

- UDP 端口绑定和关闭。
- SIP UDP Datagram 接收和发送。
- 入站网络上下文保存。
- Parser/Encoder 与 Netty Pipeline 集成。
- Netty EventLoop 到虚拟线程的执行切换。
- OPTIONS 请求和 `200 OK` 响应示例。
- UDP Transport 单元测试及真实回环集成测试。
- 解析失败、发送失败和生命周期错误处理。

### 本阶段不实现

- Client Transaction 和 Server Transaction。
- UDP 重传与 SIP Timer。
- 请求与响应关联。
- INVITE、ACK、CANCEL 的事务规则。
- Dialog。
- TCP、TLS 和 WebSocket Transport。
- DNS NAPTR/SRV 和 Transport Selection。
- JAIN-SIP API 兼容层。

JAIN-SIP 兼容后续通过独立 Adapter module 实现，不让 `javax.sip` 类型进入 LoomSIP 核心。

## 3. 代码组织

继续保持单 Maven module，通过 package 建立边界：

```text
org.loomsip.transport
  SipTransport
  TransportProtocol
  TransportEndpoint
  TransportContext
  InboundSipMessage
  SipMessageHandler
  SendResult
  TransportException

org.loomsip.transport.netty
  NettyUdpTransport
  UdpTransportConfig
  SipDatagramHandler

org.loomsip.message
  SipResponses

org.loomsip.example
  OptionsClient
  OptionsServer
```

协议核心继续不依赖 Netty。只有 `transport.netty` package 使用 `ByteBuf`、`DatagramPacket`、`Channel` 和 `EventLoopGroup`。

## 4. Transport 公共模型

### 4.1 传输协议

```java
public enum TransportProtocol {
    UDP,
    TCP,
    TLS
}
```

第二阶段只实现 UDP，但公共枚举为后续传输实现保留稳定类型。

### 4.2 Endpoint

```java
public record TransportEndpoint(
        TransportProtocol protocol,
        InetSocketAddress address
) {
}
```

Endpoint 表示一个本地监听地址或发送目标。构造时需要校验协议和地址不为空。

### 4.3 网络上下文

```java
public record TransportContext(
        TransportProtocol protocol,
        InetSocketAddress localAddress,
        InetSocketAddress remoteAddress
) {
}
```

入站消息必须携带本地和远端地址。后续处理 Via `received`、`rport`、NAT、响应路由和连接复用时都需要这些信息。

### 4.4 入站消息

```java
public record InboundSipMessage(
        SipMessage message,
        TransportContext context
) {
}
```

`InboundSipMessage` 只包含不可变 SIP 对象和 JDK 网络类型，不包含 Netty Channel 或 ByteBuf。

## 5. Transport 接口

```java
public interface SipTransport extends AutoCloseable {

    void start() throws TransportException;

    CompletionStage<SendResult> send(
            SipMessage message,
            TransportEndpoint target
    );

    TransportEndpoint localEndpoint();

    @Override
    void close();
}
```

`send` 只表示一次网络发送，不等待 SIP 响应。

不能在 Transport 中定义：

```java
CompletionStage<SipResponse> sendRequest(...);
```

等待响应、关联 Transaction、重传和超时属于后续 Transaction Layer。

### 5.1 发送结果

```java
public record SendResult(
        TransportEndpoint localEndpoint,
        TransportEndpoint remoteEndpoint,
        int encodedBytes
) {
}
```

Netty `ChannelFuture` 完成后转换为 `CompletionStage<SendResult>`。写入失败时以异常完成，不能只记录日志而让调用方误认为发送成功。

### 5.2 异常模型

`TransportException` 用于明确表示：

- 地址绑定失败。
- Transport 未启动或已经关闭。
- 目标协议与 Transport 不匹配。
- Datagram 超过配置上限。
- Netty Channel 写入失败。
- EventLoop 启动或关闭失败。

解析失败继续使用 `SipParseException`，并通过消息处理接口单独报告。

## 6. 入站事件接口

```java
public interface SipMessageHandler {

    void onMessage(InboundSipMessage message);

    default void onMalformedMessage(
            TransportContext context,
            SipParseException cause
    ) {
    }

    default void onTransportError(Throwable cause) {
    }
}
```

行为要求：

- 合法报文通过 `onMessage` 交给上层。
- 非法 UDP 报文通过 `onMalformedMessage` 报告并丢弃。
- 单个非法报文不能关闭监听 Channel。
- Channel 或 EventLoop 级错误通过 `onTransportError` 报告。
- 错误信息不得默认记录完整 SIP Body。

## 7. Netty 实现

### 7.1 依赖选择

第二阶段只引入 UDP 所需的 Netty 4.1 稳定版本依赖，不使用 `netty-all` 聚合包。具体版本在实施时固定到 Maven Central 可用的稳定补丁版本。

第一版使用跨平台实现：

```text
NioEventLoopGroup
NioDatagramChannel
```

暂不引入 epoll、kqueue 或其他 native transport。

### 7.2 Channel Pipeline

```text
NioDatagramChannel
    ↓
SipDatagramHandler
    ├── 检查 Datagram 大小
    ├── 复制 ByteBuf
    ├── SipMessageParser.parse(byte[])
    ├── 创建 TransportContext
    └── 投递 InboundSipMessage
```

SIP Parser 已经具有 start-line、Header 和 Body 上限。Datagram Handler 还需要在复制数据前检查整个 UDP Datagram 上限，避免先分配过大数组。

### 7.3 ByteBuf 所有权

入站 Datagram 由 Netty 管理引用计数。Handler 在 EventLoop 中复制数据：

```java
byte[] bytes = new byte[packet.content().readableBytes()];
packet.content().getBytes(packet.content().readerIndex(), bytes);

SipMessage message = parser.parse(bytes);
```

复制完成后，上层不持有 ByteBuf，也不需要调用 `retain()` 或 `release()`。

出站时：

```java
byte[] encoded = encoder.encode(message);
ByteBuf content = Unpooled.wrappedBuffer(encoded);
DatagramPacket packet = new DatagramPacket(content, targetAddress);
channel.writeAndFlush(packet);
```

执行 `writeAndFlush` 后，Datagram 和 ByteBuf 的所有权交给 Netty。如果在写入前发生异常，创建方必须释放已创建的引用计数对象。

第一版接受一次入站和出站字节复制，优先保证所有权清晰和跨线程安全。

## 8. EventLoop 与虚拟线程

Netty EventLoop 可以执行：

- Datagram 读取。
- 报文大小检查。
- 字节复制。
- 当前同步 Parser 调用。
- 将不可变消息快速投递给上层 Dispatcher。

以下工作不能在 EventLoop 执行：

- 用户业务回调。
- 数据库或远程服务访问。
- 等待其他线程或 Future。
- 长时间计算。
- 后续 Transaction/Dialog 状态机处理。

Transport/Endpoint 启动时创建一个共享虚拟线程 Executor：

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

收到合法消息后：

```java
executor.execute(() -> handler.onMessage(inboundMessage));
```

不能为每条 Datagram 创建一个新的 Executor。Transport 关闭时需要停止接收新任务，并关闭所拥有的虚拟线程 Executor。

后续引入事务层后，执行链调整为：

```text
Netty EventLoop
    ↓
Transaction Dispatcher
    ↓
Transaction Mailbox
    ↓
Virtual Thread
```

Transport 公共接口不应因此发生破坏性变化。

## 9. UDP 配置

第一版配置保持精简：

```java
public record UdpTransportConfig(
        InetSocketAddress bindAddress,
        int maxDatagramBytes
) {
}
```

要求：

- 支持 `127.0.0.1:0`，由操作系统分配端口，方便测试。
- `maxDatagramBytes` 必须大于零且不超过 UDP 有效载荷理论上限。
- 绑定成功后，`localEndpoint()` 返回操作系统分配的真实地址和端口。
- 第一版不开放大量底层 Netty ChannelOption，确有需求时再增加稳定配置项。

UDP 理论载荷上限约为 65507 字节，但大 SIP 报文实际可能需要切换 TCP。基于 MTU 的 Transport Selection 属于后续阶段，本阶段不擅自拒绝所有超过 1300 字节的报文。

## 10. 生命周期

Transport 生命周期：

```text
NEW → STARTING → RUNNING → CLOSING → CLOSED
```

行为约束：

- `start()` 绑定配置地址并等待结果，失败时抛出 `TransportException`。
- 重复调用 `start()` 不得创建多个 Channel。
- `localEndpoint()` 在绑定成功后才能返回有效 Endpoint。
- 未启动或关闭后调用 `send()`，返回异常完成的 `CompletionStage`。
- `close()` 可以重复调用。
- 关闭时先停止接收，再关闭 Channel、虚拟线程 Executor 和自己拥有的 EventLoopGroup。
- 启动部分失败时必须释放已创建的 EventLoop 和 Executor。
- 不允许遗留阻止 JVM 退出的 Netty 或 Executor 线程。

第一版由 `NettyUdpTransport` 自己创建和拥有 EventLoopGroup。后续支持多 Transport 共享 EventLoopGroup 时，再通过构造参数和明确的 ownership 标识扩展。

## 11. OPTIONS 响应工厂

为了完成不依赖 Transaction 的 UDP 闭环，增加最小响应工厂：

```java
public final class SipResponses {

    public static SipResponse createResponse(
            SipRequest request,
            int statusCode,
            String reasonPhrase
    ) {
        // Copy response correlation headers.
    }
}
```

`200 OK` 至少复制：

- 请求中的所有 Via，保持顺序。
- From。
- To。
- Call-ID。
- CSeq。

响应 Encoder 自动生成 `Content-Length`。

最终响应通常需要给 To 增加 local tag。不能用简单的 `contains("tag=")` 或无条件字符串拼接处理参数；如果第二阶段需要自动生成 tag，应先增加最小的 name-addr/参数解析能力。也可以让 OPTIONS 示例显式提供响应 To Header，从而把完整 Header 类型化解析留给事务和 Dialog 实现前完成。

响应工厂只创建响应模板，不承担 Transaction 行为。

## 12. OPTIONS 示例

服务端：

```text
1. 启动 UDP Transport 并绑定本地端口。
2. 收到 SipRequest。
3. 判断 Method 是否为 OPTIONS。
4. 在虚拟线程 Handler 中创建 200 OK。
5. 使用入站 TransportContext.remoteAddress() 发送响应。
```

客户端：

```text
1. 启动 UDP Transport。
2. 构造具有 Via、From、To、Call-ID、CSeq、Max-Forwards 的 OPTIONS。
3. 向服务端 Endpoint 发送请求。
4. 在 Handler 中接收并输出 200 OK。
```

示例中的 `CompletableFuture` 只用于等待演示结果，不能被包装为 Transport 的请求/响应 API，避免提前实现一个不完整的 Transaction 机制。

## 13. 测试策略

### 13.1 单元测试

- `UdpTransportConfig` 参数校验。
- Endpoint 和 Context 不变量。
- Netty Datagram 到 `InboundSipMessage` 的转换。
- Encoder 输出到 Datagram 的地址和字节内容。
- 非法 SIP 报文触发 `onMalformedMessage`。
- Datagram 超过配置上限时被拒绝。
- Handler 异常不会关闭 UDP Channel。
- `SendResult` 中地址和编码长度正确。

Netty Handler 可以使用 EmbeddedChannel 或直接调用可测试的转换组件，但不能只依赖 mock 验证内部方法调用。

### 13.2 真实 UDP 集成测试

使用两个绑定 `127.0.0.1:0` 的 Transport：

```text
Client Transport                 Server Transport
127.0.0.1:随机端口  ─OPTIONS→    127.0.0.1:随机端口
                    ←200 OK─
```

测试覆盖：

1. 绑定随机端口后能获得实际端口。
2. 服务端收到正确 SIP Request。
3. 服务端 Context 包含正确本地和远端地址。
4. 客户端收到并解析 `200 OK`。
5. 二进制 Body 通过 UDP 后保持一致。
6. 非法 Datagram 被丢弃后，Channel 仍能处理下一条合法消息。
7. `Content-Length` 不匹配不触发 `onMessage`。
8. `close()` 可重复调用且相关线程退出。
9. 关闭后发送返回明确失败。

异步测试必须设置硬超时，例如 3 到 5 秒，避免失败时 Maven 构建永久等待。测试结束时通过 `finally` 或扩展机制关闭所有 Transport。

## 14. 验收标准

- JDK 21 下 `mvn clean test` 通过。
- Netty 依赖只进入 Transport 实现，不进入 message/codec 核心。
- 两个真实本地 UDP Endpoint 完成 OPTIONS/`200 OK` 往返。
- 上层 `SipMessageHandler` 在虚拟线程而不是 Netty EventLoop 中运行。
- 入站消息包含正确的协议、本地地址和远端地址。
- 上层代码不接触 ByteBuf、Channel 或 DatagramPacket。
- 非法或超限 Datagram 不会关闭整个监听 Channel。
- 发送结果能够向调用方报告成功或异常。
- Transport 重复关闭安全，测试结束后没有遗留线程。
- 没有在 Transport 中实现响应关联、重传或超时。

## 15. 后续衔接

第三阶段基于本阶段 Transport 增加：

- Transaction ID 和 Transaction Repository。
- 入站 Request/Response Dispatcher。
- 每个 Transaction 的 Mailbox。
- Timer 抽象和虚拟时钟。
- NICT、NIST、ICT、IST 状态机。
- UDP 重传、总超时和 Transport 失败事件。
- ACK、CANCEL 的事务关联规则。

JAIN-SIP Adapter 在 Transaction 和 Dialog API 稳定后单独规划和实施。
