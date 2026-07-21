package org.loomsip.testkit;

import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportException;

import java.util.Objects;

/**
 * Test-owned lifecycle for a client/server pair of already configured transports.
 *
 * <pre>{@code
 * ScenarioTransportPair
 *     |              |
 *     v              v
 * client transport  server transport
 * }</pre>
 */
public final class ScenarioTransportPair implements AutoCloseable {

    private final SipTransport client;
    private final SipTransport server;

    private ScenarioTransportPair(SipTransport client, SipTransport server) {
        this.client = Objects.requireNonNull(client, "client");
        this.server = Objects.requireNonNull(server, "server");
    }

    /**
     * Starts both transports and closes the client if server startup fails.
     *
     * @param client client-side transport
     * @param server server-side transport
     * @return started pair
     * @throws TransportException if either transport cannot start
     */
    public static ScenarioTransportPair start(SipTransport client, SipTransport server) throws TransportException {
        ScenarioTransportPair pair = new ScenarioTransportPair(client, server);
        pair.client.start();
        try {
            pair.server.start();
            return pair;
        } catch (RuntimeException exception) {
            pair.client.close();
            throw exception;
        }
    }

    /** @return started client transport */
    public SipTransport client() {
        return client;
    }

    /** @return started server transport */
    public SipTransport server() {
        return server;
    }

    @Override
    public void close() {
        server.close();
        client.close();
    }
}
