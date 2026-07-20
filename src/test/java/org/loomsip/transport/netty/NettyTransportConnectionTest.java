package org.loomsip.transport.netty;

import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.loomsip.transport.ConnectionKey;
import org.loomsip.transport.ConnectionState;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportLimitException;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.WriteQueueLimits;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyTransportConnectionTest {

    @Test
    void rejectsPendingWriteCountAndReleasesAdmissionOnClose() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new HoldingOutboundHandler());
        NettyTransportConnection connection = connection(
                channel,
                new WriteQueueLimits(1, 32)
        );
        byte[] firstBytes = new byte[]{1, 2, 3};

        try {
            CompletableFuture<SendResult> first = connection.send(
                    firstBytes,
                    connection.remoteEndpoint()
            ).toCompletableFuture();
            assertFalse(first.isDone());
            assertEquals(1, connection.pendingSendCount());
            assertEquals(3, connection.pendingSendBytes());

            ExecutionException rejected = assertThrows(ExecutionException.class, () ->
                    connection.send(new byte[]{4}, connection.remoteEndpoint())
                            .toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TransportLimitException.class, rejected.getCause());

            connection.close();
            ExecutionException closed = assertThrows(ExecutionException.class, () ->
                    first.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TransportException.class, closed.getCause());
            assertEquals(0, connection.pendingSendCount());
            assertEquals(0, connection.pendingSendBytes());
            assertEquals(ConnectionState.CLOSING, connection.state());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOneWriteLargerThanByteLimitBeforeChannelWrite() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new HoldingOutboundHandler());
        NettyTransportConnection connection = connection(
                channel,
                new WriteQueueLimits(10, 2)
        );
        try {
            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                    connection.send(new byte[]{1, 2, 3}, connection.remoteEndpoint())
                            .toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TransportLimitException.class, failure.getCause());
            assertEquals(0, connection.pendingSendCount());
            assertEquals(0, connection.pendingSendBytes());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static NettyTransportConnection connection(
            EmbeddedChannel channel,
            WriteQueueLimits limits
    ) {
        TransportEndpoint local = TransportEndpoint.tcp(address(21000));
        TransportEndpoint remote = TransportEndpoint.tcp(address(21001));
        return new NettyTransportConnection(
                new ConnectionKey(TransportProtocol.TCP, local.address(), remote.address()),
                channel,
                limits,
                local,
                remote
        );
    }

    private static InetSocketAddress address(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static final class HoldingOutboundHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext context, Object message,
                          io.netty.channel.ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            // Intentionally leave the promise incomplete to model a slow peer.
        }
    }
}
