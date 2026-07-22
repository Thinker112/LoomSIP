package org.loomsip.stack;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable inbound request data with a one-time final server-transaction response decision.
 *
 * <pre>{@code
 * SipRequest + TransportContext --> IncomingRequestContext --> sendResponse()
 * }</pre>
 */
public final class IncomingRequestContext {

    private final SipRequest request;
    private final TransportContext transportContext;
    private final Consumer<SipResponse> responder;
    private final Object responseMonitor = new Object();
    private boolean finalResponseSelected;

    IncomingRequestContext(
            SipRequest request,
            TransportContext transportContext,
            Consumer<SipResponse> responder
    ) {
        this.request = Objects.requireNonNull(request, "request");
        this.transportContext = Objects.requireNonNull(transportContext, "transportContext");
        this.responder = Objects.requireNonNull(responder, "responder");
    }

    /**
     * Returns the immutable SIP request.
     *
     * @return inbound request
     */
    public SipRequest request() {
        return request;
    }

    /**
     * Returns the inbound network metadata.
     *
     * @return local and remote transport metadata
     */
    public TransportContext transportContext() {
        return transportContext;
    }

    /**
     * Sends a provisional response or the only final response accepted by this context.
     *
     * <p>The selected response is submitted to the owning server Transaction,
     * so SIP retransmission and state transitions remain mailbox-owned.</p>
     *
     * @param response correlated SIP response
     * @return {@code true} when accepted; {@code false} after a final response
     */
    public boolean respond(SipResponse response) {
        Objects.requireNonNull(response, "response");
        synchronized (responseMonitor) {
            if (finalResponseSelected) {
                return false;
            }
            if (response.statusCode() >= 200) {
                finalResponseSelected = true;
            }
            responder.accept(response);
            return true;
        }
    }

    /**
     * Reports whether application code has already selected a final response.
     *
     * @return whether a final response has been accepted
     */
    public boolean hasResponded() {
        synchronized (responseMonitor) {
            return finalResponseSelected;
        }
    }
}
