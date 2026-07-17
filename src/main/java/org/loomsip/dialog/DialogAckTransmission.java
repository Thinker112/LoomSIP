package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;

import java.util.Objects;

/**
 * Cached immutable 2xx ACK and its unresolved SIP next hop.
 *
 * @param ack complete ACK request
 * @param nextHop SIP URI passed to the target resolver
 */
public record DialogAckTransmission(SipRequest ack, SipUri nextHop) {

    /** Validates the ACK transmission plan. */
    public DialogAckTransmission {
        Objects.requireNonNull(ack, "ack");
        Objects.requireNonNull(nextHop, "nextHop");
    }
}
