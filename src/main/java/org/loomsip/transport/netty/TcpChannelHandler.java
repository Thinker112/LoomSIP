package org.loomsip.transport.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.loomsip.message.SipMessage;
import org.loomsip.transport.ConnectionKey;
import org.loomsip.transport.TransportException;

/** Bridges one TCP child channel to the owning transport without blocking its EventLoop. */
final class TcpChannelHandler extends SimpleChannelInboundHandler<SipMessage> {

    private final NettyTcpTransport transport;
    private final ConnectionKey outboundKey;

    TcpChannelHandler(NettyTcpTransport transport, ConnectionKey outboundKey) {
        this.transport = transport;
        this.outboundKey = outboundKey;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        transport.channelActive(context.channel(), outboundKey);
        super.channelActive(context);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, SipMessage message) {
        transport.channelMessage(context.channel(), message);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            transport.channelFailure(
                    context.channel(),
                    new TransportException("TCP connection exceeded configured idle timeout")
            );
            return;
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        transport.channelInactive(context.channel());
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        transport.channelFailure(context.channel(), cause);
    }
}
