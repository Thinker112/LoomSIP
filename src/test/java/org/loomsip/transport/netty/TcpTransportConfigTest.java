package org.loomsip.transport.netty;

import org.junit.jupiter.api.Test;
import org.loomsip.transport.ConnectionLimits;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpTransportConfigTest {

    @Test
    void suppliesDefaultsForResolvedBindAddress() {
        InetSocketAddress bindAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

        TcpTransportConfig config = new TcpTransportConfig(bindAddress);

        assertEquals(bindAddress, config.bindAddress());
        assertEquals(ConnectionLimits.DEFAULT, config.connectionLimits());
    }

    @Test
    void rejectsInvalidAddressesAndConnectionLimits() {
        assertThrows(IllegalArgumentException.class, () -> new TcpTransportConfig(
                InetSocketAddress.createUnresolved("invalid.example", 5060)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(
                1,
                2,
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(
                1,
                1,
                1,
                Duration.ZERO,
                Duration.ofSeconds(1)
        ));
    }
}
