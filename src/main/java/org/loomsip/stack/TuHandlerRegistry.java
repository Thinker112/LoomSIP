package org.loomsip.stack;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipResponses;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable, startup-time registry of Transaction User request handlers.
 *
 * <pre>{@code
 * INVITE ---------> invite handler
 * other requests -> request handler
 * }</pre>
 */
public final class TuHandlerRegistry {

    private static final IncomingRequestHandler UNSUPPORTED = context -> context.respond(
            SipResponses.createResponse(context.request(), 501, "Not Implemented")
    );

    private final IncomingRequestHandler inviteHandler;
    private final IncomingRequestHandler requestHandler;

    private TuHandlerRegistry(IncomingRequestHandler inviteHandler, IncomingRequestHandler requestHandler) {
        this.inviteHandler = inviteHandler;
        this.requestHandler = requestHandler;
    }

    /**
     * Creates a mutable startup-time registry builder.
     *
     * @return builder that becomes immutable at build time
     */
    public static Builder builder() {
        return new Builder();
    }

    void dispatch(IncomingRequestContext context, Consumer<Throwable> failureReporter) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(failureReporter, "failureReporter");
        try {
            (SipMethod.INVITE.equals(context.request().method()) ? inviteHandler : requestHandler)
                    .onRequest(context);
        } catch (Throwable failure) {
            try {
                failureReporter.accept(failure);
            } catch (Throwable ignored) {
                // Observability must not prevent the Transaction from receiving its fallback response.
            }
            if (!context.hasResponded()) {
                context.respond(SipResponses.createResponse(context.request(), 500, "Server Internal Error"));
            }
        }
    }

    /** Builder used before Stack startup to register request capabilities. */
    public static final class Builder {

        private IncomingRequestHandler inviteHandler = UNSUPPORTED;
        private IncomingRequestHandler requestHandler = UNSUPPORTED;

        /**
         * Registers the handler for initial INVITE requests.
         *
         * @param handler application handler
         * @return this builder
         */
        public Builder inviteHandler(IncomingRequestHandler handler) {
            inviteHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Registers the fallback handler for non-INVITE requests.
         *
         * @param handler application handler
         * @return this builder
         */
        public Builder requestHandler(IncomingRequestHandler handler) {
            requestHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Freezes the registered handlers for one Stack runtime.
         *
         * @return immutable handler registry
         */
        public TuHandlerRegistry build() {
            return new TuHandlerRegistry(inviteHandler, requestHandler);
        }
    }
}
