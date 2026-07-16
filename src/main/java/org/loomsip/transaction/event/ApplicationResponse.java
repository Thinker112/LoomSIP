package org.loomsip.transaction.event;

import org.loomsip.message.SipResponse;

import java.util.Objects;

/**
 * TU command to send a response through a server transaction.
 *
 * @param response immutable response
 */
public record ApplicationResponse(SipResponse response) implements TransactionEvent {

    /**
     * Validates and creates an application response command.
     *
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public ApplicationResponse {
        Objects.requireNonNull(response, "response");
    }
}
