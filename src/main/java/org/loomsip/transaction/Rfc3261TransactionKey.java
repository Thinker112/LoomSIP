package org.loomsip.transaction;

import org.loomsip.message.SipMethod;
import org.loomsip.message.header.SentBy;

import java.util.Objects;

/**
 * RFC 3261 transaction key for a Via branch containing the magic cookie.
 *
 * @param branch exact branch parameter value
 * @param sentBy normalized sent-by with an explicit effective port
 * @param method method used by transaction matching
 */
public record Rfc3261TransactionKey(
        String branch,
        SentBy sentBy,
        SipMethod method
) implements TransactionKey {

    /**
     * Validates and creates an RFC 3261 key.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if branch is blank or sent-by has no explicit port
     */
    public Rfc3261TransactionKey {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(sentBy, "sentBy");
        Objects.requireNonNull(method, "method");
        if (branch.isBlank()) {
            throw new IllegalArgumentException("transaction branch must not be blank");
        }
        if (sentBy.port() == 0) {
            throw new IllegalArgumentException("transaction sent-by must contain an effective port");
        }
    }
}
