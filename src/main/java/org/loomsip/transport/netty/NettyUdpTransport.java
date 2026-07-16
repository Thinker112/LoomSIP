package org.loomsip.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.loomsip.codec.SipMessageEncoder;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.codec.SipParseException;
import org.loomsip.codec.SipParserLimits;
import org.loomsip.message.SipMessage;
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
 * One-shot Netty UDP transport with virtual-thread delivery of user callbacks.
 *
 * <p>Datagram copying and parsing run on the Netty EventLoop. Successfully
 * parsed messages and diagnostics are forwarded to a shared virtual-thread
 * executor, so application work never blocks the EventLoop.</p>
 *
 * <pre>{@code
 * UDP Datagram
 *      |
 *      v
 * Netty EventLoop
 *      |
 *      +--> size check --> byte[] copy --> SipMessageParser
 *                                              |
 *                                              v
 *                                   InboundSipMessage
 *                                              |
 *                                              v
 *                                  Virtual-thread Executor
 *                                              |
 *                                              v
 *                                      SipMessageHandler
 *
 * Outbound SipMessage --> Encoder --> ChannelFuture --> SendResult/Event
 * }</pre>
 */
public final class NettyUdpTransport implements SipTransport {

    private static final System.Logger LOGGER = System.getLogger(NettyUdpTransport.class.getName());
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final UdpTransportConfig config;
    private final SipMessageParser parser;
    private final SipMessageEncoder encoder;
    private final SipMessageHandler handler;
    private final Object lifecycleMonitor = new Object();
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    /**
     * Public send futures whose Netty write has not completed yet.
     *
     * <p>This is a lifecycle registry, not a SIP response registry. It tracks
     * only local channel writes; it does not correlate responses, perform UDP
     * retransmission, or implement SIP transaction timers. The concurrent set
     * allows sending threads, the Netty EventLoop, and {@link #close()} to
     * update the registry safely.</p>
     */
    private final Set<CompletableFuture<SendResult>> pendingSends = ConcurrentHashMap.newKeySet();

    private volatile TransportState state = TransportState.NEW;
    private volatile TransportEndpoint localEndpoint;
    private volatile Channel channel;
    private volatile EventLoopGroup eventLoopGroup;
    private volatile ExecutorService handlerExecutor;

    /**
     * Creates a UDP transport using the default SIP parser limits.
     *
     * @param config UDP bind and datagram configuration
     * @param handler user callback target
     */
    public NettyUdpTransport(UdpTransportConfig config, SipMessageHandler handler) {
        this(config, SipParserLimits.DEFAULT, handler);
    }

    /**
     * Creates a UDP transport using explicit SIP parser limits.
     *
     * @param config UDP bind and datagram configuration
     * @param parserLimits limits shared with other inbound transport parsers
     * @param handler user callback target
     */
    public NettyUdpTransport(
            UdpTransportConfig config,
            SipParserLimits parserLimits,
            SipMessageHandler handler
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.parser = new SipMessageParser(Objects.requireNonNull(parserLimits, "parserLimits"));
        this.encoder = new SipMessageEncoder();
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void start() throws TransportException {
        synchronized (lifecycleMonitor) {
            if (state != TransportState.NEW) {
                throw new TransportException("UDP transport cannot start from state " + state);
            }
            state = TransportState.STARTING;

            NioEventLoopGroup newEventLoopGroup = null;
            ExecutorService newHandlerExecutor = null;
            Channel newChannel = null;
            try {
                newEventLoopGroup = new NioEventLoopGroup(
                        1,
                        new DefaultThreadFactory("loomsip-udp-io", true)
                );
                newHandlerExecutor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("loomsip-udp-handler-", 0).factory()
                );
                eventLoopGroup = newEventLoopGroup;
                handlerExecutor = newHandlerExecutor;

                Bootstrap bootstrap = createBootstrap(newEventLoopGroup);
                newChannel = bootstrap.bind(config.bindAddress()).sync().channel();
                channel = newChannel;
                localEndpoint = TransportEndpoint.udp((InetSocketAddress) newChannel.localAddress());
                state = TransportState.RUNNING;
                newChannel.closeFuture().addListener(ignored -> handleChannelClosed());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                state = TransportState.FAILED;
                releaseResources(newChannel, newHandlerExecutor, newEventLoopGroup);
                throw new TransportException("interrupted while binding UDP transport", exception);
            } catch (Throwable cause) {
                state = TransportState.FAILED;
                releaseResources(newChannel, newHandlerExecutor, newEventLoopGroup);
                throw new TransportException("failed to bind UDP transport to " + config.bindAddress(), cause);
            }
        }
    }

