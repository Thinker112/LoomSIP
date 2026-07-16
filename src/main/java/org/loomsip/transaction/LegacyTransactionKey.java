package org.loomsip.transaction;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipUri;

import java.util.Objects;
import java.util.Optional;

/**
 * Composite server-transaction key for requests without an RFC 3261 branch.
 *
 * @param requestUri request URI used by legacy matching
 * @param fromTag From tag, when present
 * @param toTag To tag, when present
 * @param callId Call-ID value
 * @param sequenceNumber CSeq sequence number
 * @param topViaValue complete unfolded top Via value
 * @param method method used by transaction matching
 */
public record LegacyTransactionKey(
        SipUri requestUri,
        Optional<String> fromTag,
        Optional<String> toTag,
        String callId,
        long sequenceNumber,
        String topViaValue,
        SipMethod method
) implements TransactionKey {

    /**
     * Validates and creates a legacy transaction key.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if Call-ID or Via is blank
     */
    public LegacyTransactionKey {
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(fromTag, "fromTag");
        Objects.requireNonNull(toTag, "toTag");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(topViaValue, "topViaValue");
        Objects.requireNonNull(method, "method");
        if (callId.isBlank() || topViaValue.isBlank()) {
            throw new IllegalArgumentException("legacy Call-ID and top Via must not be blank");
        }
    }
}
