package org.loomsip.stack;

/**
 * Application callback for one inbound SIP request after Transaction correlation.
 *
 * <pre>{@code
 * Server Transaction --> IncomingRequestContext --> IncomingRequestHandler
 * }</pre>
 */
@FunctionalInterface
public interface IncomingRequestHandler {

    /**
     * Handles a request and eventually makes its one response decision.
     *
     * @param context immutable request metadata and response capability
     */
    void onRequest(IncomingRequestContext context);
}
