package org.loomsip.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.loomsip.codec.SipMessageEncoder;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.codec.SipParseException;
import org.loomsip.codec.SipParserLimits;
import org.loomsip.message.SipMessage;
import org.loomsip.transport.ConnectionKey;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportState;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One-shot Netty TCP transport with connection reuse and virtual-thread callbacks.
 *
 * <pre>{@code
 *                              +------------------------+
 * outbound send -------------> | ConnectionManager      |
 *                              | reuse / merge connect  |
 *                              +-----------+------------+
 *                                          |
 *                                          v
 *                               SocketChannel.write
 *
 * accepted/outbound SocketChannel
 *              |
 *              v
 *       IdleStateHandler
 *              |
 *              v
 *       SipStreamDecoder
 *              |
 *              v
 *       TcpChannelHandler
 *              |
 *              v
 * virtual-thread executor --> SipMessageHandler
 * }</pre>
 *
 * <p>Connection callbacks never mutate Transaction or Dialog state. They emit
 * immutable messages and transport failures through the existing handler
 * boundary; stage 5D will translate connection failures into mailbox events.</p>
 */
public final class NettyTcpTransport implements SipTransport {

    private static final System.Logger LOGGER = System.getLogger(NettyTcpTransport.class.getName());
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final TcpTransportConfig config;
    private final SipMessageParser parser;
    private final SipMessageEncoder encoder = new SipMessageEncoder();
    private final SipMessageHandler handler;
    private final ConnectionManager connectionManager;
    private final Object lifecycleMonitor = new Object();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final Set<CompletableFuture<SendResult>> pendingSends = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<io.netty.channel.ChannelId, NettyTransportConnection>
            channelConnections = new ConcurrentHashMap<>();

    private volatile TransportState state = TransportState.NEW;
    private volatile TransportEndpoint localEndpoint;
    private volatile Channel serverChannel;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile ExecutorService handlerExecutor;

    /**
     * Creates a TCP transport with default complete-message parser limits.
     *
     * @param config TCP listener, framing, and connection configuration
     * @param handler decoded message and transport diagnostic callback
     */
    public NettyTcpTransport(TcpTransportConfig config, SipMessageHandler handler) {
        this(config, SipParserLimits.DEFAULT, handler);
    }

    /**
     * Creates a TCP transport with explicit complete-message parser limits.
     *
     * @param config TCP listener, framing, and connection configuration
     * @param parserLimits complete-message parser limits
     * @param handler decoded message and transport diagnostic callback
     */
    public NettyTcpTransport(
            TcpTransportConfig config,
            SipParserLimits parserLimits,
            SipMessageHandler handler
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.parser = new SipMessageParser(Objects.requireNonNull(parserLimits, "parserLimits"));
        this.handler = Objects.requireNonNull(handler, "handler");
        this.connectionManager = new ConnectionManager(config.connectionLimits());
    }

