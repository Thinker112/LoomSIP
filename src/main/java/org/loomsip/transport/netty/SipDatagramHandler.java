package org.loomsip.transport.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipMessage;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Copies and parses inbound Netty datagrams before forwarding immutable events.
 *
 * <p>The forwarding callbacks must remain non-blocking because this handler runs
 * on a Netty EventLoop. User callbacks are dispatched by {@link NettyUdpTransport}.</p>
 */
final class SipDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final int maxDatagramBytes;
    private final SipMessageParser parser;
    private final Consumer<InboundSipMessage> messageSink;
    private final BiConsumer<TransportContext, SipParseException> malformedSink;
    private final Consumer<Throwable> errorSink;

    /**
     * Creates an inbound UDP decoder and forwarding handler.
     *
     * @param maxDatagramBytes maximum datagram size checked before allocation
     * @param parser complete-message parser
     * @param messageSink non-blocking sink for valid messages
     * @param malformedSink non-blocking sink for rejected datagrams
     * @param errorSink non-blocking sink for channel failures
     */
    SipDatagramHandler(
            int maxDatagramBytes,
            SipMessageParser parser,
            Consumer<InboundSipMessage> messageSink,
            BiConsumer<TransportContext, SipParseException> malformedSink,
            Consumer<Throwable> errorSink
    ) {
        this.maxDatagramBytes = maxDatagramBytes;
        this.parser = Objects.requireNonNull(parser, "parser");
        this.messageSink = Objects.requireNonNull(messageSink, "messageSink");
        this.malformedSink = Objects.requireNonNull(malformedSink, "malformedSink");
        this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
        InetSocketAddress localAddress = (InetSocketAddress) context.channel().localAddress();
        InetSocketAddress remoteAddress = packet.sender();
        if (localAddress == null || remoteAddress == null) {
            errorSink.accept(new IllegalStateException("UDP datagram has incomplete address metadata"));
            return;
        }

        TransportContext transportContext = new TransportContext(
                TransportProtocol.UDP,
                localAddress,
                remoteAddress
        );
        int readableBytes = packet.content().readableBytes();
        if (readableBytes > maxDatagramBytes) {
            malformedSink.accept(
                    transportContext,
                    new SipParseException(
                            "UDP datagram exceeds configured limit of " + maxDatagramBytes + " bytes",
                            maxDatagramBytes
                    )
            );
            return;
        }

        byte[] bytes = new byte[readableBytes];
        packet.content().getBytes(packet.content().readerIndex(), bytes);
        try {
            SipMessage message = parser.parse(bytes);
            messageSink.accept(new InboundSipMessage(message, transportContext));
        } catch (SipParseException exception) {
            malformedSink.accept(transportContext, exception);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        errorSink.accept(cause);
    }
}
