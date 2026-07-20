package org.loomsip.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import org.loomsip.transport.ConnectionKey;
import org.loomsip.transport.ConnectionState;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportConnection;
import org.loomsip.transport.TransportConnectionClosedException;
import org.loomsip.transport.TransportConnectionId;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportLimitException;
import org.loomsip.transport.TransportWriteException;
import org.loomsip.transport.WriteQueueLimits;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Netty-backed reliable connection owned by {@link ConnectionManager}. */
final class NettyTransportConnection implements TransportConnection {

    private final TransportConnectionId id;
    private final ConnectionKey key;
    private final Channel channel;
    private final TransportEndpoint localEndpoint;
    private final TransportEndpoint remoteEndpoint;
    private final WriteQueueLimits writeQueueLimits;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.ACTIVE);
    private final AtomicReference<Throwable> closeCause = new AtomicReference<>();
    private final Object writeMonitor = new Object();
    /**
     * Writes accepted by this Channel whose completion callback has not run.
     *
     * <p>The set is a lifecycle registry, not a response or transaction
     * registry. A slow peer can otherwise make encoded buffers and futures grow
     * without bound, so 5E will apply per-connection pending-write count and
     * byte limits before this set accepts a new write.</p>
     */
    private final Map<CompletableFuture<SendResult>, Integer> pendingSends = new HashMap<>();
    private long pendingSendBytes;

    NettyTransportConnection(ConnectionKey key, Channel channel) {
        this(key, channel, WriteQueueLimits.DEFAULT);
    }

    NettyTransportConnection(
            ConnectionKey key,
            Channel channel,
            WriteQueueLimits writeQueueLimits
    ) {
        this(
                key,
                channel,
                writeQueueLimits,
                new TransportEndpoint(key.protocol(), NettyTcpTransport.socketAddress(
                        channel.localAddress(),
                        "connection local address"
                )),
                new TransportEndpoint(key.protocol(), NettyTcpTransport.socketAddress(
                        channel.remoteAddress(),
                        "connection remote address"
                ))
        );
    }

    NettyTransportConnection(
            ConnectionKey key,
            Channel channel,
            WriteQueueLimits writeQueueLimits,
            TransportEndpoint localEndpoint,
            TransportEndpoint remoteEndpoint
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.writeQueueLimits = Objects.requireNonNull(writeQueueLimits, "writeQueueLimits");
        this.id = new TransportConnectionId(channel.id().asLongText());
        this.localEndpoint = Objects.requireNonNull(localEndpoint, "localEndpoint");
        this.remoteEndpoint = Objects.requireNonNull(remoteEndpoint, "remoteEndpoint");
    }

    @Override
    public TransportConnectionId id() {
        return id;
    }

    @Override
    public ConnectionKey key() {
        return key;
    }

    @Override
    public ConnectionState state() {
        return state.get();
    }

    @Override
    public TransportEndpoint localEndpoint() {
        return localEndpoint;
    }

    @Override
    public TransportEndpoint remoteEndpoint() {
        return remoteEndpoint;
    }

    Channel channel() {
        return channel;
    }

    boolean isReusable() {
        return state.get() == ConnectionState.ACTIVE && channel.isActive();
    }

    int pendingSendCount() {
        synchronized (writeMonitor) {
            return pendingSends.size();
        }
    }

    long pendingSendBytes() {
        synchronized (writeMonitor) {
            return pendingSendBytes;
        }
    }

    CompletionStage<SendResult> send(byte[] encoded, TransportEndpoint requestedTarget) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        if (encoded.length > writeQueueLimits.maxPendingWriteBytesPerConnection()) {
            return CompletableFuture.failedFuture(new TransportLimitException(
                    key.protocol() + " write exceeds per-connection byte limit of "
                            + writeQueueLimits.maxPendingWriteBytesPerConnection()
            ));
        }

        CompletableFuture<SendResult> result = new CompletableFuture<>();
        synchronized (writeMonitor) {
            if (!isReusable()) {
                return CompletableFuture.failedFuture(new TransportException(
                        key.protocol() + " connection " + id.value() + " is not active"
                ));
            }
            if (pendingSends.size() >= writeQueueLimits.maxPendingWritesPerConnection()) {
                return CompletableFuture.failedFuture(new TransportLimitException(
                        key.protocol() + " pending write count limit reached: "
                                + writeQueueLimits.maxPendingWritesPerConnection()
                ));
            }
            long nextBytes = pendingSendBytes + encoded.length;
            if (nextBytes > writeQueueLimits.maxPendingWriteBytesPerConnection()) {
                return CompletableFuture.failedFuture(new TransportLimitException(
                        key.protocol() + " pending write byte limit reached: "
                                + writeQueueLimits.maxPendingWriteBytesPerConnection()
                ));
            }
            pendingSends.put(result, encoded.length);
            pendingSendBytes = nextBytes;
        }
        ByteBuf content = Unpooled.wrappedBuffer(encoded);
        try {
            ChannelFuture writeFuture = channel.writeAndFlush(content);
            writeFuture.addListener(completed -> {
                releasePending(result);
                if (completed.isSuccess()) {
                    result.complete(new SendResult(localEndpoint, requestedTarget, encoded.length));
                } else {
                    result.completeExceptionally(new TransportWriteException(
                            "failed to write " + key.protocol() + " SIP message to "
                                    + requestedTarget.address(),
                            completed.cause()
                    ));
                }
            });
        } catch (Throwable cause) {
            ReferenceCountUtil.safeRelease(content);
            releasePending(result);
            result.completeExceptionally(new TransportWriteException(
                    "failed to submit " + key.protocol() + " SIP message to "
                            + requestedTarget.address(),
                    cause
            ));
        }
        return result;
    }

    void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (writeMonitor) {
            closeCause.compareAndSet(null, cause);
            ConnectionState current = state.get();
            if (current != ConnectionState.CLOSED && current != ConnectionState.CLOSING) {
                state.set(ConnectionState.FAILED);
            }
        }
        channel.close();
    }

    @Override
    public void close() {
        close(new TransportConnectionClosedException(key.protocol() + " connection closed"));
    }

    void close(Throwable pendingSendFailure) {
        Objects.requireNonNull(pendingSendFailure, "pendingSendFailure");
        synchronized (writeMonitor) {
            ConnectionState current = state.get();
            if (current == ConnectionState.CLOSING || current == ConnectionState.CLOSED) {
                return;
            }
            state.set(ConnectionState.CLOSING);
            closeCause.compareAndSet(null, pendingSendFailure);
            failPendingSendsLocked(pendingSendFailure);
        }
        channel.close();
    }

    Throwable channelClosed(Throwable unexpectedFailure) {
        Objects.requireNonNull(unexpectedFailure, "unexpectedFailure");
        ConnectionState previous;
        Throwable failure;
        synchronized (writeMonitor) {
            previous = state.getAndSet(ConnectionState.CLOSED);
            failure = closeCause.updateAndGet(existing ->
                    existing == null ? unexpectedFailure : existing
            );
            failPendingSendsLocked(failure);
        }
        return previous == ConnectionState.ACTIVE || previous == ConnectionState.FAILED
                ? failure
                : null;
    }

    private void releasePending(CompletableFuture<SendResult> result) {
        synchronized (writeMonitor) {
            Integer bytes = pendingSends.remove(result);
            if (bytes != null) {
                pendingSendBytes -= bytes;
            }
        }
    }

    private void failPendingSendsLocked(Throwable cause) {
        pendingSends.forEach((future, ignored) -> future.completeExceptionally(cause));
        pendingSends.clear();
        pendingSendBytes = 0;
    }
}