    @Override
    public void start() throws TransportException {
        synchronized (lifecycleMonitor) {
            if (state != TransportState.NEW) {
                throw new TransportException("TCP transport cannot start from state " + state);
            }
            state = TransportState.STARTING;

            NioEventLoopGroup newBossGroup = null;
            NioEventLoopGroup newWorkerGroup = null;
            ExecutorService newHandlerExecutor = null;
            Channel newServerChannel = null;
            try {
                newBossGroup = new NioEventLoopGroup(
                        1,
                        new DefaultThreadFactory("loomsip-tcp-accept", true)
                );
                newWorkerGroup = new NioEventLoopGroup(
                        1,
                        new DefaultThreadFactory("loomsip-tcp-io", true)
                );
                newHandlerExecutor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("loomsip-tcp-handler-", 0).factory()
                );
                bossGroup = newBossGroup;
                workerGroup = newWorkerGroup;
                handlerExecutor = newHandlerExecutor;

                newServerChannel = createServerBootstrap(newBossGroup, newWorkerGroup)
                        .bind(config.bindAddress())
                        .sync()
                        .channel();
                serverChannel = newServerChannel;
                localEndpoint = TransportEndpoint.tcp(socketAddress(
                        newServerChannel.localAddress(),
                        "TCP listener local address"
                ));
                state = TransportState.RUNNING;
                newServerChannel.closeFuture().addListener(ignored -> handleServerChannelClosed());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                state = TransportState.FAILED;
                releaseResources(
                        newServerChannel,
                        newHandlerExecutor,
                        newWorkerGroup,
                        newBossGroup
                );
                throw new TransportException("interrupted while binding TCP transport", exception);
            } catch (Throwable cause) {
                state = TransportState.FAILED;
                releaseResources(
                        newServerChannel,
                        newHandlerExecutor,
                        newWorkerGroup,
                        newBossGroup
                );
                throw new TransportException(
                        "failed to bind TCP transport to " + config.bindAddress(),
                        cause
                );
            }
        }
    }

    private ServerBootstrap createServerBootstrap(
            NioEventLoopGroup acceptGroup,
            NioEventLoopGroup ioGroup
    ) {
        return new ServerBootstrap()
                .group(acceptGroup, ioGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(channelInitializer(null));
    }

    private ChannelInitializer<SocketChannel> channelInitializer(ConnectionKey outboundKey) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                long idleMillis = config.connectionLimits().idleTimeout().toMillis();
                channel.pipeline().addLast(new IdleStateHandler(
                        0,
                        0,
                        idleMillis,
                        TimeUnit.MILLISECONDS
                ));
                channel.pipeline().addLast(new SipStreamDecoder(
                        config.streamBufferLimits(),
                        parser
                ));
                channel.pipeline().addLast(new TcpChannelHandler(
                        NettyTcpTransport.this,
                        outboundKey
                ));
            }
        };
    }

    @Override
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");
        if (target.protocol() != TransportProtocol.TCP) {
            return failedSend("TCP transport cannot send to " + target.protocol(), null);
        }
        if (target.address().getPort() == 0) {
            return failedSend("TCP target port must not be zero", null);
        }

        TransportState currentState = state;
        if (currentState != TransportState.RUNNING) {
            return failedSend("TCP transport is not running (state " + currentState + ")", null);
        }

        byte[] encoded = encoder.encode(message);
        CompletableFuture<SendResult> result = new CompletableFuture<>();
        pendingSends.add(result);

        ConnectionKey key = new ConnectionKey(
                TransportProtocol.TCP,
                config.bindAddress(),
                target.address()
        );
        CompletionStage<NettyTransportConnection> connectionStage = connectionManager.acquire(
                key,
                () -> connect(key)
        );

        connectionStage.whenComplete((connection, connectFailure) -> {
            if (connectFailure != null) {
                pendingSends.remove(result);
                result.completeExceptionally(transportFailure(
                        "failed to acquire TCP connection to " + target.address(),
                        connectFailure
                ));
                return;
            }
            connection.send(encoded, target).whenComplete((sendResult, sendFailure) -> {
                pendingSends.remove(result);
                if (sendFailure == null) {
                    result.complete(sendResult);
                } else {
                    result.completeExceptionally(transportFailure(
                            "failed to send TCP SIP message to " + target.address(),
                            sendFailure
                    ));
                }
            });
        });
        return result;
    }

    private CompletionStage<NettyTransportConnection> connect(ConnectionKey key) {
        EventLoopGroup group = workerGroup;
        if (group == null || state != TransportState.RUNNING) {
            return CompletableFuture.failedFuture(new TransportException(
                    "TCP transport stopped before connect began"
            ));
        }

        CompletableFuture<NettyTransportConnection> result = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        config.connectionLimits().connectTimeoutMillis()
                )
                .handler(channelInitializer(key));
        try {
            ChannelFuture connectFuture = bootstrap.connect(key.remoteAddress());
            connectFuture.addListener(completed -> {
                if (!completed.isSuccess()) {
                    result.completeExceptionally(new TransportException(
                            "failed to connect TCP channel to " + key.remoteAddress(),
                            completed.cause()
                    ));
                    return;
                }
                Channel channel = ((ChannelFuture) completed).channel();
                NettyTransportConnection connection = ensureConnection(channel, key);
                if (connection.isReusable()) {
                    result.complete(connection);
                } else {
                    result.completeExceptionally(new TransportException(
                            "TCP channel closed while connect completed"
                    ));
                }
            });
        } catch (Throwable cause) {
            result.completeExceptionally(new TransportException(
                    "failed to submit TCP connect to " + key.remoteAddress(),
                    cause
            ));
        }
        return result;
    }

    void channelActive(Channel channel, ConnectionKey outboundKey) {
        if (outboundKey != null) {
            ensureConnection(channel, outboundKey);
            return;
        }
        InetSocketAddress local = socketAddress(channel.localAddress(), "TCP local address");
        InetSocketAddress remote = socketAddress(channel.remoteAddress(), "TCP remote address");
        ConnectionKey inboundKey = new ConnectionKey(TransportProtocol.TCP, local, remote);
        NettyTransportConnection connection = ensureConnection(channel, inboundKey);
        if (!connectionManager.registerInbound(inboundKey, connection)) {
            connection.fail(new TransportException(
                    "TCP inbound connection rejected by configured limits"
            ));
        }
    }

    void channelMessage(Channel channel, SipMessage message) {
        NettyTransportConnection connection = channelConnections.get(channel.id());
        if (connection == null) {
            channelFailure(channel, new IllegalStateException(
                    "TCP message arrived before connection registration"
            ));
            return;
        }
        TransportContext context = new TransportContext(
                TransportProtocol.TCP,
                connection.localEndpoint().address(),
                connection.remoteEndpoint().address()
        );
        dispatchCallback(() -> handler.onMessage(new InboundSipMessage(message, context)), true);
    }

    void channelFailure(Channel channel, Throwable cause) {
        NettyTransportConnection connection = channelConnections.get(channel.id());
        if (connection == null) {
            channel.close();
            dispatchError(cause);
            return;
        }
        connection.fail(cause);
    }

    void channelInactive(Channel channel) {
        NettyTransportConnection connection = channelConnections.remove(channel.id());
        if (connection == null) {
            return;
        }
        Throwable reportable = connectionManager.channelClosed(connection);
        if (reportable != null && state == TransportState.RUNNING) {
            dispatchConnectionFailure(connection, reportable);
        }
    }

    private NettyTransportConnection ensureConnection(Channel channel, ConnectionKey key) {
        return channelConnections.computeIfAbsent(
                channel.id(),
                ignored -> new NettyTransportConnection(key, channel)
        );
    }

    private void dispatchConnectionFailure(
            NettyTransportConnection connection,
            Throwable failure
    ) {
        SipParseException parseFailure = findCause(failure, SipParseException.class);
        if (parseFailure != null) {
            TransportContext context = new TransportContext(
                    TransportProtocol.TCP,
                    connection.localEndpoint().address(),
                    connection.remoteEndpoint().address()
            );
            dispatchCallback(() -> handler.onMalformedMessage(context, parseFailure), true);
        } else {
            dispatchError(failure);
        }
    }

    private void dispatchError(Throwable cause) {
        dispatchCallback(() -> invokeErrorHandler(cause), false);
    }

    private void dispatchCallback(Runnable callback, boolean reportCallbackFailure) {
        ExecutorService executor = handlerExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    callback.run();
                } catch (Throwable cause) {
                    if (reportCallbackFailure) {
                        invokeErrorHandler(cause);
                    } else {
                        logErrorHandlerFailure(cause);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            if (state == TransportState.RUNNING) {
                Thread.startVirtualThread(() -> invokeErrorHandler(exception));
            }
        }
    }

    private void invokeErrorHandler(Throwable cause) {
        try {
            handler.onTransportError(cause);
        } catch (Throwable handlerFailure) {
            logErrorHandlerFailure(handlerFailure);
        }
    }

    private static void logErrorHandlerFailure(Throwable cause) {
        LOGGER.log(System.Logger.Level.WARNING, "SIP TCP transport error callback failed", cause);
    }

    private void handleServerChannelClosed() {
        boolean unexpected;
        synchronized (lifecycleMonitor) {
            unexpected = state == TransportState.RUNNING;
            if (unexpected) {
                state = TransportState.FAILED;
            }
        }
        if (unexpected) {
            TransportException failure = new TransportException(
                    "TCP listener channel closed unexpectedly"
            );
            Thread.startVirtualThread(() -> {
                invokeErrorHandler(failure);
                close();
            });
        }
    }

    private CompletionStage<SendResult> failedSend(String message, Throwable cause) {
        return CompletableFuture.failedFuture(cause == null
                ? new TransportException(message)
                : new TransportException(message, cause));
    }

    @Override
    public TransportEndpoint localEndpoint() {
        TransportEndpoint endpoint = localEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("TCP transport has not bound a local endpoint");
        }
        return endpoint;
    }

    @Override
    public TransportState state() {
        return state;
    }

    /**
     * Returns the managed connection registry for diagnostics and testing.
     *
     * @return this transport's connection manager
     */
    public ConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public void close() {
        Channel serverToClose;
        ExecutorService executorToClose;
        EventLoopGroup workerToClose;
        EventLoopGroup bossToClose;
        boolean waitForOtherCloser = false;

        synchronized (lifecycleMonitor) {
            if (state == TransportState.CLOSED) {
                return;
            }
            if (state == TransportState.CLOSING) {
                waitForOtherCloser = true;
                serverToClose = null;
                executorToClose = null;
                workerToClose = null;
                bossToClose = null;
            } else if (state == TransportState.NEW) {
                state = TransportState.CLOSED;
                connectionManager.close();
                closedLatch.countDown();
                return;
            } else {
                state = TransportState.CLOSING;
                serverToClose = serverChannel;
                executorToClose = handlerExecutor;
                workerToClose = workerGroup;
                bossToClose = bossGroup;
            }
        }

        if (waitForOtherCloser) {
            awaitClosed();
            return;
        }

        try {
            if (serverToClose != null) {
                serverToClose.close().syncUninterruptibly();
            }
            connectionManager.close();
            TransportException closedFailure = new TransportException(
                    "TCP transport closed before send completed"
            );
            pendingSends.forEach(future -> future.completeExceptionally(closedFailure));
            pendingSends.clear();
            releaseResources(null, executorToClose, workerToClose, bossToClose);
            channelConnections.clear();
        } finally {
            synchronized (lifecycleMonitor) {
                state = TransportState.CLOSED;
                closedLatch.countDown();
            }
        }
    }

    private void awaitClosed() {
        boolean interrupted = false;
        while (true) {
            try {
                closedLatch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseResources(
            Channel serverChannel,
            ExecutorService executor,
            EventLoopGroup workerGroup,
            EventLoopGroup bossGroup
    ) {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(
                    0,
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            ).syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(
                    0,
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            ).syncUninterruptibly();
        }
        if (executor != null) {
            executor.shutdownNow();
            if (!Thread.currentThread().isVirtual()) {
                try {
                    executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static InetSocketAddress socketAddress(SocketAddress address, String description) {
        if (address instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress;
        }
        throw new IllegalStateException(description + " is unavailable");
    }

    private static TransportException transportFailure(String message, Throwable cause) {
        Throwable actual = cause;
        while ((actual instanceof java.util.concurrent.CompletionException
                || actual instanceof java.util.concurrent.ExecutionException)
                && actual.getCause() != null) {
            actual = actual.getCause();
        }
        return actual instanceof TransportException transportException
                ? transportException
                : new TransportException(message, actual);
    }

    private static <T extends Throwable> T findCause(Throwable cause, Class<T> type) {
        Throwable current = cause;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current instanceof DecoderException && current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
