package org.loomsip.example;

import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.netty.NettyUdpTransport;
import org.loomsip.transport.netty.UdpTransportConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot UDP OPTIONS client used only to demonstrate the transport milestone.
 *
 * <p>The client accepts the first received SIP response. It deliberately does
 * not provide transaction correlation, retransmission, or timeout behavior.</p>
 */
public final class OptionsClient implements AutoCloseable {

    private final NettyUdpTransport transport;
    private final CompletableFuture<SipResponse> response = new CompletableFuture<>();
    private final AtomicBoolean requestSent = new AtomicBoolean();

    /**
     * Creates a client bound when {@link #start()} is invoked.
     *
     * @param bindAddress resolved local UDP address; port zero is allowed
     */
    public OptionsClient(InetSocketAddress bindAddress) {
        this.transport = new NettyUdpTransport(
                new UdpTransportConfig(bindAddress),
                new ClientHandler()
        );
    }

    /**
     * Binds the UDP endpoint.
     *
     * @throws TransportException if binding fails
     */
    public void start() throws TransportException {
        transport.start();
    }

    /**
     * Sends the client's only OPTIONS request.
     *
     * @param server remote UDP server
     * @return future completed by the first inbound SIP response or a send failure
     * @throws IllegalStateException if called more than once or before startup
     */
    public CompletionStage<SipResponse> sendOptions(TransportEndpoint server) {
        Objects.requireNonNull(server, "server");
        if (!requestSent.compareAndSet(false, true)) {
            throw new IllegalStateException("the example client only sends one OPTIONS request");
        }
        TransportEndpoint local = transport.localEndpoint();
        SipRequest request = createOptions(local, server);
        transport.send(request, server).whenComplete((ignored, failure) -> {
            if (failure != null) {
                response.completeExceptionally(failure);
            }
        });
        return response;
    }

    /**
     * Returns the actual bound endpoint.
     *
     * @return client UDP endpoint
     */
    public TransportEndpoint localEndpoint() {
        return transport.localEndpoint();
    }

    /**
     * Closes the underlying UDP transport.
     */
    @Override
    public void close() {
        response.completeExceptionally(new TransportException("OPTIONS client closed before receiving a response"));
        transport.close();
    }

    private static SipRequest createOptions(TransportEndpoint local, TransportEndpoint server) {
        String branch = "z9hG4bK-" + compactUuid();
        String fromTag = compactUuid();
        String callId = compactUuid() + "@loomsip.local";
        String targetUri = "sip:service@" + hostPort(server.address());

        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP " + hostPort(local.address()) + ";branch=" + branch)
                .add("Max-Forwards", "70")
                .add("From", "<sip:client@" + uriHost(local.address()) + ">;tag=" + fromTag)
                .add("To", "<" + targetUri + ">")
                .add("Call-ID", callId)
                .add("CSeq", "1 OPTIONS")
                .build();
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse(targetUri),
                SipVersion.SIP_2_0,
                headers,
                SipBody.empty()
        );
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String hostPort(InetSocketAddress address) {
        return uriHost(address) + ":" + address.getPort();
    }

    private static String uriHost(InetSocketAddress address) {
        String host = host(address);
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static String host(InetSocketAddress address) {
        InetAddress inetAddress = address.getAddress();
        return inetAddress.getHostAddress();
    }

    private final class ClientHandler implements SipMessageHandler {

        @Override
        public void onMessage(InboundSipMessage inbound) {
            if (inbound.message() instanceof SipResponse sipResponse) {
                response.complete(sipResponse);
            }
        }

        @Override
        public void onMalformedMessage(TransportContext context, SipParseException cause) {
            response.completeExceptionally(cause);
        }

        @Override
        public void onTransportError(Throwable cause) {
            response.completeExceptionally(cause);
        }
    }
}
