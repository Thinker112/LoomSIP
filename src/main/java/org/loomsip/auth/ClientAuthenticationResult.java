package org.loomsip.auth;

import org.loomsip.exchange.RequestAttempt;
import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result after the authentication coordinator processes one response.
 *
 * @param disposition response handling outcome
 * @param response original immutable SIP response
 * @param retryAttempt started retry attempt when disposition is {@code RETRIED}
 * @param <T> concrete transaction handle type
 */
public record ClientAuthenticationResult<T>(
        ClientAuthenticationDisposition disposition,
        SipResponse response,
        Optional<RequestAttempt<T>> retryAttempt
) {

    /** Validates result components and disposition consistency. */
    public ClientAuthenticationResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(response, "response");
        retryAttempt = Objects.requireNonNull(retryAttempt, "retryAttempt");
        if (disposition == ClientAuthenticationDisposition.RETRIED && retryAttempt.isEmpty()) {
            throw new IllegalArgumentException("RETRIED result requires a retry attempt");
        }
        if (disposition != ClientAuthenticationDisposition.RETRIED && retryAttempt.isPresent()) {
            throw new IllegalArgumentException("only RETRIED result may contain a retry attempt");
        }
    }
}