    private Bootstrap createBootstrap(NioEventLoopGroup group) {
        return new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .option(
                        ChannelOption.RCVBUF_ALLOCATOR,
                        // The extra byte distinguishes an oversized datagram from
                        // one that exactly matches the configured acceptance limit.
                        new FixedRecvByteBufAllocator(config.maxDatagramBytes() + 1)
                )
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel datagramChannel) {
                        datagramChannel.pipeline().addLast(new SipDatagramHandler(
                                config.maxDatagramBytes(),
                                parser,
                                NettyUdpTransport.this::dispatchMessage,
                                NettyUdpTransport.this::dispatchMalformed,
                                NettyUdpTransport.this::dispatchError
                        ));
                    }
                });
    }

    @Override
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");

        if (target.protocol() != TransportProtocol.UDP) {
            return failedSend("UDP transport cannot send to " + target.protocol(), null);
        }
        if (target.address().getPort() == 0) {
            return failedSend("UDP target port must not be zero", null);
        }

        TransportState currentState = state;
        Channel currentChannel = channel;
        TransportEndpoint currentLocalEndpoint = localEndpoint;
        if (currentState != TransportState.RUNNING
                || currentChannel == null
                || currentLocalEndpoint == null
                || !currentChannel.isActive()) {
            return failedSend("UDP transport is not running (state " + currentState + ")", null);
        }

        byte[] encoded = encoder.encode(message);
        if (encoded.length > config.maxDatagramBytes()) {
            return failedSend(
                    "encoded SIP message exceeds UDP datagram limit of " + config.maxDatagramBytes() + " bytes",
                    null
            );
        }

        CompletableFuture<SendResult> result = new CompletableFuture<>();
        // Register the public future before handing the packet to Netty so
        // close() can fail it if the channel shuts down before the write ends.
        pendingSends.add(result);
        ByteBuf content = Unpooled.wrappedBuffer(encoded);
        DatagramPacket packet = new DatagramPacket(content, target.address());
        try {
            ChannelFuture writeFuture = currentChannel.writeAndFlush(packet);
            writeFuture.addListener(completed -> {
                // ChannelFuture completion is the boundary for this local send;
                // it says nothing about a remote SIP response or retransmission.
                pendingSends.remove(result);
                if (completed.isSuccess()) {
                    result.complete(new SendResult(currentLocalEndpoint, target, encoded.length));
                } else {
                    result.completeExceptionally(new TransportException(
                            "failed to send UDP SIP message to " + target.address(),
                            completed.cause()
                    ));
                }
            });
        } catch (Throwable cause) {
            ReferenceCountUtil.safeRelease(packet);
            pendingSends.remove(result);
            result.completeExceptionally(new TransportException(
                    "failed to submit UDP SIP message to " + target.address(),
                    cause
            ));
        }
        return result;
    }

    private CompletionStage<SendResult> failedSend(String message, Throwable cause) {
        TransportException exception = cause == null
                ? new TransportException(message)
                : new TransportException(message, cause);
        return CompletableFuture.failedFuture(exception);
    }

    @Override
    public TransportEndpoint localEndpoint() {
        TransportEndpoint endpoint = localEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("UDP transport has not bound a local endpoint");
        }
        return endpoint;
    }

    @Override
    public TransportState state() {
        return state;
    }

    private void dispatchMessage(InboundSipMessage message) {
        dispatchCallback(() -> handler.onMessage(message), true);
    }

    private void dispatchMalformed(TransportContext context, SipParseException cause) {
        dispatchCallback(() -> handler.onMalformedMessage(context, cause), true);
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
        LOGGER.log(System.Logger.Level.WARNING, "SIP transport error callback failed", cause);
    }

    private void handleChannelClosed() {
        boolean unexpected;
        synchronized (lifecycleMonitor) {
            unexpected = state == TransportState.RUNNING;
            if (unexpected) {
                state = TransportState.FAILED;
            }
        }
        if (unexpected) {
            TransportException failure = new TransportException("UDP channel closed unexpectedly");
            Thread.startVirtualThread(() -> {
                invokeErrorHandler(failure);
                close();
            });
        }
    }

    @Override
    public void close() {
        Channel channelToClose;
        ExecutorService executorToClose;
        EventLoopGroup groupToClose;
        boolean waitForOtherCloser = false;

        synchronized (lifecycleMonitor) {
            if (state == TransportState.CLOSED) {
                return;
            }
            if (state == TransportState.CLOSING) {
                waitForOtherCloser = true;
                channelToClose = null;
                executorToClose = null;
                groupToClose = null;
            } else if (state == TransportState.NEW) {
                state = TransportState.CLOSED;
                closedLatch.countDown();
                return;
            } else {
                state = TransportState.CLOSING;
                channelToClose = channel;
                executorToClose = handlerExecutor;
                groupToClose = eventLoopGroup;
            }
        }

        if (waitForOtherCloser) {
            awaitClosed();
            return;
        }

        try {
            releaseResources(channelToClose, executorToClose, groupToClose);
            // A channel close can race a Netty write callback. Completing the
            // registry here guarantees callers do not wait forever for writes
            // that were still outstanding when shutdown began.
            TransportException closedFailure = new TransportException("UDP transport closed before send completed");
            pendingSends.forEach(future -> future.completeExceptionally(closedFailure));
            pendingSends.clear();
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
            Channel channel,
            ExecutorService executor,
            EventLoopGroup eventLoopGroup
    ) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
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
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully(
                    0,
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            ).syncUninterruptibly();
        }
    }
}
