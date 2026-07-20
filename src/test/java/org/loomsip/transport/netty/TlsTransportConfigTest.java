package org.loomsip.transport.netty;

import org.junit.jupiter.api.Test;
import io.netty.handler.ssl.SslContext;
import org.loomsip.transport.ConnectionLimits;
import org.loomsip.codec.StreamBufferLimits;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsTransportConfigTest {

    @Test
    void redactsSslContextsAndCopiesEngineLists() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            SslContext context = material.serverContext();
            TlsTransportConfig config = new TlsTransportConfig(
                    loopbackAddress(),
                    StreamBufferLimits.DEFAULT,
                    ConnectionLimits.DEFAULT,
                    context,
                    material.trustedClientContext(),
                    Duration.ofSeconds(1),
                    true,
                    "test-profile",
                    List.of("TLSv1.3"),
                    List.of("TLS_AES_128_GCM_SHA256")
            );

            assertTrue(config.toString().contains("test-profile"));
            assertTrue(!config.toString().contains("SslContext"));
            assertEquals(List.of("TLSv1.3"), config.enabledProtocols());
            assertEquals(1_000, config.handshakeTimeoutMillis());
        }
    }

    @Test
    void rejectsInvalidTlsConfiguration() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new TlsTransportConfig(
                InetSocketAddress.createUnresolved("localhost", 5061),
                null,
                null
        ));
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            assertThrows(IllegalArgumentException.class, () -> new TlsTransportConfig(
                    loopbackAddress(),
                    StreamBufferLimits.DEFAULT,
                    ConnectionLimits.DEFAULT,
                    material.serverContext(),
                    material.trustedClientContext(),
                    Duration.ZERO,
                    true,
                    "profile",
                    List.of(),
                    List.of()
            ));
        }
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

}
