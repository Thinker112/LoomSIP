package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;

import java.util.Objects;

/** Immutable request and next hop produced atomically by a Dialog Mailbox. */
record DialogPreparedRequest(SipRequest request, SipUri nextHop) {

    DialogPreparedRequest {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(nextHop, "nextHop");
    }
}
