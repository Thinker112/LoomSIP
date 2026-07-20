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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
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
import org.loomsip.transport.TransportConnectException;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportState;
import org.loomsip.transport.TlsHandshakeException;
import org.loomsip.transport.TlsPeerVerificationException;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
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
public class NettyTcpTransport implements SipTransport {

    private static final System.Logger LOGGER = System.getLogger(NettyTcpTransport.class.getName());
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final TcpTransportConfig config;
    private final TransportProtocol protocol;
    private final TlsTransportConfig tlsConfig;
    private final SipMessageParser parser;
    private final SipMessageEncoder encoder = new SipMessageEncoder();
    private final SipMessageHandler handler;
    private final ConnectionManager connectionManager;
    private final Object lifecycleMonitor = new Object();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    /**
     * Transport-level send stages that have not reached a terminal result.
     *
     * <p>This tracks local write completion, not SIP response correlation or
     * transaction retransmission. It exists so close/connect/write races can
     * complete every stage already returned to callers. Stage 5E adds count and
     * byte admission limits; without them a slow TCP/TLS peer could retain an
     * unbounded number of encoded buffers and futures.</p>
     */
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
        this(TransportProtocol.TCP, config, parserLimits, handler, null);
    }

    /**
     * Creates the shared reliable-stream implementation used by TCP and TLS.
     *
     * @param protocol TCP or TLS protocol
     * @param config common listener, framing, and connection configuration
     * @param parserLimits complete-message parser limits
     * @param handler decoded message and transport diagnostic callback
     * @param tlsConfig TLS engine configuration, or {@code null} for plain TCP
     */
    NettyTcpTransport(
            TransportProtocol protocol,
            TcpTransportConfig config,
            SipParserLimits parserLimits,
            SipMessageHandler handler,
            TlsTransportConfig tlsConfig
    ) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        if (protocol == TransportProtocol.UDP) {
            throw new IllegalArgumentException("reliable stream transport cannot use UDP");
        }
        if (protocol == TransportProtocol.TLS) {
            if (tlsConfig == null) {
                throw new IllegalArgumentException("TLS protocol requires TLS configuration");
            }
        } else if (tlsConfig != null) {
            throw new IllegalArgumentException("TLS configuration requires the TLS protocol");
        }
        this.config = Objects.requireNonNull(config, "config");
        this.tlsConfig = tlsConfig;
        this.parser = new SipMessageParser(Objects.requireNonNull(parserLimits, "parserLimits"));
        this.handler = Objects.requireNonNull(handler, "handler");
        this.connectionManager = new ConnectionManager(config.connectionLimits());
    }

    @Override
    public void start() throws TransportException {
        synchronized (lifecycleMonitor) {
            if (state != TransportState.NEW) {
                throw new TransportException(protocol + " transport cannot start from state " + state);
            }
            state = TransportState.STARTING;

            NioEventLoopGroup newBossGroup = null;
            NioEventLoopGroup newWorkerGroup = null;
            ExecutorService newHandlerExecutor = null;
            Channel newServerChannel = null;
            try {
                newBossGroup = new NioEventLoopGroup(
                        1,
                        new DefaultThreadFactory("loomsip-" + protocolName() + "-accept", true)
                );
                newWorkerGroup = new NioEventLoopGroup(
                        1,
                        new DefaultThreadFactory("loomsip-" + protocolName() + "-io", true)
                );
                newHandlerExecutor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("loomsip-" + protocolName() + "-handler-", 0).factory()
                );
                bossGroup = newBossGroup;
                workerGroup = newWorkerGroup;
                handlerExecutor = newHandlerExecutor;

                newServerChannel = createServerBootstrap(newBossGroup, newWorkerGroup)
                        .bind(config.bindAddress())
                        .sync()
                        .channel();
                serverChannel = newServerChannel;
                localEndpoint = new TransportEndpoint(protocol, socketAddress(
                        newServerChannel.localAddress(),
                        protocol + " listener local address"
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
                throw new TransportException("interrupted while binding " + protocol + " transport", exception);
            } catch (Throwable cause) {
                state = TransportState.FAILED;
                releaseResources(
                        newServerChannel,
                        newHandlerExecutor,
                        newWorkerGroup,
                        newBossGroup
                );
                throw new TransportException(
                        "failed to bind " + protocol + " transport to " + config.bindAddress(),
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
                if (tlsConfig != null) {
                    channel.pipeline().addLast(newSslHandler(channel, outboundKey));
                }
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

    private SslHandler newSslHandler(SocketChannel channel, ConnectionKey outboundKey) {
        SslContext context = outboundKey == null
                ? tlsConfig.serverContext()
                : tlsConfig.clientContext();
        SslHandler handler;
        if (outboundKey == null) {
            handler = context.newHandler(channel.alloc());
        } else {
            handler = context.newHandler(
                    channel.alloc(),
                    outboundKey.peerIdentity(),
                    outboundKey.remoteAddress().getPort()
            );
            if (tlsConfig.hostnameVerification()) {
                SSLParameters parameters = handler.engine().getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                handler.engine().setSSLParameters(parameters);
            }
        }
        if (!tlsConfig.enabledProtocols().isEmpty()) {
            handler.engine().setEnabledProtocols(tlsConfig.enabledProtocols().toArray(String[]::new));
        }
        if (!tlsConfig.enabledCipherSuites().isEmpty()) {
            handler.engine().setEnabledCipherSuites(
                    tlsConfig.enabledCipherSuites().toArray(String[]::new)
            );
        }
        handler.setHandshakeTimeoutMillis(tlsConfig.handshakeTimeoutMillis());
        return handler;
    }

    @Override
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");
        if (target.protocol() != protocol) {
            return failedSend(protocol + " transport cannot send to " + target.protocol(), null);
        }
        if (target.address().getPort() == 0) {
            return failedSend(protocol + " target port must not be zero", null);
        }

        TransportState currentState = state;
        if (currentState != TransportState.RUNNING) {
            return failedSend(protocol + " transport is not running (state " + currentState + ")", null);
        }

        byte[] encoded = encoder.encode(message);
        CompletableFuture<SendResult> result = new CompletableFuture<>();
        pendingSends.add(result);

        ConnectionKey key = new ConnectionKey(
                protocol,
                config.bindAddress(),
                target.address(),
                tlsConfig == null ? "" : tlsConfig.securityProfile(),
                tlsConfig == null ? "" : target.address().getHostString()
        );
        CompletionStage<NettyTransportConnection> connectionStage = connectionManager.acquire(
                key,
                () -> connect(key)
        );

        connectionStage.whenComplete((connection, connectFailure) -> {
            if (connectFailure != null) {
                pendingSends.remove(result);
                result.completeExceptionally(transportFailure(
                        "failed to acquire " + protocol + " connection to " + target.address(),
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
                            "failed to send " + protocol + " SIP message to " + target.address(),
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
                    protocol + " transport stopped before connect began"
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
                    result.completeExceptionally(new TransportConnectException(
                            "failed to connect " + protocol + " channel to " + key.remoteAddress(),
                            completed.cause()
                    ));
                    return;
                }
                Channel channel = ((ChannelFuture) completed).channel();
                SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
                if (sslHandler == null) {
                    completeConnectedChannel(channel, key, result);
                    return;
                }
                sslHandler.handshakeFuture().addListener(handshake -> {
                    if (handshake.isSuccess()) {
                        completeConnectedChannel(channel, key, result);
                    } else {
                        result.completeExceptionally(tlsHandshakeFailure(
                                key.remoteAddress(),
                                handshake.cause()
                        ));
                    }
                });
            });
        } catch (Throwable cause) {
            result.completeExceptionally(new TransportConnectException(
                    "failed to submit " + protocol + " connect to " + key.remoteAddress(),
                    cause
            ));
        }
        return result;
    }

    private void completeConnectedChannel(
            Channel channel,
            ConnectionKey key,
            CompletableFuture<NettyTransportConnection> result
    ) {
        NettyTransportConnection connection = ensureConnection(channel, key);
        if (connection.isReusable()) {
            result.complete(connection);
        } else {
            result.completeExceptionally(new TransportException(
                    protocol + " channel closed while connect completed"
            ));
        }
    }

    void channelActive(Channel channel, ConnectionKey outboundKey) {
        if (outboundKey != null) {
            ensureConnection(channel, outboundKey);
            return;
        }
        InetSocketAddress local = socketAddress(channel.localAddress(), protocol + " local address");
        InetSocketAddress remote = socketAddress(channel.remoteAddress(), protocol + " remote address");
        ConnectionKey inboundKey = new ConnectionKey(
                protocol,
                local,
                remote,
                tlsConfig == null ? "" : tlsConfig.securityProfile(),
                ""
        );
        NettyTransportConnection connection = ensureConnection(channel, inboundKey);
        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        if (sslHandler != null) {
            sslHandler.handshakeFuture().addListener(handshake -> {
                if (handshake.isSuccess()) {
                    registerInbound(inboundKey, connection);
                } else {
                    connection.fail(tlsHandshakeFailure(remote, handshake.cause()));
                }
            });
            return;
        }
        registerInbound(inboundKey, connection);
    }

    private void registerInbound(
            ConnectionKey inboundKey,
            NettyTransportConnection connection
    ) {
        if (!connectionManager.registerInbound(inboundKey, connection)) {
            connection.fail(new TransportException(
                    protocol + " inbound connection rejected by configured limits"
            ));
        }
    }

    void channelMessage(Channel channel, SipMessage message) {
        NettyTransportConnection connection = channelConnections.get(channel.id());
        if (connection == null) {
            channelFailure(channel, new IllegalStateException(
                    protocol + " message arrived before connection registration"
            ));
            return;
        }
        TransportContext context = new TransportContext(
                protocol,
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
        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        boolean handshakeFailure = sslHandler != null
                && (!sslHandler.handshakeFuture().isDone() || !sslHandler.handshakeFuture().isSuccess());
        Throwable failure = handshakeFailure
                ? tlsHandshakeFailure(connection.remoteEndpoint().address(), cause)
                : cause;
        connection.fail(failure);
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
                ignored -> new NettyTransportConnection(
                        key,
                        channel,
                        config.writeQueueLimits()
                )
        );
    }

    private void dispatchConnectionFailure(
            NettyTransportConnection connection,
            Throwable failure
    ) {
        SipParseException parseFailure = findCause(failure, SipParseException.class);
        if (parseFailure != null) {
            TransportContext context = new TransportContext(
                    protocol,
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
        LOGGER.log(System.Logger.Level.WARNING, "SIP stream transport error callback failed", cause);
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
                    protocol + " listener channel closed unexpectedly"
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
            throw new IllegalStateException(protocol + " transport has not bound a local endpoint");
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
                    protocol + " transport closed before send completed"
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

    private String protocolName() {
        return protocol.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static TransportException tlsHandshakeFailure(
            InetSocketAddress remoteAddress,
            Throwable cause
    ) {
        Throwable actual = cause == null ? new IllegalStateException("unknown TLS handshake failure") : cause;
        if (isPeerVerificationFailure(actual)) {
            return new TlsPeerVerificationException(
                    "TLS peer verification failed for " + remoteAddress,
                    actual
            );
        }
        return new TlsHandshakeException(
                "TLS handshake failed for " + remoteAddress,
                actual
        );
    }

    private static boolean isPeerVerificationFailure(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SSLPeerUnverifiedException
                    || current instanceof CertificateException
                    || (current.getMessage() != null
                    && current.getMessage().toLowerCase(java.util.Locale.ROOT).contains("no name matching"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
