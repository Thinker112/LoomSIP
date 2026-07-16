package org.loomsip.example;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.netty.NettyUdpTransport;
import org.loomsip.transport.netty.UdpTransportConfig;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Minimal stateless UDP OPTIONS responder used by the milestone-two example.
 *
 * <p>This is not a server transaction implementation and performs no SIP
 * retransmission or duplicate-request detection.</p>
 */
public final class OptionsServer implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(OptionsServer.class.getName());

    private final String localTag = UUID.randomUUID().toString().replace("-", "");
    private final NettyUdpTransport transport;

    /**
     * Creates an OPTIONS server bound when {@link #start()} is invoked.
     *
     * @param bindAddress resolved local UDP address; port zero is allowed
     */
    public OptionsServer(InetSocketAddress bindAddress) {
        this.transport = new NettyUdpTransport(
                new UdpTransportConfig(bindAddress),
                new ServerHandler()
        );
    }

    /**
     * Binds the UDP listener.
     *
     * @throws TransportException if binding fails
     */
    public void start() throws TransportException {
        transport.start();
    }

    /**
     * Returns the actual bound endpoint.
     *
     * @return server UDP endpoint
     * @throws IllegalStateException if the server has not started successfully
     */
    public TransportEndpoint localEndpoint() {
        return transport.localEndpoint();
    }

    /**
     * Closes the underlying UDP transport.
     */
    @Override
    public void close() {
        transport.close();
    }

    private final class ServerHandler implements SipMessageHandler {

        @Override
        public void onMessage(InboundSipMessage inbound) {
            if (!(inbound.message() instanceof SipRequest request)
                    || !SipMethod.OPTIONS.equals(request.method())) {
                return;
            }

            SipResponse response = SipResponses.createResponse(request, 200, "OK", localTag);
            TransportEndpoint target = TransportEndpoint.udp(inbound.context().remoteAddress());
            transport.send(response, target).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.log(System.Logger.Level.WARNING, "Failed to send OPTIONS response", failure);
                }
            });
        }

        @Override
        public void onTransportError(Throwable cause) {
            LOGGER.log(System.Logger.Level.WARNING, "OPTIONS server transport failure", cause);
        }
    }
}
