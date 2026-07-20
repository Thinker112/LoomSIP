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
import org.loomsip.transport.TransportConnectionId;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Netty-backed reliable connection owned by {@link ConnectionManager}. */
final class NettyTransportConnection implements TransportConnection {

    private final TransportConnectionId id;
    private final ConnectionKey key;
    private final Channel channel;
    private final TransportEndpoint localEndpoint;
    private final TransportEndpoint remoteEndpoint;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.ACTIVE);
    private final AtomicReference<Throwable> closeCause = new AtomicReference<>();
    private final Set<CompletableFuture<SendResult>> pendingSends = ConcurrentHashMap.newKeySet();

    NettyTransportConnection(ConnectionKey key, Channel channel) {
        this.key = Objects.requireNonNull(key, "key");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.id = new TransportConnectionId(channel.id().asLongText());
        this.localEndpoint = new TransportEndpoint(key.protocol(), NettyTcpTransport.socketAddress(
                channel.localAddress(),
                "TCP connection local address"
        ));
        this.remoteEndpoint = new TransportEndpoint(key.protocol(), NettyTcpTransport.socketAddress(
                channel.remoteAddress(),
                "TCP connection remote address"
        ));
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
        return pendingSends.size();
    }

    CompletionStage<SendResult> send(byte[] encoded, TransportEndpoint requestedTarget) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        if (!isReusable()) {
            return CompletableFuture.failedFuture(new TransportException(
                    "TCP connection " + id.value() + " is not active"
            ));
        }

        CompletableFuture<SendResult> result = new CompletableFuture<>();
        pendingSends.add(result);
        ByteBuf content = Unpooled.wrappedBuffer(encoded);
        try {
            ChannelFuture writeFuture = channel.writeAndFlush(content);
            writeFuture.addListener(completed -> {
                pendingSends.remove(result);
                if (completed.isSuccess()) {
                    result.complete(new SendResult(localEndpoint, requestedTarget, encoded.length));
                } else {
                    result.completeExceptionally(new TransportException(
                            "failed to write TCP SIP message to " + requestedTarget.address(),
                            completed.cause()
                    ));
                }
            });
        } catch (Throwable cause) {
            ReferenceCountUtil.safeRelease(content);
            pendingSends.remove(result);
            result.completeExceptionally(new TransportException(
                    "failed to submit TCP SIP message to " + requestedTarget.address(),
                    cause
            ));
        }
        return result;
    }

    void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        closeCause.compareAndSet(null, cause);
        ConnectionState current = state.get();
        if (current != ConnectionState.CLOSED && current != ConnectionState.CLOSING) {
            state.set(ConnectionState.FAILED);
        }
        channel.close();
    }

    @Override
    public void close() {
        close(new TransportException("TCP connection closed"));
    }

    void close(Throwable pendingSendFailure) {
        Objects.requireNonNull(pendingSendFailure, "pendingSendFailure");
        ConnectionState current;
        do {
            current = state.get();
            if (current == ConnectionState.CLOSING || current == ConnectionState.CLOSED) {
                return;
            }
        } while (!state.compareAndSet(current, ConnectionState.CLOSING));
        closeCause.compareAndSet(null, pendingSendFailure);
        failPendingSends(pendingSendFailure);
        channel.close();
    }

    Throwable channelClosed(Throwable unexpectedFailure) {
        Objects.requireNonNull(unexpectedFailure, "unexpectedFailure");
        ConnectionState previous = state.getAndSet(ConnectionState.CLOSED);
        Throwable failure = closeCause.updateAndGet(existing ->
                existing == null ? unexpectedFailure : existing
        );
        failPendingSends(failure);
        return previous == ConnectionState.ACTIVE || previous == ConnectionState.FAILED
                ? failure
                : null;
    }

    private void failPendingSends(Throwable cause) {
        pendingSends.forEach(future -> future.completeExceptionally(cause));
        pendingSends.clear();
    }
}
