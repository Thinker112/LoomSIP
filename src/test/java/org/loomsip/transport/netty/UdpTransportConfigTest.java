package org.loomsip.transport.netty;

import org.junit.jupiter.api.Test;
import org.loomsip.transport.TransportEndpoint;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UdpTransportConfigTest {

    @Test
    void validatesDatagramBounds() {
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);

        assertThrows(IllegalArgumentException.class, () -> new UdpTransportConfig(loopback, 0));
        assertThrows(IllegalArgumentException.class, () -> new UdpTransportConfig(
                loopback,
                UdpTransportConfig.MAX_UDP_PAYLOAD_BYTES + 1
        ));
        assertEquals(UdpTransportConfig.DEFAULT_MAX_DATAGRAM_BYTES,
                new UdpTransportConfig(loopback).maxDatagramBytes());
    }

    @Test
    void rejectsUnresolvedAddresses() {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("invalid.example", 5060);

        assertThrows(IllegalArgumentException.class, () -> new UdpTransportConfig(unresolved));
        assertThrows(IllegalArgumentException.class, () -> TransportEndpoint.udp(unresolved));
    }
}
