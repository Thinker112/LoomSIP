package org.loomsip.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.loomsip.codec.SipMessageParser;
import org.loomsip.codec.StreamBufferLimits;
import org.loomsip.message.SipRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SipStreamDecoderTest {

    @Test
    void decodesFragmentedAndStickyMessagesThroughNettyPipeline() {
        EmbeddedChannel channel = channel();
        byte[] first = request("first");
        byte[] second = request("second");
        int split = first.length / 2;

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(first, 0, split)));
        channel.writeInbound(Unpooled.wrappedBuffer(first, split, first.length - split));
        channel.writeInbound(Unpooled.wrappedBuffer(concat(second, request("third"))));

        assertEquals("first@example.com", callId(channel.readInbound()));
        assertEquals("second@example.com", callId(channel.readInbound()));
        assertEquals("third@example.com", callId(channel.readInbound()));
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void closesChannelAndReleasesInputOnFramingFailure() {
        EmbeddedChannel channel = channel();
        ByteBuf invalid = Unpooled.wrappedBuffer(wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders("invalid")
                        + "Content-Length: nope\r\n\r\n"
        ));

        assertThrows(DecoderException.class, () -> channel.writeInbound(invalid));

        assertFalse(channel.isActive());
        assertEquals(0, invalid.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsConnectionCloseWithIncompleteMessage() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(Unpooled.wrappedBuffer(wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
        )));

        assertThrows(DecoderException.class, channel::finish);

        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new SipStreamDecoder(
                StreamBufferLimits.DEFAULT,
                new SipMessageParser()
        ));
    }

    private static String callId(Object message) {
        return ((SipRequest) message).headers().firstValue("Call-ID").orElseThrow();
    }

    private static byte[] request(String id) {
        return wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders(id)
                        + "Content-Length: 0\r\n\r\n"
        );
    }

    private static String baseHeaders(String id) {
        return "Via: SIP/2.0/TCP client.example.com;branch=z9hG4bK-" + id + "\r\n"
                + "From: <sip:alice@example.com>;tag=alice\r\n"
                + "To: <sip:service@example.com>\r\n"
                + "Call-ID: " + id + "@example.com\r\n"
                + "CSeq: 1 OPTIONS\r\n"
                + "Max-Forwards: 70\r\n";
    }

    private static byte[] wire(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
