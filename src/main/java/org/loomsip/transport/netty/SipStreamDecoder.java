package org.loomsip.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.codec.SipParseException;
import org.loomsip.codec.SipStreamFramer;
import org.loomsip.codec.StreamBufferLimits;
import org.loomsip.message.SipMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Netty adapter from channel bytes to complete immutable SIP messages.
 *
 * <pre>{@code
 * ByteBuf chunks
 *      |
 *      v
 * SipStreamFramer
 *      |
 *      v
 * complete byte[] frame
 *      |
 *      v
 * SipMessageParser
 *      |
 *      v
 * SipMessage pipeline event
 * }</pre>
 *
 * <p>The adapter consumes every readable Netty byte immediately. Incomplete
 * bytes are owned by the pure Java {@link SipStreamFramer}; no {@link ByteBuf}
 * reference crosses the codec boundary.</p>
 */
final class SipStreamDecoder extends ByteToMessageDecoder {

    private final SipStreamFramer framer;
    private final SipMessageParser parser;

    SipStreamDecoder(StreamBufferLimits limits, SipMessageParser parser) {
        framer = new SipStreamFramer(Objects.requireNonNull(limits, "limits"));
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (!input.isReadable()) {
            return;
        }
        byte[] chunk = new byte[input.readableBytes()];
        input.readBytes(chunk);
        try {
            List<byte[]> frames = framer.feed(chunk);
            List<SipMessage> messages = new ArrayList<>(frames.size());
            for (byte[] frame : frames) {
                messages.add(parser.parse(frame));
            }
            output.addAll(messages);
        } catch (SipParseException exception) {
            framer.reset();
            context.close();
            throw new DecoderException("invalid SIP stream", exception);
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        decode(context, input, output);
        try {
            framer.endOfInput();
        } catch (SipParseException exception) {
            framer.reset();
            context.close();
            throw new DecoderException("SIP stream closed with an incomplete message", exception);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        framer.reset();
        context.fireExceptionCaught(cause);
        context.close();
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext context) throws Exception {
        framer.reset();
        super.handlerRemoved0(context);
    }
}
